package satisfactory.api

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import com.typesafe.config.ConfigFactory
import satisfactory.api.application.{Limits, Roles}
import satisfactory.protocol.SolvingStatus
import satisfactory.spi.{Json, ModelCatalog}

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.GZIPOutputStream
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Every log line the suite's JVM writes, for the scan that proves no dataset content leaks (SC-018). */
final class CapturingAppender extends AppenderBase[ILoggingEvent]:
  override def append(event: ILoggingEvent): Unit = CapturingAppender.lines.add(event.getFormattedMessage): Unit

object CapturingAppender:
  val lines = ConcurrentLinkedQueue[String]()

final case class Reply(status: Int, body: String, headers: Map[String, List[String]]):
  def json: JsonNode = Json.parse(body)
  override def toString: String = s"$status $body"

/**
 * The api service in this JVM over a throwaway Postgres (Docker), with the runner in-process and two
 * tenants: `t_a` (a read-write and a read-only key) and `t_b` (a read-write key). Quickstart tier 3.
 */
trait ApiFixture extends munit.FunSuite:

  override val munitTimeout = 10.minutes

  protected val keyA         = "sk_test_tenant_a_read_write"
  protected val keyAReadOnly = "sk_test_tenant_a_read_only"
  protected val keyB         = "sk_test_tenant_b_read_write"

  /** Configuration a suite changes: `satisfactory.*` paths to values. */
  protected def configOverrides: Map[String, Any] = Map.empty

  protected def catalog: ModelCatalog = ApiService.catalog

  protected def tenantALimits: Option[Limits] = None

  protected var testKit: AnkkaTestKit      = null
  protected var baseUrl: String            = ""
  protected var wiring: ApiService.Wiring  = null
  protected val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  override def beforeAll(): Unit =
    val config = ConfigFactory
      .parseMap(configOverrides.map((k, v) => k -> v.asInstanceOf[AnyRef]).asJava)
      .withFallback(ConfigFactory.load())
      .resolve()
    val settings = Settings.from(config)
    wiring = ApiService.Wiring(settings, catalog, factories => HttpServer.at("127.0.0.1", 0)(factories*))
    testKit = AnkkaTestKit.start(ApiService.components, wiring.extensions, readyTimeout = 120.seconds)
    baseUrl = s"http://127.0.0.1:${wiring.http.boundPort.getOrElse(fail("the server did not bind"))}"
    val client = testKit.componentClient
    Bootstrap.seed(client, settings, "t_a", "acme", "k_a_rw", keyA, Roles.ReadWrite, tenantALimits)
    Bootstrap.seed(client, settings, "t_a", "acme", "k_a_ro", keyAReadOnly, Roles.ReadOnly, tenantALimits)
    Bootstrap.seed(client, settings, "t_b", "globex", "k_b_rw", keyB)

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  // ── HTTP ─────────────────────────────────────────────────────────────────────────────────

  protected def send(
      method: String,
      path: String,
      body: Option[Array[Byte]] = None,
      key: Option[String] = Some(keyA),
      headers: Seq[(String, String)] = Nil
  ): Reply =
    val builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(60))
    key.foreach(k => builder.header("X-API-KEY", k))
    headers.foreach((k, v) => builder.header(k, v))
    body match
      case Some(bytes) =>
        builder.header("Content-Type", "application/json")
        builder.method(method, HttpRequest.BodyPublishers.ofByteArray(bytes)): Unit
      case None => builder.method(method, HttpRequest.BodyPublishers.noBody()): Unit
    val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    Reply(response.statusCode, response.body, response.headers.map.asScala.view.mapValues(_.asScala.toList).toMap)

  protected def get(path: String, key: Option[String] = Some(keyA)): Reply = send("GET", path, None, key)

  protected def post(path: String, json: String, key: Option[String] = Some(keyA), headers: Seq[(String, String)] = Nil): Reply =
    send("POST", path, Some(json.getBytes("UTF-8")), key, headers)

  protected def gzip(bytes: Array[Byte]): Array[Byte] =
    val out = ByteArrayOutputStream()
    val gz  = GZIPOutputStream(out)
    gz.write(bytes)
    gz.close()
    out.toByteArray

  // ── The employee scheduling model ───────────────────────────────────────────────────────

  protected val es = "/api/models/employee-scheduling/v1"

  protected lazy val smallInput: String = get(s"$es/demo-data/SMALL/input").body

  protected def submitBody(input: String = smallInput, spentLimit: String = "PT3S", extra: String = ""): String =
    s"""{"modelInput":$input,"config":{"run":{"termination":{"spentLimit":"$spentLimit"}}$extra}}"""

  protected def submit(
      spentLimit: String = "PT3S",
      query: String = "",
      key: Option[String] = Some(keyA),
      input: String = smallInput
  ): JsonNode =
    val reply = post(s"$es/schedules$query", submitBody(input, spentLimit), key)
    assertEquals(reply.status, 202, reply.toString)
    reply.json

  protected def metadata(id: String, key: Option[String] = Some(keyA)): JsonNode =
    val reply = get(s"$es/schedules/$id/metadata", key)
    assertEquals(reply.status, 200, reply.toString)
    reply.json

  protected def status(id: String): String = metadata(id).get("solverStatus").asText()

  protected def awaitStatus(id: String, wanted: Set[String], timeout: FiniteDuration = 90.seconds, key: Option[String] = Some(keyA)): JsonNode =
    val deadline = System.nanoTime() + timeout.toNanos
    var last     = metadata(id, key)
    while !wanted.contains(last.get("solverStatus").asText()) && System.nanoTime() < deadline do
      Thread.sleep(200)
      last = metadata(id, key)
    assert(wanted.contains(last.get("solverStatus").asText()), s"$id is ${last.get("solverStatus")} after $timeout: $last")
    last

  protected def awaitFinal(id: String, timeout: FiniteDuration = 90.seconds, key: Option[String] = Some(keyA)): JsonNode =
    awaitStatus(id, SolvingStatus.terminal + SolvingStatus.DatasetComputed, timeout, key)

  protected def eventually[A](timeout: FiniteDuration = 30.seconds)(probe: => A)(condition: A => Boolean): A =
    val deadline = System.nanoTime() + timeout.toNanos
    var value    = probe
    while !condition(value) && System.nanoTime() < deadline do
      Thread.sleep(200)
      value = probe
    assert(condition(value), s"not within $timeout: $value")
    value
