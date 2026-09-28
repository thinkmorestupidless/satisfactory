package satisfactory.client

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray}
import satisfactory.protocol.*
import satisfactory.protocol.WireCodecs.given

import java.io.ByteArrayOutputStream
import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.zip.GZIPOutputStream
import scala.concurrent.duration.*
import scala.util.Try

/** A refusal from the service, with Timefold's `ErrorInfo`. */
final class SatisfactoryError(val status: Int, val info: ErrorInfo)
    extends RuntimeException(s"$status ${info.code}: ${info.message}${if info.details.isEmpty then "" else info.details.mkString(" (", "; ", ")")}")

/** Query options for creating a dataset (submit, `from-input`, `from-patch`). */
final case class SubmitOptions(
    name: Option[String] = None,
    operation: Option[String] = None,
    configurationId: Option[String] = None,
    priority: Option[Int] = None,
    tags: List[String] = Nil,
    idempotencyKey: Option[String] = None,
    gzip: Boolean = false
)

final case class ListFilter(statuses: List[String] = Nil, tags: List[String] = Nil, page: Int = 0, size: Int = 50)

/**
 * satisfactory's API, over the JDK's HTTP client (contracts/libraries.md). No ankka, no Pekko: a
 * plain library any JVM application can use.
 *
 * {{{
 *   val client = SatisfactoryClient("https://api-satisfactory.example.com", sys.env("SAT_KEY"))
 *   val scheduling = client.model("employee-scheduling", "v1")
 *   val created = scheduling.submit(SubmitRequest(input))
 *   scheduling.events(created.id).foreach(println)
 * }}}
 */
final class SatisfactoryClient(
    baseUrl: String,
    apiKey: String,
    http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
    timeout: FiniteDuration = 60.seconds
):
  private val root = baseUrl.stripSuffix("/")

  def model(registrationKey: String, version: String): ModelClient = ModelClient(this, registrationKey, version)

  def aboutMe(): WhoAmI = call[WhoAmI]("GET", "/api/aboutme")

  // ── Plumbing, shared with ModelClient ────────────────────────────────────────────────────

  private[client] def request(method: String, path: String, body: Option[Array[Byte]], headers: Seq[(String, String)] = Nil): HttpRequest =
    val b = HttpRequest.newBuilder(URI.create(root + path)).timeout(Duration.ofMillis(timeout.toMillis)).header("X-API-KEY", apiKey)
    headers.foreach((k, v) => b.header(k, v): Unit)
    body match
      case Some(bytes) =>
        b.header("Content-Type", "application/json")
        b.method(method, HttpRequest.BodyPublishers.ofByteArray(bytes)).build()
      case None => b.method(method, HttpRequest.BodyPublishers.noBody()).build()

  private[client] def exchange(req: HttpRequest): HttpResponse[Array[Byte]] =
    val response = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
    if response.statusCode >= 400 then throw error(response.statusCode, response.body)
    response

  private[client] def error(status: Int, body: Array[Byte]): SatisfactoryError =
    val info = Try(readFromArray[ErrorInfo](body)).toOption.filter(_.code.nonEmpty).getOrElse {
      val text = String(body, UTF_8)
      ErrorInfo("", s"http-$status", Try(ankkaMessage(text)).getOrElse(text.take(300)), Nil)
    }
    SatisfactoryError(status, info)

  /** ankka answers its own refusals as `{"status":…,"error":"…"}`. */
  private def ankkaMessage(text: String): String =
    val marker = "\"error\":\""
    val start  = text.indexOf(marker) + marker.length
    text.substring(start, text.indexOf('"', start))

  private[client] def call[O](method: String, path: String, body: Option[Array[Byte]] = None, headers: Seq[(String, String)] = Nil)(using
      out: JsonValueCodec[O]
  ): O = readFromArray[O](exchange(request(method, path, body, headers)).body)

  private[client] def http_ : HttpClient = http
  private[client] def rootUrl: String    = root

private[client] object Query:
  def apply(params: (String, Option[String])*): String =
    val present = params.collect { case (k, Some(v)) => s"${enc(k)}=${enc(v)}" }
    if present.isEmpty then "" else present.mkString("?", "&", "")

  def many(base: String, name: String, values: Seq[String]): String =
    if values.isEmpty then base
    else base + (if base.contains("?") then "&" else "?") + values.map(v => s"${enc(name)}=${enc(v)}").mkString("&")

  def enc(s: String): String = URLEncoder.encode(s, UTF_8)

private[client] object Gzip:
  def apply(bytes: Array[Byte]): Array[Byte] =
    val out = ByteArrayOutputStream()
    val gz  = GZIPOutputStream(out)
    gz.write(bytes)
    gz.close()
    out.toByteArray
