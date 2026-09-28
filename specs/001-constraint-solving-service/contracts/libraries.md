# Contract: `satisfactory-client` and `ankka-satisfactory`

Both published from this repository under `com.thinkmorestupidless`, Scala 3, versioned with the API.

## `satisfactory-client` (no ankka dependency; `protocol` + the JDK `HttpClient`)

> **As built:** bodies are `RawJson` (no Jackson in the client); `events` is a blocking,
> reconnecting `Iterator[StreamFrame]` (`Streams.Frames`, `AutoCloseable`) that simply ends on a
> final dataset, and `eventsPublisher` wraps it as a `Flow.Publisher`. `forModel` takes the tags to
> put on submissions explicitly (`SatisfactoryTools.workflowTag(id)`), built per agent call.

```scala
final class SatisfactoryClient(baseUrl: String, apiKey: String, http: HttpClient = HttpClient.newHttpClient()):
  def model(key: String, version: String): ModelClient

final class ModelClient:
  def describe(): ModelDescriptor
  def demoData(): List[DemoDataSummary]; def demoInput(id: String): JsonNode
  def submit(request: SubmitRequest, opts: SubmitOptions = SubmitOptions()): Metadata     // opts: name, operation, configurationId, priority, tags, idempotencyKey, gzip
  def solve(id: String, priority: Option[Int] = None): Metadata
  def get(id: String): DatasetResponse; def metadata(id: String): Metadata
  def list(filter: ListFilter = ListFilter()): Page[Metadata]
  def updateMetadata(id: String, name: Option[String], tags: Option[List[String]]): Metadata
  def input(id: String): JsonNode; def modelRequest(id: String): SubmitRequest; def config(id: String): ModelConfiguration
  def validationResult(id: String): ValidationResult; def scoreAnalysis(id: String): ScoreAnalysis; def logs(id: String): String
  def analyze(request: SubmitRequest): ScoreAnalysis                                        // stateless
  def events(id: String, after: Option[Long] = None, statuses: Set[SolvingStatus] = Set.empty, followLineage: Boolean = false): Flow.Publisher[StreamFrame]
  def terminate(id: String, force: Boolean = false): DatasetResponse
  def purge(id: String): Unit; def restore(id: String): Unit
  def fromInput(id: String, select: Select, config: Option[ModelConfiguration], opts: SubmitOptions): Metadata
  def fromPatch(id: String, patch: List[PatchOp], select: Select, config: Option[ModelConfiguration], opts: SubmitOptions): Metadata
  def awaitFinal(id: String, poll: Duration = 2.seconds, max: Duration = 1.hour): Metadata    // polling helper with backoff

enum StreamFrame: case Update(seq: Long, metadata: Metadata, skipped: Int); case Heartbeat
class SatisfactoryError(status: Int, info: ErrorInfo) extends RuntimeException
```

Errors map `401/403/404/409/410/429` to `SatisfactoryError`. `events` reconnects automatically with `after = lastSeq` on transport failure.

## `ankka-satisfactory` (depends on `ankka-sdk`, `ankka-http`, `ankka-agent`, `satisfactory-client`)

```scala
object SatisfactoryTools:
  def forModel(client: SatisfactoryClient, key: String, version: String, tagging: ToolTagging = ToolTagging.fromContext): List[FunctionTool]
  // solve_<key>(dataset: String, name: Option[String], spentLimit: Option[String]) → { datasetId } | { validationErrors }
  // get_best_solution_<key>(datasetId: String) → DatasetResponse as JSON
  // terminate_<key>(datasetId: String) → Metadata as JSON

object SatisfactoryWebhook:
  def looksSigned(maxSkew: FiniteDuration = 5.minutes): RequestContext => Boolean            // for Acl.AllowIf
  def verify(secret: String, request: RequestContext, body: Array[Byte]): Either[WebhookError, Event]
  given FromBody[Array[Byte]]                                                               // application/json, raw bytes

enum Event: case Dataset(event: DatasetEvent); case WebhookFailing(event: WebhookFailingEvent)

final class FakeSatisfactory(script: Script):                                              // in-memory
  def client: SatisfactoryClient                                                            // talks to an in-process HTTP server on a random port
  def fire(event: DatasetEvent, to: String): Unit                                           // POSTs a signed webhook to the app's endpoint
  def remaining: Int                                                                        // a script that runs out fails the test loudly
```

Tools tag every submit with `ankka-workflow:<id>` or `ankka-session:<id>` from the calling context so the webhook routes back to the workflow by tag. The library mounts no endpoint; the developer writes one with `postBody[Array[Byte], Done]` and `acl = Acl.AllowIf(SatisfactoryWebhook.looksSigned())`, verifies, and resumes the workflow.
