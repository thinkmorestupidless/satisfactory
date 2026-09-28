package satisfactory.solver

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray, writeToArray}
import com.thinkmorestupidless.ankka.sdk.{ServiceClient, ServiceResponse}
import satisfactory.protocol.*
import satisfactory.protocol.WireCodecs.given
import satisfactory.runner.{ChannelError, ControlChannel}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.control.NonFatal

/**
 * The runner protocol over ankka's service client: `api` called as the `solver` service. In a
 * cluster that is mutual TLS with this workload's own certificate, which is the only way onto a
 * service's port there; on a laptop it is plain HTTP to wherever `api` announced itself. The status
 * mapping is `HttpControlChannel`'s: `409` is a stale lease, `5xx` and anything that never got an
 * answer are worth retrying, the rest is a refusal.
 */
final class ServiceControlChannel(api: ServiceClient, token: String) extends ControlChannel:

  private def exchange(
      method: String,
      path: String,
      body: Option[Array[Byte]] = None,
      contentType: String = "application/json"
  ): Either[ChannelError, ServiceResponse] =
    try
      val response = api.request(
        method,
        "/internal" + path,
        body,
        body.map(_ => contentType),
        Seq("X-Runner-Token" -> token)
      )
      response.status match
        case s if s >= 200 && s < 300 => Right(response)
        case 409                      => Left(ChannelError.Stale(response.text))
        case s if s >= 500            => Left(ChannelError.Unavailable(s"$s ${response.text}"))
        case s                        => Left(ChannelError.Rejected(s, response.text))
    catch
      case e: InterruptedException => throw e
      case NonFatal(e) =>
        Left(ChannelError.Unavailable(s"${e.getClass.getSimpleName}: ${e.getMessage}"))

  private def call[I, O](method: String, path: String, body: I)(using
      JsonValueCodec[I],
      JsonValueCodec[O]
  ): Either[ChannelError, O] =
    exchange(method, path, Some(writeToArray(body))).map(r => readFromArray[O](r.body))

  private def send[I](method: String, path: String, body: I)(using
      JsonValueCodec[I]
  ): Either[ChannelError, Unit] =
    exchange(method, path, Some(writeToArray(body))).map(_ => ())

  private def segment(value: String): String = URLEncoder.encode(value, UTF_8).replace("+", "%20")

  def register(workerId: String, registration: WorkerRegistration) =
    call[WorkerRegistration, WorkerAck]("POST", s"/workers/${segment(workerId)}", registration)

  def deregister(workerId: String) =
    exchange("DELETE", s"/workers/${segment(workerId)}").map(_ => ())

  /** A long poll: the API holds it until something is claimable or its wait runs out (`204`). */
  def claim(claimRequest: ClaimRequest) =
    exchange("POST", "/leases", Some(writeToArray(claimRequest))).map(r =>
      Option.when(r.status == 200)(readFromArray[ClaimResponse](r.body))
    )

  def phase(datasetId: String, r: PhaseRequest) =
    send("POST", s"/datasets/${segment(datasetId)}/phase", r)
  def report(datasetId: String, r: ReportRequest) =
    call[ReportRequest, ReportResponse]("POST", s"/datasets/${segment(datasetId)}/solutions", r)
  def heartbeat(datasetId: String, r: HeartbeatRequest) = call[HeartbeatRequest, HeartbeatResponse](
    "POST",
    s"/datasets/${segment(datasetId)}/heartbeat",
    r
  )
  def complete(datasetId: String, r: CompleteRequest) =
    send("POST", s"/datasets/${segment(datasetId)}/complete", r)
  def fail(datasetId: String, r: FailRequest) =
    send("POST", s"/datasets/${segment(datasetId)}/fail", r)
  def release(datasetId: String, r: ReleaseRequest) =
    send("POST", s"/datasets/${segment(datasetId)}/release", r)

  /** A ref is `<dataset>/<kind>/<part>`: the dataset, then the rest as one encoded segment. */
  private def blobPath(ref: String): String =
    val (dataset, rest) = ref.span(_ != '/')
    s"/blobs/${segment(dataset)}/${segment(rest.drop(1))}"

  def getBlob(ref: String) = exchange("GET", blobPath(ref)).map(_.body)

  def putBlob(ref: String, contentType: String, bytes: Array[Byte]) =
    exchange("PUT", blobPath(ref), Some(bytes), contentType).map(_ => ())
