package satisfactory.runner

import satisfactory.protocol.*

import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue, CopyOnWriteArrayList}
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*

/**
 * An in-memory API for the runner's tests: a queue of claims, a blob store, and a record of every
 * call. The fold rules are the entity's; this only does what a test needs — "strictly better is
 * recorded" and a scriptable heartbeat reply.
 */
final class ScriptedChannel extends ControlChannel:

  val calls     = CopyOnWriteArrayList[String]()
  val reports   = CopyOnWriteArrayList[ReportRequest]()
  val phases    = CopyOnWriteArrayList[(String, PhaseRequest)]()
  val completes = CopyOnWriteArrayList[(String, CompleteRequest)]()
  val releases  = CopyOnWriteArrayList[(String, ReleaseRequest)]()
  val fails     = CopyOnWriteArrayList[(String, FailRequest)]()
  val blobs     = ConcurrentHashMap[String, Array[Byte]]()

  private val queue = ConcurrentLinkedQueue[ClaimResponse]()
  private val best  = ConcurrentHashMap[String, ScoreKey]()
  private var seq   = 0L

  /** What every heartbeat answers; tests swap it to terminate or drain. */
  val heartbeatReply = AtomicReference(HeartbeatResponse(false, false, 20_000, false))

  /** Reports for this dataset answer 409 once this many have been accepted. */
  val staleAfterReports = ConcurrentHashMap[String, Int]()

  @volatile var drain = false

  def enqueue(claim: ClaimResponse, input: Array[Byte]): Unit =
    blobs.put(claim.spec.inputRef, input): Unit
    queue.add(claim): Unit

  def register(workerId: String, registration: WorkerRegistration) =
    calls.add(s"register $workerId"): Unit
    Right(WorkerAck(drain))

  def deregister(workerId: String) =
    calls.add(s"deregister $workerId"): Unit
    Right(())

  def claim(request: ClaimRequest) =
    Right(Option(queue.poll()).map(_.copy(drain = drain)))

  def phase(datasetId: String, request: PhaseRequest) =
    calls.add(s"phase $datasetId ${request.phase}"): Unit
    phases.add(datasetId -> request): Unit
    Right(())

  def report(datasetId: String, request: ReportRequest) = synchronized {
    val accepted = reports.asScala.count(_.solutionRef.startsWith(datasetId))
    if staleAfterReports.asScala.get(datasetId).exists(accepted >= _) then
      Left(ChannelError.Stale("superseded"))
    else
      reports.add(request): Unit
      val key    = ScoreKey(request.scoreKey)
      val better = Option(best.get(datasetId)).forall(key.isBetterThan)
      if better then
        best.put(datasetId, key): Unit
        seq += 1
      Right(ReportResponse(better, seq, Nil))
  }

  def heartbeat(datasetId: String, request: HeartbeatRequest) =
    calls.add(s"heartbeat $datasetId"): Unit
    Right(heartbeatReply.get().copy(drain = heartbeatReply.get().drain || drain))

  def complete(datasetId: String, request: CompleteRequest) =
    calls.add(s"complete $datasetId ${request.reason}"): Unit
    completes.add(datasetId -> request): Unit
    Right(())

  def fail(datasetId: String, request: FailRequest) =
    calls.add(s"fail $datasetId"): Unit
    fails.add(datasetId -> request): Unit
    Right(())

  def release(datasetId: String, request: ReleaseRequest) =
    calls.add(s"release $datasetId ${request.reason}"): Unit
    releases.add(datasetId -> request): Unit
    Right(())

  def getBlob(ref: String) =
    Option(blobs.get(ref)).toRight(ChannelError.Rejected(404, s"no blob $ref"))

  def putBlob(ref: String, contentType: String, bytes: Array[Byte]) =
    blobs.put(ref, bytes): Unit
    Right(())

object ScriptedChannel:

  def spec(
      operation: String = Operation.Solve,
      spentLimit: String = "PT2S",
      datasetId: String = "ds_1"
  ): DatasetSpec =
    DatasetSpec(
      tenantId = "t_1",
      modelId = "employee-scheduling",
      modelVersion = "v1",
      entity = "schedules",
      operation = operation,
      priority = 5,
      inputRef = BlobRefs.input(datasetId),
      patchRef = None,
      parentId = None,
      originId = None,
      select = None,
      config = ResolvedConfig(TerminationConfig(spentLimit = Some(spentLimit)), Map.empty, 1, None),
      submittedConfig = None
    )

  def claim(datasetId: String, epoch: Long = 1, operation: String = Operation.Solve, spentLimit: String = "PT2S") =
    ClaimResponse(
      datasetId,
      epoch,
      spec(operation, spentLimit, datasetId),
      ClaimMode.Full,
      None,
      20_000,
      drain = false
    )
