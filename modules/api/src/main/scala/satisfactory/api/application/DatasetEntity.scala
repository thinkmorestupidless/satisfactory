package satisfactory.api.application

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Done, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import satisfactory.protocol.*

import java.time.Instant

// ── Command inputs and replies ──────────────────────────────────────────────────────────────
// Every command carries its time: the entity has no clock, and events must say when they happened.

final case class CreateDataset(spec: DatasetSpec, name: Option[String], tags: List[String], at: Instant)
final case class SolveDataset(priority: Option[Int], at: Instant)
final case class TerminateDataset(by: String, force: Boolean, at: Instant)
final case class SupersedeDataset(childId: String, at: Instant)
final case class UpdateMetadata(name: Option[String], tags: Option[List[String]])
final case class At(at: Instant)

final case class LeaseDataset(workerId: String, at: Instant)
final case class LeaseGrant(epoch: Long, spec: DatasetSpec, mode: String, warmStartRef: Option[String])

final case class PhaseUpdate(
    epoch: Long,
    phase: String,
    validation: Option[ValidationResult],
    summary: Option[ValidationSummary],
    inputScore: Option[String],
    inputMetrics: Option[RawJson],
    analysisRef: Option[String],
    at: Instant
)

final case class RecordSolution(
    epoch: Long,
    score: String,
    key: List[BigDecimal],
    feasible: Boolean,
    solutionRef: String,
    kpis: Option[RawJson],
    at: Instant
)
final case class RecordResult(recorded: Boolean, seq: Long, evicted: List[String])

final case class Beat(epoch: Long, lines: List[String])
final case class Control(terminate: Boolean, force: Boolean)

final case class CompleteDataset(epoch: Long, reason: String, analysisRef: Option[String], at: Instant)
final case class FailDataset(epoch: Long, message: String, at: Instant)
final case class ReleaseDataset(epoch: Long, reason: String, at: Instant)
final case class ExpireLease(epoch: Long, maxAttempts: Int, at: Instant)

/** What a stream reads: the ring entries after a sequence number, or how many it can no longer give. */
final case class Updates(
    exists: Boolean,
    entries: List[RingEntry],
    skipped: Long,
    isFinal: Boolean,
    supersededBy: Option[String],
    seq: Long
)

/**
 * One dataset: the source of truth for everything that happens to it (DESIGN.md §3.3). Three rules,
 * all enforced here so replay agrees with the live run:
 *
 *   1. every worker command carries its lease epoch, and a stale one is refused with `Conflict`
 *      before anything is persisted — a worker presumed dead that comes back cannot overwrite its
 *      successor;
 *   2. only a strictly better score is recorded, and the entity assigns its `seq`;
 *   3. no event carries a body: solutions, inputs and patches are blob references.
 */
