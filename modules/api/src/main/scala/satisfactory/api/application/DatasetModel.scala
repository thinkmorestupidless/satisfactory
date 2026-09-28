package satisfactory.api.application

import satisfactory.protocol.*

import java.time.Instant

/** The worker holding a dataset, and the epoch that fences everyone else out. */
final case class Lease(workerId: String, epoch: Long, grantedAt: Instant)

/** The best solution so far: its score as text and as comparable levels, and where its body lives. */
final case class Best(
    seq: Long,
    score: String,
    key: List[BigDecimal],
    feasible: Boolean,
    solutionRef: String,
    kpis: Option[RawJson],
    at: Instant
):
  def scoreKey: ScoreKey = ScoreKey(key)

final case class TerminateRequest(by: String, force: Boolean, at: Instant)

/** One entry of the bounded ring streams read: the metadata as it was at `seq`. */
final case class RingEntry(seq: Long, metadata: Metadata)

/**
 * A dataset's state: the fold of its events (data-model.md). Nothing in here is a body — inputs and
 * solutions are blob references.
 */
final case class Dataset(
    id: String,
    spec: Option[DatasetSpec] = None,
    name: Option[String] = None,
    tags: List[String] = Nil,
    status: String = SolvingStatus.DatasetCreated,
    queued: Boolean = false,
    solveRequested: Boolean = false,
    computed: Boolean = false,
    attempt: Int = 0,
    lastEpoch: Long = 0,
    lease: Option[Lease] = None,
    best: Option[Best] = None,
    firstFeasibleRef: Option[String] = None,
    retained: Vector[String] = Vector.empty,
    warmStartRef: Option[String] = None,
    terminate: Option[TerminateRequest] = None,
    supersededBy: Option[String] = None,
    validation: Option[ValidationResult] = None,
    validationSummary: Option[ValidationSummary] = None,
    inputScore: Option[String] = None,
    inputMetrics: Option[RawJson] = None,
    analysisRef: Option[String] = None,
    submitted: Option[Instant] = None,
    started: Option[Instant] = None,
    active: Option[Instant] = None,
    completed: Option[Instant] = None,
    shutdown: Option[Instant] = None,
    failureMessage: Option[String] = None,
    purged: Boolean = false,
    purgedAt: Option[Instant] = None,
    expired: Boolean = false,
    logs: Vector[String] = Vector.empty,
    seq: Long = 0,
    ring: Vector[RingEntry] = Vector.empty
):
  def exists: Boolean = spec.isDefined

  /**
   * Final: streams close and webhooks fire. `DATASET_COMPUTED` is final only while nobody has asked
   * it to solve (`operation=NONE`).
   */
  def isFinal: Boolean =
    SolvingStatus.terminal.contains(status) ||
      (status == SolvingStatus.DatasetComputed && !solveRequested)

  def isActive: Boolean = exists && !isFinal

  def tenantId: String = spec.fold("")(_.tenantId)

  def metadata: Metadata = metadataAt(seq)

  private def metadataAt(atSeq: Long): Metadata =
    Metadata(
      id = id,
      parentId = spec.flatMap(_.parentId),
      originId = spec.flatMap(_.originId),
      name = name,
      tags = tags,
      solverStatus = status,
      score = best.map(_.score).orElse(inputScore),
      submitDateTime = submitted,
      startDateTime = started,
      activeDateTime = active,
      completeDateTime = completed,
      shutdownDateTime = shutdown,
      validationResult = validationSummary,
      failureMessage = failureMessage,
      supersededBy = supersededBy,
      queuePaused = None,
      seq = atSeq
    )

  /** The ring's view of the world: everything public except the sequence number itself. */
  private[application] def observable: Metadata = metadataAt(0)

  /** For the listing view: the fold's state without the parts only streams and logs need. */
  def compact: Dataset = copy(ring = ring.takeRight(1), logs = Vector.empty)

enum DatasetEvent:
  case Created(spec: DatasetSpec, name: Option[String], tags: List[String], at: Instant)
  case SolveRequested(priority: Option[Int], at: Instant)
  case Leased(workerId: String, epoch: Long, at: Instant)
  case Validated(result: ValidationResult, summary: ValidationSummary, at: Instant)
  case Invalidated(result: ValidationResult, summary: ValidationSummary, at: Instant)
  case Computed(inputScore: Option[String], inputMetrics: Option[RawJson], analysisRef: Option[String], at: Instant)
  case SolvingStarted(at: Instant)
  case SolvingActive(at: Instant)
  case SolutionRecorded(
      score: String,
      key: List[BigDecimal],
      feasible: Boolean,
      solutionRef: String,
      kpis: Option[RawJson],
      evicted: List[String],
      at: Instant
  )
  case TerminationRequested(by: String, force: Boolean, at: Instant)
  case Superseded(childId: String, at: Instant)
  case LeaseLost(epoch: Long, reason: String, at: Instant)
  case Finished(reason: String, analysisRef: Option[String], at: Instant)
  case Failed(message: String, at: Instant)
  case MetadataUpdated(name: Option[String], tags: Option[List[String]])
  case LogsAppended(lines: List[String])
  case Purged(at: Instant)
  case Restored(at: Instant)
  case Expired(at: Instant)

object LeaseLossReason:
  val Expired  = "expired"
  val Drained  = ReleaseReason.Drained
  val Shutdown = ReleaseReason.Shutdown

/**
 * The fold: how each event changes a dataset. Pure, and shared by the entity and the listing view,
 * so replay, the live run and the listing agree by construction.
 */
