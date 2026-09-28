package satisfactory.ankka

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray, writeToArray}
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import satisfactory.client.SatisfactoryClient
import satisfactory.protocol.*
import satisfactory.spi.{Json, SolverModel}

import java.net.InetSocketAddress
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Instant
import java.util.concurrent.{ConcurrentLinkedQueue, CopyOnWriteArrayList}
import scala.jdk.CollectionConverters.*

/** A request the fake received. */
final case class FakeRequest(method: String, path: String, query: String, headers: Map[String, String], body: Array[Byte]):
  def as[A](using codec: JsonValueCodec[A]): A = readFromArray[A](body)

final case class FakeResponse(status: Int, body: Array[Byte])

object FakeResponse:
  def json[A](value: A, status: Int = 200)(using codec: JsonValueCodec[A]): FakeResponse = FakeResponse(status, writeToArray(value))

/**
 * satisfactory in memory, for an ankka application's tests (DESIGN.md §12): it answers the client
 * from a script and fires signed webhooks, so `sbt test` needs nothing running. The catalog (the
 * model's descriptor) is always answered; everything else must be scripted, in order. A call nobody
 * scripted is answered `500` with what was missing and recorded, so a test that ran out of script
 * fails loudly rather than quietly getting defaults — as ankka's `TestModelProvider` does.
 */
final class FakeSatisfactory(descriptors: ModelDescriptor*) extends AutoCloseable:

  private final case class Expectation(method: String, path: String => Boolean, respond: FakeRequest => FakeResponse, describe: String)

  private val script     = ConcurrentLinkedQueue[Expectation]()
  val received           = CopyOnWriteArrayList[FakeRequest]()
  val unexpected         = CopyOnWriteArrayList[String]()
  private val http       = HttpClient.newHttpClient()

  private val server =
    val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    s.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
    s.createContext("/", exchange => answer(exchange))
    s.start()
    s

  def url: String = s"http://127.0.0.1:${server.getAddress.getPort}"

  def client(apiKey: String = "fake-key"): SatisfactoryClient = SatisfactoryClient(url, apiKey)

  /** Scripts the next matching call. */
  def expect(method: String, pathEndsWith: String)(respond: FakeRequest => FakeResponse): this.type =
    script.add(Expectation(method, _.endsWith(pathEndsWith), respond, s"$method …$pathEndsWith")): Unit
    this

  /** The next submission: answered with what `created` makes of it, `202`. */
  def expectSubmit(created: (FakeRequest, SubmitRequest) => Metadata): this.type =
    script.add(
      Expectation(
        "POST",
        p => descriptors.exists(d => p.endsWith(s"/${d.entity}")),
        r => FakeResponse.json(created(r, r.as[SubmitRequest](using WireCodecs.submitRequest)), 202)(using WireCodecs.metadata),
        "a submission"
      )
    ): Unit
    this

  def expectGet(datasetId: String, response: DatasetResponse): this.type =
    expect("GET", s"/$datasetId")(_ => FakeResponse.json(response)(using WireCodecs.datasetResponse))

  def remaining: Int = script.size

  /** Fails the test if anything scripted was not called, or anything was called that was not scripted. */
  def verifyScript(): Unit =
    val left = script.asScala.map(_.describe).toList
    if left.nonEmpty || !unexpected.isEmpty then
      throw AssertionError(s"FakeSatisfactory: unused script $left; unscripted calls ${unexpected.asScala.toList}")

  /** Posts a signed webhook to the application under test; answers its status. */
  def fire(event: DatasetEvent, to: String, secret: String, signOver: String = Signing.OverBody): Int =
    val body      = writeToArray(event)(using WebhookCodecs.datasetEvent)
    val timestamp = Instant.now().toString
    val payload   = if signOver == Signing.OverPath then URI.create(to).getRawPath.getBytes("UTF-8") else body
    val request = HttpRequest.newBuilder(URI.create(to))
      .header("Content-Type", "application/json")
      .header(Signing.SignatureHeader, Signing.sign(secret, timestamp, payload))
      .header(Signing.TimestampHeader, timestamp)
      .header(Signing.EventHeader, event.`type`)
      .POST(HttpRequest.BodyPublishers.ofByteArray(body))
      .build()
    http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode

  def close(): Unit = server.stop(0)

  private def answer(exchange: HttpExchange): Unit =
    val request = FakeRequest(
      exchange.getRequestMethod,
      exchange.getRequestURI.getPath,
      Option(exchange.getRequestURI.getRawQuery).getOrElse(""),
      exchange.getRequestHeaders.asScala.map((k, v) => k.toLowerCase -> v.asScala.headOption.getOrElse("")).toMap,
      exchange.getRequestBody.readAllBytes()
    )
    received.add(request): Unit
    val catalog = descriptors.find(d => request.method == "GET" && request.path == s"/api/models/${d.id}/${d.version}/model")
    val response = catalog match
      case Some(d) => FakeResponse.json(d)(using WireCodecs.modelDescriptor)
      case None =>
        Option(script.peek()).filter(e => e.method == request.method && e.path(request.path)) match
          case Some(e) =>
            script.poll(): Unit
            e.respond(request)
          case None =>
            val what = s"${request.method} ${request.path}"
            unexpected.add(what): Unit
            val next = Option(script.peek()).fold("the script is empty")(e => s"the script expected ${e.describe}")
            FakeResponse.json(ErrorInfo("fake", "unscripted", s"FakeSatisfactory has no scripted response for $what; $next"), 500)(using WireCodecs.errorInfo)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(response.status, if response.body.isEmpty then -1 else response.body.length.toLong)
    if response.body.nonEmpty then exchange.getResponseBody.write(response.body)
    exchange.close()

object FakeSatisfactory:
  /** A model's descriptor, as the service's catalog would serve it. */
  def describe(model: SolverModel[?, ?]): ModelDescriptor =
    ModelDescriptor(
      id = model.key().registrationKey(),
      version = model.key().version(),
      entity = model.key().entity(),
      name = model.info().name(),
      description = model.info().description(),
      maturity = model.maturity().name(),
      features = model.info().features().asScala.toList,
      schemas = ModelSchemas(RawJson(Json.bytes(model.inputSchema().node())), RawJson(Json.bytes(model.outputSchema().node())), RawJson(Json.bytes(model.overridesSchema().node()))),
      patchPaths = Nil,
      constraints = model.constraints().asScala.toList.map(c => ConstraintDescriptor(c.name(), c.key(), c.description(), c.level().name(), c.defaultWeight())),
      kpis = Nil,
      inputMetrics = Nil,
      issueTypes = Nil
    )
