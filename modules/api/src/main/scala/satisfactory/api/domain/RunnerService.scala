package satisfactory.api.domain

import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import satisfactory.api.application.*
import satisfactory.api.blobs.Blob
import satisfactory.protocol.*
import satisfactory.spi.Json

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The runner protocol's server side (contracts/runner-protocol.md), shared by the HTTP endpoint the
 * `solver` service calls and the in-process channel `sbt api/run` uses. Refusals are `CommandError`s;
 * a `Conflict` is a stale lease.
 */
final class RunnerService(ctx: ApiContext):

  private val rows = ctx.views.forView(DatasetRows)

  private def dataset(id: String) = ctx.client.forEventSourcedEntity(EntityId(id))
  private def tenant(id: String)  = ctx.client.forEventSourcedEntity(EntityId(id))

  private def worker(id: String) = ctx.client.forKeyValueEntity(EntityId(id))

  /** Whether an operator has asked this worker to drain; unknown workers are not draining. */
  def drainOf(workerId: String): Boolean =
    workerId.nonEmpty && worker(workerId).call(WorkerEntity.get).invoke().draining

  def register(workerId: String, registration: WorkerRegistration): WorkerAck =
    WorkerAck(worker(workerId).call(WorkerEntity.register).invoke(WorkerSeen(registration.models, registration.slots, ctx.now())))

  def deregister(workerId: String): Unit = worker(workerId).call(WorkerEntity.deregister).invoke(): Unit

  private def holding(workerId: String, datasetId: String, on: Boolean): Unit =
    if workerId.nonEmpty then
      try worker(workerId).call(WorkerEntity.holds).invoke(WorkerHolds(datasetId, on)): Unit
      catch case scala.util.control.NonFatal(_) => ()

  private def holderOf(datasetId: String): String =
    try dataset(datasetId).call(DatasetEntity.get).invoke().lease.fold("")(_.workerId)
    catch case _: CommandError => ""

  /**
   * The view proposes, the entities decide (research R6): candidates from the listing, which may be
   * stale; for each, an exact tenant slot, then the dataset's own lease. Long-polls up to `wait`.
   */
  def claim(request: ClaimRequest, wait: FiniteDuration = Duration.Zero): Option[ClaimResponse] =
    val deadline = System.nanoTime() + wait.toNanos
    var claimed  = tryClaim(request)
    while claimed.isEmpty && System.nanoTime() < deadline && !drainOf(request.workerId) do
      Thread.sleep(250)
      claimed = tryClaim(request)
    claimed

  private def tryClaim(request: ClaimRequest): Option[ClaimResponse] =
    if drainOf(request.workerId) then None
    else
      val candidates = request.datasetId match
        case Some(id) => rows.get(id).toVector
        case None     => DatasetRows.claimCandidates(rows, request.models)
      candidates.iterator
        .filter(row => request.models.contains(row.modelKey))
        .map(row => attempt(request.workerId, row))
        .collectFirst { case Some(claim) => claim }

  private def attempt(workerId: String, row: DatasetRow): Option[ClaimResponse] =
    val granted =
      try tenant(row.tenantId).call(TenantEntity.acquireSlot).invoke(SlotRequest(row.datasetId))
      catch case e: CommandError if e.code == ErrorCode.NotFound => false
    if !granted then None
    else
      try
        val grant = dataset(row.datasetId).call(DatasetEntity.lease).invoke(LeaseDataset(workerId, ctx.now()))
        DatasetTimers.armLease(ctx.timers, row.datasetId, grant.epoch, ctx.settings.leaseTtl)
        holding(workerId, row.datasetId, on = true)
        Some(
          ClaimResponse(
            row.datasetId,
            grant.epoch,
            grant.spec,
            grant.mode,
            grant.warmStartRef,
            ctx.settings.leaseTtl.toMillis,
            drainOf(workerId)
          )
        )
      catch
        case e: CommandError if e.code == ErrorCode.Conflict || e.code == ErrorCode.NotFound =>
          // Someone else holds it, or it finished: give back the slot only if nobody will need it.
          val finished =
            try dataset(row.datasetId).call(DatasetEntity.get).invoke().isFinal
            catch case _: CommandError => true
          if finished then tenant(row.tenantId).call(TenantEntity.releaseSlot).invoke(SlotRequest(row.datasetId)): Unit
          None

  def phase(datasetId: String, request: PhaseRequest): Unit =
    val summary = request.validation.map(summarise)
    dataset(datasetId)
      .call(DatasetEntity.phase)
      .invoke(
        PhaseUpdate(
          request.epoch,
          request.phase,
          request.validation,
          summary,
          request.inputScore,
          request.inputMetrics,
          request.analysisRef,
          ctx.now()
        )
      ): Unit

  /** Timefold's legacy `validationResult` summary on the metadata: the codes, split by severity. */
  private def summarise(result: ValidationResult): ValidationSummary =
    val issues = Json.parse(result.issues.bytes).elements().asScala.toList
    def codes(severity: String) =
      issues.filter(i => Option(i.get("severity")).exists(_.asText() == severity)).map(i => i.path("code").asText())
    ValidationSummary(result.status, codes("ERROR"), codes("WARNING"))

  /** Rule 2 lives in the entity; the bodies it lets go (or never kept) are deleted here. */
  def report(datasetId: String, request: ReportRequest): ReportResponse =
    val result = dataset(datasetId)
      .call(DatasetEntity.recordSolution)
      .invoke(
        RecordSolution(request.epoch, request.score, request.scoreKey, request.feasible, request.solutionRef, request.kpis, ctx.now())
      )
    if result.recorded then ctx.blobs.delete(result.evicted) else ctx.blobs.delete(List(request.solutionRef))
    ReportResponse(result.recorded, result.seq, result.evicted)

  def heartbeat(datasetId: String, request: HeartbeatRequest): HeartbeatResponse =
    val control = dataset(datasetId).call(DatasetEntity.heartbeat).invoke(Beat(request.epoch, request.logLines.take(50).map(satisfactory.runner.LogHygiene.clean)))
    DatasetTimers.armLease(ctx.timers, datasetId, request.epoch, ctx.settings.leaseTtl)
    HeartbeatResponse(control.terminate, control.force, ctx.settings.leaseTtl.toMillis, drainOf(request.workerId))

  def complete(datasetId: String, request: CompleteRequest): Unit =
    val holder = holderOf(datasetId)
    dataset(datasetId)
      .call(DatasetEntity.complete)
      .invoke(CompleteDataset(request.epoch, request.reason, request.analysisRef, ctx.now())): Unit
    holding(holder, datasetId, on = false)
    satisfactory.api.metrics.Counters.solvesCompleted.incrementAndGet(): Unit

  def fail(datasetId: String, request: FailRequest): Unit =
    val holder = holderOf(datasetId)
    dataset(datasetId).call(DatasetEntity.fail).invoke(FailDataset(request.epoch, satisfactory.runner.LogHygiene.clean(request.message), ctx.now())): Unit
    holding(holder, datasetId, on = false)
    satisfactory.api.metrics.Counters.solvesFailed.incrementAndGet(): Unit

  def release(datasetId: String, request: ReleaseRequest): Unit =
    val holder = holderOf(datasetId)
    dataset(datasetId).call(DatasetEntity.release).invoke(ReleaseDataset(request.epoch, request.reason, ctx.now())): Unit
    ctx.timers.delete(DatasetTimers.leaseName(datasetId))
    holding(holder, datasetId, on = false)
    satisfactory.api.metrics.Counters.requeues.incrementAndGet(): Unit

  def getBlob(ref: String): Option[Blob] = ctx.blobs.get(ref)

  def putBlob(ref: String, contentType: String, bytes: Array[Byte]): Unit = ctx.blobs.put(ref, contentType, bytes)
