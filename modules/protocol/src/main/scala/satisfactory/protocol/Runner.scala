package satisfactory.protocol

/**
 * The configuration a dataset actually runs with: model defaults, then the parent's, then the
 * profile's, then the request's own, then clamps (data-model.md "Configuration resolution").
 * Resolved once at creation and never recomputed.
 */
final case class ResolvedConfig(
    termination: TerminationConfig,
    weights: Map[String, Long],
    maxThreadCount: Int,
    configurationId: Option[String]
)

/** Everything immutable about a dataset. Blob bodies are referenced, never carried. */
final case class DatasetSpec(
    tenantId: String,
    modelId: String,
    modelVersion: String,
    entity: String,
    operation: String,
    priority: Int,
    inputRef: String,
    patchRef: Option[String],
    parentId: Option[String],
    originId: Option[String],
    select: Option[String],
    config: ResolvedConfig,
    submittedConfig: Option[ModelConfiguration]
):
  def modelKey: String = ModelKeys.of(modelId, modelVersion)

object ModelKeys:
  def of(id: String, version: String): String = s"$id/$version"

/**
 * How a worker starts a claimed dataset:
 *   - `full`: validate, score the input, and (for SOLVE) solve from it;
 *   - `solve`: validation and scoring already happened (`operation=NONE`, then `POST /{id}`);
 *   - `warm`: a re-queued or derived dataset; solve from `warmStartRef`, the solution to start from.
 */
object ClaimMode:
  val Full  = "full"
  val Solve = "solve"
  val Warm  = "warm"

final case class WorkerRegistration(models: List[String], slots: Int)
final case class WorkerAck(drain: Boolean)

final case class ClaimRequest(workerId: String, models: List[String], datasetId: Option[String] = None)

final case class ClaimResponse(
    datasetId: String,
    epoch: Long,
    spec: DatasetSpec,
    mode: String,
    warmStartRef: Option[String],
    leaseTtlMillis: Long,
    drain: Boolean
)

object Phase:
  val Validated = "validated"
  val Invalid   = "invalid"
  val Computed  = "computed"
  val Started   = "started"
  val Active    = "active"

final case class PhaseRequest(
    epoch: Long,
    phase: String,
    validation: Option[ValidationResult] = None,
    inputScore: Option[String] = None,
    inputMetrics: Option[RawJson] = None,
    analysisRef: Option[String] = None
)

final case class ReportRequest(
    epoch: Long,
    score: String,
    scoreKey: List[BigDecimal],
    feasible: Boolean,
    solutionRef: String,
    kpis: Option[RawJson]
)

final case class ReportResponse(recorded: Boolean, seq: Long, evictedRefs: List[String])

final case class HeartbeatRequest(
    epoch: Long,
    workerId: String = "",
    scoreCalculationSpeed: Long = 0,
    moveCount: Long = 0,
    logLines: List[String] = Nil
)

final case class HeartbeatResponse(terminate: Boolean, force: Boolean, leaseTtlMillis: Long, drain: Boolean)

object CompleteReason:
  val Termination = "termination"
  val Terminated  = "terminated"
  val Superseded  = "superseded"
  val Lifetime    = "lifetime"

final case class CompleteRequest(epoch: Long, reason: String, analysisRef: Option[String] = None)

final case class FailRequest(epoch: Long, message: String)

object ReleaseReason:
  val Drained  = "drained"
  val Shutdown = "shutdown"

final case class ReleaseRequest(epoch: Long, reason: String)
