package satisfactory.client

import com.github.plokhotnyuk.jsoniter_scala.core.writeToArray
import satisfactory.protocol.*
import satisfactory.protocol.WireCodecs.given

import scala.concurrent.duration.*

/** One model's API: `/api/models/<key>/<version>`, its entity name read from the catalog once. */
final class ModelClient private[client] (client: SatisfactoryClient, key: String, version: String):
  private val base = s"/api/models/$key/$version"

  lazy val descriptor: ModelDescriptor = describe()
  private def entity: String           = s"$base/${descriptor.entity}"

  def describe(): ModelDescriptor                 = client.call[ModelDescriptor]("GET", s"$base/model")
  def demoData(): List[DemoDataSummary]           = client.call[List[DemoDataSummary]]("GET", s"$base/demo-data")
  def demoRequest(id: String): SubmitRequest      = client.call[SubmitRequest]("GET", s"$base/demo-data/${Query.enc(id)}")
  def demoInput(id: String): RawJson              = RawJson(client.exchange(client.request("GET", s"$base/demo-data/${Query.enc(id)}/input", None)).body)

  private def options(o: SubmitOptions): String =
    Query.many(
      Query(
        "name"            -> o.name,
        "operation"       -> o.operation,
        "configurationId" -> o.configurationId,
        "priority"        -> o.priority.map(_.toString)
      ),
      "tags",
      o.tags
    )

  private def headers(o: SubmitOptions): Seq[(String, String)] =
    o.idempotencyKey.map("Idempotency-Key" -> _).toSeq ++ Option.when(o.gzip)("Content-Encoding" -> "gzip")

  private def body(bytes: Array[Byte], o: SubmitOptions): Array[Byte] = if o.gzip then Gzip(bytes) else bytes

  def submit(request: SubmitRequest, opts: SubmitOptions = SubmitOptions()): Metadata =
    client.call[Metadata]("POST", s"$entity${options(opts)}", Some(body(writeToArray(request), opts)), headers(opts))

  def solve(id: String, priority: Option[Int] = None): Metadata =
    client.call[Metadata]("POST", s"$entity/$id${Query("priority" -> priority.map(_.toString))}")

  def get(id: String): DatasetResponse = client.call[DatasetResponse]("GET", s"$entity/$id")
  def metadata(id: String): Metadata   = client.call[Metadata]("GET", s"$entity/$id/metadata")

  def list(filter: ListFilter = ListFilter()): Page[Metadata] =
    val q = Query.many(Query.many(Query("page" -> Some(filter.page.toString), "size" -> Some(filter.size.toString)), "status", filter.statuses), "tag", filter.tags)
    client.call[Page[Metadata]]("GET", s"$entity$q")

  def updateMetadata(id: String, name: Option[String] = None, tags: Option[List[String]] = None): Metadata =
    client.call[Metadata]("PATCH", s"$entity/$id/metadata", Some(writeToArray(MetadataPatch(name, tags))))

  def input(id: String): RawJson                   = RawJson(client.exchange(client.request("GET", s"$entity/$id/input", None)).body)
  def modelRequest(id: String): SubmitRequest      = client.call[SubmitRequest]("GET", s"$entity/$id/model-request")
  def config(id: String): ModelConfiguration       = client.call[ModelConfiguration]("GET", s"$entity/$id/config")
  def validationResult(id: String): ValidationResult = client.call[ValidationResult]("GET", s"$entity/$id/validation-result")
  def logs(id: String): String                     = client.call[Logs]("GET", s"$entity/$id/logs").details

  def scoreAnalysis(id: String, includeJustifications: Boolean = false): ScoreAnalysis =
    client.call[ScoreAnalysis]("GET", s"$entity/$id/score-analysis${if includeJustifications then "?includeJustifications=true" else ""}")

  /** Scores a plan the caller already has; creates nothing. */
  def analyze(request: SubmitRequest, configurationId: Option[String] = None): ScoreAnalysis =
    client.call[ScoreAnalysis]("POST", s"$entity/score-analysis${Query("configurationId" -> configurationId)}", Some(writeToArray(request)))

  def terminate(id: String, force: Boolean = false): DatasetResponse =
    client.call[DatasetResponse]("DELETE", s"$entity/$id${if force then "?force=true" else ""}")

  def purge(id: String): Unit   = client.exchange(client.request("DELETE", s"$entity/$id/purge", None)): Unit
  def restore(id: String): Unit = client.exchange(client.request("PUT", s"$entity/$id", None)): Unit

  def fromInput(id: String, select: String = Select.Unsolved, config: Option[ModelConfiguration] = None, opts: SubmitOptions = SubmitOptions()): Metadata =
    val q = Query.many(options(opts), "select", List(select))
    client.call[Metadata]("POST", s"$entity/$id/from-input$q", Some(writeToArray(FromInputRequest(config))), headers(opts.copy(gzip = false)))

  def fromPatch(id: String, patch: List[PatchOp], select: String = Select.Solved, config: Option[ModelConfiguration] = None, opts: SubmitOptions = SubmitOptions()): Metadata =
    val q = Query.many(options(opts), "select", List(select))
    client.call[Metadata]("POST", s"$entity/$id/from-patch$q", Some(writeToArray(PatchRequest(patch, config))), headers(opts.copy(gzip = false)))

  /**
   * Polls `/metadata` until a final status, starting at `poll` and backing off to 30 s, as Timefold
   * advises for longer solves. `DATASET_COMPUTED` counts as final: a caller who asked for
   * `operation=NONE` is waiting for exactly that.
   */
  def awaitFinal(id: String, poll: FiniteDuration = 2.seconds, max: FiniteDuration = 1.hour): Metadata =
    val deadline = System.nanoTime() + max.toNanos
    var interval = poll
    var current  = metadata(id)
    while !Streams.isFinal(current) && System.nanoTime() < deadline do
      Thread.sleep(interval.toMillis)
      interval = (interval * 3 / 2).min(30.seconds)
      current = metadata(id)
    current

  /**
   * The dataset's metadata as it changes, until a final status (API.md §2.4): an iterator that
   * blocks for the next frame, and reconnects from the last `seq` it saw if the connection drops.
   * Close it to stop early.
   */
  def events(id: String, after: Option[Long] = None, statuses: Set[String] = Set.empty, followLineage: Boolean = false): Streams.Frames =
    Streams.Frames(client, s"$entity/$id/events", after, statuses, followLineage)

  /** The same stream as a `java.util.concurrent.Flow.Publisher`, for reactive consumers. */
  def eventsPublisher(id: String, after: Option[Long] = None, statuses: Set[String] = Set.empty, followLineage: Boolean = false): java.util.concurrent.Flow.Publisher[StreamFrame] =
    Streams.publisher(events(id, after, statuses, followLineage))