final class DatasetEntity(context: EventSourcedEntityContext) extends EventSourcedEntity[Dataset, DatasetEvent]:
  import DatasetEvent.*

  def emptyState: Dataset = DatasetFold.empty(context.entityId)

  def applyEvent(event: DatasetEvent): Dataset = DatasetFold(currentState, event)

  private def d = currentState

  private def missing[R]: ReadOnlyEffect[R] =
    effects.error(s"dataset ${context.entityId} does not exist", ErrorCode.NotFound)

  // ── Client-facing ──────────────────────────────────────────────────────────────────────────

  def create(request: CreateDataset): Effect[Metadata] =
    if d.exists then effects.error(s"dataset ${context.entityId} already exists", ErrorCode.Conflict)
    else
      val events =
        Vector(Created(request.spec, request.name, request.tags, request.at)) ++
          Option.when(request.spec.operation == Operation.Solve)(SolveRequested(None, request.at))
      effects.persistAll(events).thenReply(_.metadata)

  def solve(request: SolveDataset): Effect[Metadata] =
    if !d.exists then missing
    else if d.status != SolvingStatus.DatasetComputed || d.solveRequested then
      effects.error(
        s"only a dataset submitted with operation=NONE and computed can be solved; this one is ${d.status}",
        ErrorCode.BadRequest
      )
    else effects.persist(SolveRequested(request.priority, request.at)).thenReply(_.metadata)

  /**
   * Terminate (`DELETE /{id}`): a success that keeps its best solution. A queued dataset, or a forced
   * terminate, finishes at once; a leased one is told on its next heartbeat. Final: unchanged.
   */
  def requestTerminate(request: TerminateDataset): Effect[Metadata] =
    if !d.exists then missing
    else if d.isFinal then effects.reply(d.metadata)
    else if d.lease.isEmpty || request.force then
      effects
        .persist(
          TerminationRequested(request.by, request.force, request.at),
          Finished(CompleteReason.Terminated, None, request.at)
        )
        .thenReply(_.metadata)
    else if d.terminate.isDefined then effects.reply(d.metadata)
    else effects.persist(TerminationRequested(request.by, force = false, request.at)).thenReply(_.metadata)

  /** A child was derived from this dataset while it was active: it completes with its best so far. */
  def supersede(request: SupersedeDataset): Effect[Metadata] =
    if !d.exists then missing
    else if d.isFinal then effects.reply(d.metadata)
    else
      effects
        .persist(Superseded(request.childId, request.at), Finished(CompleteReason.Superseded, None, request.at))
        .thenReply(_.metadata)

  def updateMetadata(request: UpdateMetadata): Effect[Metadata] =
    if !d.exists then missing
    else if d.purged then effects.error("a purged dataset's metadata cannot change", ErrorCode.BadRequest)
    else
      MetadataRules.check(request.name, request.tags) match
        case Some(problem) => effects.error(problem, ErrorCode.BadRequest)
        case None          => effects.persist(MetadataUpdated(request.name, request.tags)).thenReply(_.metadata)

  def purge(request: At): Effect[Metadata] =
    if !d.exists then missing
    else if !d.isFinal then effects.error("a dataset cannot be purged while it is solving", ErrorCode.BadRequest)
    else if d.purged then effects.reply(d.metadata)
    else effects.persist(Purged(request.at)).thenReply(_.metadata)

  def restore(request: At): Effect[Metadata] =
    if !d.exists then missing
    else if d.expired then effects.error("the dataset's retention has expired", ErrorCode.NotFound)
    else if !d.purged then effects.reply(d.metadata)
    else effects.persist(Restored(request.at)).thenReply(_.metadata)

  def get: ReadOnlyEffect[Dataset] = if d.exists then effects.reply(d) else missing

  def metadata: ReadOnlyEffect[Metadata] = if d.exists then effects.reply(d.metadata) else missing

  /**
   * The ring entries after `after`. When the ring no longer reaches back that far, the latest entry
   * and how many were skipped — the stream then opens with the current best.
   */
  def updatesSince(after: Long): ReadOnlyEffect[Updates] =
    if !d.exists then effects.reply(Updates(exists = false, Nil, 0, isFinal = false, None, 0))
    else
      val newer = d.ring.filter(_.seq > after).toList
      val (entries, skipped) = newer.headOption match
        case Some(first) if first.seq > after + 1 && after > 0 =>
          (d.ring.lastOption.toList, math.max(0L, d.seq - after - 1))
        case _ => (newer, 0L)
      effects.reply(Updates(exists = true, entries, skipped, d.isFinal, d.supersededBy, d.seq))

  // ── Worker-facing: every command carries the lease epoch ──────────────────────────────────

  /** The arbiter: of any number of claimants, one gets a lease with the next epoch. */
  def lease(request: LeaseDataset): Effect[LeaseGrant] =
    if !d.exists then missing
    else if !d.queued || d.lease.isDefined || d.isFinal then
      effects.error("the dataset is not claimable", ErrorCode.Conflict)
    else
      val epoch = d.lastEpoch + 1
      val mode =
        if d.warmStartRef.isDefined then ClaimMode.Warm
        else if d.computed then ClaimMode.Solve
        else ClaimMode.Full
      effects
        .persist(Leased(request.workerId, epoch, request.at))
        .thenReply(s => LeaseGrant(epoch, s.spec.get, mode, s.warmStartRef))

  private def fenced[R](epoch: Long)(effect: => Effect[R]): Effect[R] =
    if !d.exists then missing
    else if !d.lease.exists(_.epoch == epoch) then
      effects.error(s"epoch $epoch does not hold the lease (current ${d.lastEpoch})", ErrorCode.Conflict)
    else effect

  def phase(update: PhaseUpdate): Effect[Done] = fenced(update.epoch) {
    val summary = update.summary.getOrElse(ValidationSummary(ValidationStatus.Ok))
    update.phase match
      case Phase.Validated =>
        update.validation match
          case Some(result) => effects.persist(Validated(result, summary, update.at)).thenReply(_ => Done)
          case None         => effects.error("validated needs a validation result")
      case Phase.Invalid =>
        update.validation match
          case Some(result) => effects.persist(Invalidated(result, summary, update.at)).thenReply(_ => Done)
          case None         => effects.error("invalid needs a validation result")
      case Phase.Computed =>
        effects
          .persist(Computed(update.inputScore, update.inputMetrics, update.analysisRef, update.at))
          .thenReply(_ => Done)
      case Phase.Started =>
        if d.started.isDefined && SolvingStatus.rank(d.status) >= SolvingStatus.rank(SolvingStatus.Started) then
          effects.reply(Done)
        else effects.persist(SolvingStarted(update.at)).thenReply(_ => Done)
      case Phase.Active =>
        if d.active.isDefined && d.status == SolvingStatus.Active then effects.reply(Done)
        else effects.persist(SolvingActive(update.at)).thenReply(_ => Done)
      case other => effects.error(s"unknown phase '$other'")
  }

  /** Rule 2: strictly better, or nothing is persisted. */
  def recordSolution(request: RecordSolution): Effect[RecordResult] = fenced(request.epoch) {
    val key = ScoreKey(request.key)
    if d.best.exists(b => !key.isBetterThan(b.scoreKey)) then
      effects.reply(RecordResult(recorded = false, d.seq, Nil))
    else
      val evicted = DatasetFold.evictions(d, request.solutionRef, request.feasible)
      effects
        .persist(
          SolutionRecorded(request.score, request.key, request.feasible, request.solutionRef, request.kpis, evicted, request.at)
        )
        .thenReply(s => RecordResult(recorded = true, s.seq, evicted))
  }

  /** Control rides the heartbeat's reply. Heartbeats themselves are not journaled; log lines are. */
  def heartbeat(request: Beat): Effect[Control] = fenced(request.epoch) {
    val control = Control(d.terminate.isDefined, d.terminate.exists(_.force))
    if request.lines.isEmpty then effects.reply(control)
    else effects.persist(LogsAppended(request.lines)).thenReply(_ => control)
  }

  def complete(request: CompleteDataset): Effect[Metadata] = fenced(request.epoch) {
    effects.persist(Finished(request.reason, request.analysisRef, request.at)).thenReply(_.metadata)
  }

  def fail(request: FailDataset): Effect[Metadata] = fenced(request.epoch) {
    effects.persist(Failed(request.message, request.at)).thenReply(_.metadata)
  }

  /** A graceful hand-back (drain, SIGTERM): back in the queue, warm-started from its best. */
  def release(request: ReleaseDataset): Effect[Metadata] = fenced(request.epoch) {
    if d.terminate.isDefined then
      effects
        .persist(
          LeaseLost(request.epoch, request.reason, request.at),
          Finished(CompleteReason.Terminated, None, request.at)
        )
        .thenReply(_.metadata)
    else effects.persist(LeaseLost(request.epoch, request.reason, request.at)).thenReply(_.metadata)
  }

  // ── Timer-facing: at least once, and a no-op when there is nothing left to do ─────────────

  def expireLease(request: ExpireLease): Effect[Done] =
    if !d.exists || !d.lease.exists(_.epoch == request.epoch) then effects.reply(Done)
    else
      val lost = LeaseLost(request.epoch, LeaseLossReason.Expired, request.at)
      if d.terminate.isDefined then
        effects.persist(lost, Finished(CompleteReason.Terminated, None, request.at)).thenReply(_ => Done)
      else if d.attempt + 1 >= request.maxAttempts then
        effects
          .persist(lost, Failed(s"the lease was lost ${d.attempt + 1} times", request.at))
          .thenReply(_ => Done)
      else effects.persist(lost).thenReply(_ => Done)

  def expireLifetime(request: At): Effect[Done] =
    if !d.exists || d.isFinal then effects.reply(Done)
    else effects.persist(Finished(CompleteReason.Lifetime, None, request.at)).thenReply(_ => Done)

  /** True when the bodies should now be deleted. */
  def expireRetention(request: At): Effect[Boolean] =
    if !d.exists || !d.isFinal || d.expired then effects.reply(false)
    else effects.persist(Expired(request.at)).thenReply(_ => true)