object DatasetFold:

  val RingSize            = 50
  val LogLimit            = 200
  val RetainedIntermediate = 10

  def empty(id: String): Dataset = Dataset(id)

  def apply(state: Dataset, event: DatasetEvent): Dataset =
    refreshRing(step(state, event), force = event.isInstanceOf[DatasetEvent.SolutionRecorded])

  private def forward(state: Dataset, status: String): String =
    if SolvingStatus.rank(status) > SolvingStatus.rank(state.status) then status else state.status

  private def step(d: Dataset, event: DatasetEvent): Dataset =
    import DatasetEvent.*
    event match
      case Created(spec, name, tags, at) =>
        d.copy(
          spec = Some(spec),
          name = name,
          tags = tags,
          status = SolvingStatus.DatasetCreated,
          queued = true,
          submitted = Some(at)
        )

      case SolveRequested(priority, _) =>
        d.copy(
          spec = d.spec.map(s => s.copy(priority = priority.getOrElse(s.priority))),
          solveRequested = true,
          queued = d.lease.isEmpty,
          status = SolvingStatus.Scheduled
        )

      case Leased(workerId, epoch, at) =>
        d.copy(queued = false, lease = Some(Lease(workerId, epoch, at)), lastEpoch = epoch)

      case Validated(result, summary, _) =>
        d.copy(
          validation = Some(result),
          validationSummary = Some(summary),
          status = forward(d, SolvingStatus.DatasetValidated)
        )

      case Invalidated(result, summary, at) =>
        d.copy(
          validation = Some(result),
          validationSummary = Some(summary),
          status = SolvingStatus.DatasetInvalid,
          lease = None,
          queued = false,
          shutdown = Some(at)
        )

      case Computed(inputScore, inputMetrics, analysisRef, at) =>
        val stops = !d.solveRequested
        d.copy(
          computed = true,
          inputScore = inputScore,
          inputMetrics = inputMetrics,
          analysisRef = analysisRef,
          status = forward(d, SolvingStatus.DatasetComputed),
          lease = if stops then None else d.lease,
          shutdown = if stops then Some(at) else d.shutdown
        )

      case SolvingStarted(at) =>
        d.copy(status = forward(d, SolvingStatus.Started), started = d.started.orElse(Some(at)))

      case SolvingActive(at) =>
        d.copy(status = forward(d, SolvingStatus.Active), active = d.active.orElse(Some(at)))

      case SolutionRecorded(score, key, feasible, ref, kpis, evicted, at) =>
        val retained = (d.retained :+ ref).filterNot(evicted.contains)
        d.copy(
          best = Some(Best(d.seq + 1, score, key, feasible, ref, kpis, at)),
          firstFeasibleRef = d.firstFeasibleRef.orElse(Option.when(feasible)(ref)),
          retained = retained,
          status = forward(d, SolvingStatus.Active),
          active = d.active.orElse(Some(at))
        )

      case TerminationRequested(by, force, at) =>
        d.copy(terminate = Some(TerminateRequest(by, force, at)))

      case Superseded(childId, _) =>
        d.copy(supersededBy = Some(childId))

      case LeaseLost(_, reason, _) =>
        d.copy(
          lease = None,
          queued = true,
          attempt = if reason == LeaseLossReason.Expired then d.attempt + 1 else d.attempt,
          warmStartRef = d.best.map(_.solutionRef).orElse(d.warmStartRef)
        )

      case Finished(_, analysisRef, at) =>
        d.copy(
          status = if d.best.isDefined then SolvingStatus.Completed else SolvingStatus.Incomplete,
          lease = None,
          queued = false,
          analysisRef = analysisRef.orElse(d.analysisRef),
          completed = d.completed.orElse(Some(at)),
          shutdown = Some(at)
        )

      case Failed(message, at) =>
        d.copy(
          status = SolvingStatus.Failed,
          failureMessage = Some(message),
          lease = None,
          queued = false,
          shutdown = Some(at)
        )

      case MetadataUpdated(name, tags) =>
        d.copy(name = name.orElse(d.name), tags = tags.getOrElse(d.tags))

      case LogsAppended(lines) =>
        d.copy(logs = (d.logs ++ lines).takeRight(LogLimit))

      case Purged(at)   => d.copy(purged = true, purgedAt = Some(at))
      case Restored(_)  => d.copy(purged = false, purgedAt = None)
      case Expired(_)   => d.copy(expired = true, purged = true, retained = Vector.empty)

  /**
   * Every change a client can see gets the next `seq` and a place in the ring. Derived in the fold,
   * so a replay assigns the same numbers the live run did. A recorded solution always takes one, so
   * its `Best.seq` (set to `seq + 1` when it is recorded) is the number the stream shows it under.
   */
  private def refreshRing(d: Dataset, force: Boolean): Dataset =
    val changed = force || d.ring.lastOption.forall(_.metadata.copy(seq = 0) != d.observable)
    if !d.exists || !changed then d
    else
      val updated = d.copy(seq = d.seq + 1)
      updated.copy(ring = (d.ring :+ RingEntry(updated.seq, updated.metadata)).takeRight(RingSize))

  /**
   * Which solution bodies to let go when `ref` is recorded: everything but the first feasible and the
   * last ten (the newest, which is also the best, is always among them).
   */
  def evictions(d: Dataset, ref: String, feasible: Boolean): List[String] =
    val all       = d.retained :+ ref
    val first     = d.firstFeasibleRef.orElse(Option.when(feasible)(ref))
    val keep      = all.takeRight(RetainedIntermediate).toSet ++ first
    all.filterNot(keep.contains).toList
