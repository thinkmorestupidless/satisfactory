package satisfactory.protocol

import java.time.Instant

// ── Submission (API.md §2.1) ────────────────────────────────────────────────────────────────

/** `SolverTerminationConfig`: flat, every field optional. Durations are ISO-8601 strings. */
final case class TerminationConfig(
    spentLimit: Option[String] = None,
    unimprovedSpentLimit: Option[String] = None,
    slidingWindowDuration: Option[String] = None,
    minimumImprovementRatio: Option[Double] = None,
    stepCountLimit: Option[Int] = None,
    moveCountLimit: Option[Long] = None
)

final case class RunConfiguration(
    name: Option[String] = None,
    tags: Option[List[String]] = None,
    maxThreadCount: Option[Int] = None,
    termination: Option[TerminationConfig] = None
)

/** `ModelConfig<Model>ConfigOverrides`: one `<constraint>Weight` per constraint, integer ≥ 0. */
final case class ModelOverrides(overrides: Option[Map[String, Long]] = None)

final case class ModelConfiguration(
    run: Option[RunConfiguration] = None,
    model: Option[ModelOverrides] = None,
    resourcesConfiguration: Option[RawJson] = None,
    mapsConfiguration: Option[RawJson] = None
)

final case class SubmitRequest(modelInput: RawJson, config: Option[ModelConfiguration] = None)

/**
 * One patch operation. `value` is raw JSON so an explicit `null` survives; it is absent (and not
 * written) only when the operation has none, which is what `remove` takes.
 */
final case class PatchOp(op: String, path: String, value: RawJson = PatchOp.Absent):
  def hasValue: Boolean = value.bytes.nonEmpty

object PatchOp:
  val Absent: RawJson = RawJson(Array.emptyByteArray)

final case class PatchRequest(patch: List[PatchOp], config: Option[ModelConfiguration] = None)

final case class FromInputRequest(config: Option[ModelConfiguration] = None)

// ── Results (API.md §2.3) ───────────────────────────────────────────────────────────────────

/** The legacy summary Timefold keeps on `Metadata`; the detail is at `/validation-result`. */
final case class ValidationSummary(
    summary: String,
    errors: List[String] = Nil,
    warnings: List[String] = Nil
)

/**
 * The thing to poll, every stream frame, and the webhook payload's core. Timefold's fields plus
 * `supersededBy` (API.md §2.6) and `seq`, so a poller can dedupe against streams and webhooks.
 */
final case class Metadata(
    id: String,
    parentId: Option[String],
    originId: Option[String],
    name: Option[String],
    tags: List[String],
    solverStatus: String,
    score: Option[String],
    submitDateTime: Option[Instant],
    startDateTime: Option[Instant],
    activeDateTime: Option[Instant],
    completeDateTime: Option[Instant],
    shutdownDateTime: Option[Instant],
    validationResult: Option[ValidationSummary],
    failureMessage: Option[String],
    supersededBy: Option[String],
    queuePaused: Option[Boolean],
    seq: Long
)

final case class DatasetResponse(
    metadata: Metadata,
    modelOutput: Option[RawJson],
    inputMetrics: Option[RawJson],
    kpis: Option[RawJson]
)

/** `{ status, issues[] }`; each issue is `{ code, severity, …its own fields }`. */
final case class ValidationResult(status: String, issues: RawJson)

object ValidationStatus:
  val NotSupported = "VALIDATION_NOT_SUPPORTED"
  val Ok           = "OK"
  val Warnings     = "WARNINGS"
  val Errors       = "ERRORS"

/**
 * Score analysis in the Community shape (research R2): per-constraint weight and contribution,
 * no match counts, justifications answered as unsupported.
 */
final case class ConstraintAnalysis(name: String, weight: String, score: String)

final case class ScoreAnalysis(
    score: String,
    constraints: List[ConstraintAnalysis],
    justifications: Option[String] = None
)

final case class Logs(details: String)

/** `PATCH /{id}/metadata`: rename and re-tag after submission. */
final case class MetadataPatch(name: Option[String] = None, tags: Option[List[String]] = None)

final case class Page[A](content: List[A], page: Int, size: Int, hasNext: Boolean)

/** Timefold's error body: `ErrorInfo { id, code, message, details }`. */
final case class ErrorInfo(id: String, code: String, message: String, details: List[String] = Nil)

// ── The catalog ─────────────────────────────────────────────────────────────────────────────

final case class KeyValue(key: String, value: String)

final case class DemoDataSummary(
    id: String,
    shortDescription: String,
    longDescription: String,
    tags: List[String],
    config: List[KeyValue]
)

final case class ConstraintDescriptor(
    name: String,
    key: String,
    description: String,
    level: String,
    defaultWeight: Long
)

final case class MetricDescriptor(
    id: String,
    title: String,
    description: String,
    `type`: String,
    priority: Int,
    example: String
)

final case class IssueTypeDescriptor(code: String, severity: String, message: String)

final case class ModelSchemas(input: RawJson, output: RawJson, overrides: RawJson)

/** `GET /v1/model`: everything a tool generator or a client generator needs. */
final case class ModelDescriptor(
    id: String,
    version: String,
    entity: String,
    name: String,
    description: String,
    maturity: String,
    features: List[String],
    schemas: ModelSchemas,
    patchPaths: List[String],
    constraints: List[ConstraintDescriptor],
    kpis: List[MetricDescriptor],
    inputMetrics: List[MetricDescriptor],
    issueTypes: List[IssueTypeDescriptor]
)

final case class WhoAmI(
    tenantId: String,
    tenantName: String,
    keyId: String,
    role: String,
    queuePaused: Boolean
)