object MetadataRules:
  val MaxName = 255
  val MaxTags = 100

  def check(name: Option[String], tags: Option[List[String]]): Option[String] =
    if name.exists(_.length > MaxName) then Some(s"name is longer than $MaxName characters")
    else if tags.exists(_.size > MaxTags) then Some(s"more than $MaxTags tags")
    else if tags.exists(t => t.distinct.size != t.size) then Some("tags must be unique")
    else None

object DatasetEntity
    extends EventSourcedEntity.Companion[DatasetEntity, Dataset, DatasetEvent](
      componentId = ComponentId("dataset"),
      stateSerializer = Codecs.serializer[Dataset]("dataset"),
      eventSerializer = Codecs.serializer[DatasetEvent]("dataset-event")
    ):

  override def snapshotEvery: Option[Int] = Some(50)

  given Serializer[CreateDataset]    = Codecs.serializer[CreateDataset]("create-dataset")
  given Serializer[SolveDataset]     = Codecs.serializer[SolveDataset]("solve-dataset")
  given Serializer[TerminateDataset] = Codecs.serializer[TerminateDataset]("terminate-dataset")
  given Serializer[SupersedeDataset] = Codecs.serializer[SupersedeDataset]("supersede-dataset")
  given Serializer[UpdateMetadata]   = Codecs.serializer[UpdateMetadata]("update-metadata")
  given Serializer[At]               = Codecs.serializer[At]("at")
  given Serializer[Metadata]         = Codecs.serializer[Metadata]("metadata")
  given Serializer[Updates]          = Codecs.serializer[Updates]("updates")
  given Serializer[LeaseDataset]     = Codecs.serializer[LeaseDataset]("lease-dataset")
  given Serializer[LeaseGrant]       = Codecs.serializer[LeaseGrant]("lease-grant")
  given Serializer[PhaseUpdate]      = Codecs.serializer[PhaseUpdate]("phase-update")
  given Serializer[RecordSolution]   = Codecs.serializer[RecordSolution]("record-solution")
  given Serializer[RecordResult]     = Codecs.serializer[RecordResult]("record-result")
  given Serializer[Beat]             = Codecs.serializer[Beat]("beat")
  given Serializer[Control]          = Codecs.serializer[Control]("control")
  given Serializer[CompleteDataset]  = Codecs.serializer[CompleteDataset]("complete-dataset")
  given Serializer[FailDataset]      = Codecs.serializer[FailDataset]("fail-dataset")
  given Serializer[ReleaseDataset]   = Codecs.serializer[ReleaseDataset]("release-dataset")
  given Serializer[ExpireLease]      = Codecs.serializer[ExpireLease]("expire-lease")

  def create(context: EventSourcedEntityContext) = new DatasetEntity(context)

  // Wire names are protocol: timers and in-flight calls address them.
  val create           = command("create")(_.create)
  val solve            = command("solve")(_.solve)
  val requestTerminate = command("request-terminate")(_.requestTerminate)
  val supersede        = command("supersede")(_.supersede)
  val updateMetadata   = command("update-metadata")(_.updateMetadata)
  val purge            = command("purge")(_.purge)
  val restore          = command("restore")(_.restore)
  val get              = query("get")(_.get)
  val metadata         = query("metadata")(_.metadata)
  val updatesSince     = query("updates-since")(_.updatesSince)

  val lease          = command("lease")(_.lease)
  val phase          = command("phase")(_.phase)
  val recordSolution = command("record-solution")(_.recordSolution)
  val heartbeat      = command("heartbeat")(_.heartbeat)
  val complete       = command("complete")(_.complete)
  val fail           = command("fail")(_.fail)
  val release        = command("release")(_.release)

  val expireLease     = command("expire-lease")(_.expireLease)
  val expireLifetime  = command("expire-lifetime")(_.expireLifetime)
  val expireRetention = command("expire-retention")(_.expireRetention)
