package satisfactory.solver

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray, writeToArray}
import satisfactory.protocol.*
import satisfactory.protocol.WireCodecs.given
import satisfactory.runner.{ChannelError, ControlChannel}

import java.net.URI
import java.net.URLEncoder
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import scala.util.control.NonFatal

/**
 * The runner protocol over HTTP, from the `solver` service to `api` (contracts/runner-protocol.md).
 * The worker only ever pulls: `api` never needs to address a worker. `409` is a stale lease; `5xx`
 * and connection failures are worth retrying; anything else is a refusal.
 */
final class HttpControlChannel(apiUrl: String, token: String, claimTimeout: Duration = Duration.ofSeconds(30))
    extends ControlChannel:

  private val base = apiUrl.stripSuffix("/") + "/internal"
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

  private def request(method: String, path: String, body: Option[Array[Byte]], timeout: Duration, contentType: String = "application/json") =
    val b = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout).header("X-Runner-Token", token)
    body match
      case Some(bytes) =>
        b.header("Content-Type", contentType)
        b.method(method, HttpRequest.BodyPublishers.ofByteArray(bytes)).build()
      case None => b.method(method, HttpRequest.BodyPublishers.noBody()).build()

  private def exchange(req: HttpRequest): Either[ChannelError, HttpResponse[Array[Byte]]] =
    try
      val response = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
      response.statusCode match
        case s if s >= 200 && s < 300 => Right(response)
        case 409                      => Left(ChannelError.Stale(String(response.body, UTF_8)))
        case s if s >= 500            => Left(ChannelError.Unavailable(s"$s ${String(response.body, UTF_8)}"))
        case s                        => Left(ChannelError.Rejected(s, String(response.body, UTF_8)))
    catch
      case e: InterruptedException => throw e
      case NonFatal(e)             => Left(ChannelError.Unavailable(s"${e.getClass.getSimpleName}: ${e.getMessage}"))

  private def call[I, O](method: String, path: String, body: I, timeout: Duration = Duration.ofSeconds(30))(using
      in: JsonValueCodec[I],
      out: JsonValueCodec[O]
  ): Either[ChannelError, O] =
    exchange(request(method, path, Some(writeToArray(body)), timeout)).map(r => readFromArray[O](r.body))

  private def send[I](method: String, path: String, body: I)(using in: JsonValueCodec[I]): Either[ChannelError, Unit] =
    exchange(request(method, path, Some(writeToArray(body)), Duration.ofSeconds(30))).map(_ => ())

  private def segment(value: String): String = URLEncoder.encode(value, UTF_8).replace("+", "%20")

  def register(workerId: String, registration: WorkerRegistration) =
    call[WorkerRegistration, WorkerAck]("POST", s"/workers/${segment(workerId)}", registration)

  def deregister(workerId: String) =
    exchange(request("DELETE", s"/workers/${segment(workerId)}", None, Duration.ofSeconds(10))).map(_ => ())

  /** A long poll: the API holds it until something is claimable or its wait runs out (`204`). */
  def claim(claimRequest: ClaimRequest) =
    exchange(request("POST", "/leases", Some(writeToArray(claimRequest)), claimTimeout)).map(r =>
      Option.when(r.statusCode == 200)(readFromArray[ClaimResponse](r.body))
    )

  def phase(datasetId: String, r: PhaseRequest)         = send("POST", s"/datasets/${segment(datasetId)}/phase", r)
  def report(datasetId: String, r: ReportRequest)       = call[ReportRequest, ReportResponse]("POST", s"/datasets/${segment(datasetId)}/solutions", r)
  def heartbeat(datasetId: String, r: HeartbeatRequest) = call[HeartbeatRequest, HeartbeatResponse]("POST", s"/datasets/${segment(datasetId)}/heartbeat", r)
  def complete(datasetId: String, r: CompleteRequest)   = send("POST", s"/datasets/${segment(datasetId)}/complete", r)
  def fail(datasetId: String, r: FailRequest)           = send("POST", s"/datasets/${segment(datasetId)}/fail", r)
  def release(datasetId: String, r: ReleaseRequest)     = send("POST", s"/datasets/${segment(datasetId)}/release", r)

  /** A ref is `<dataset>/<kind>/<part>`: the dataset, then the rest as one encoded segment. */
  private def blobPath(ref: String): String =
    val (dataset, rest) = ref.span(_ != '/')
    s"/blobs/${segment(dataset)}/${segment(rest.drop(1))}"

  def getBlob(ref: String) =
    exchange(request("GET", blobPath(ref), None, Duration.ofSeconds(60))).map(_.body)

  def putBlob(ref: String, contentType: String, bytes: Array[Byte]) =
    exchange(request("PUT", blobPath(ref), Some(bytes), Duration.ofSeconds(60), contentType)).map(_ => ())
