package satisfactory.api.api

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, writeToArray}
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.http.{Bytes, HttpProblem, Respond}
import org.slf4j.LoggerFactory
import satisfactory.protocol.{ErrorInfo, Ids}
import satisfactory.protocol.WireCodecs.given
import satisfactory.spi.InvalidDataset

import scala.util.control.NonFatal

/** A refusal the API answers with Timefold's `ErrorInfo { id, code, message, details }`. */
final case class ApiError(status: Int, code: String, message: String, details: List[String] = Nil)
    extends RuntimeException(message, null, false, false)

object ApiError:
  def badRequest(message: String, details: List[String] = Nil) = ApiError(400, "bad-request", message, details)
  def validation(message: String, details: List[String])        = ApiError(400, "validation", message, details)
  def forbidden(message: String)                                = ApiError(403, "forbidden", message)
  def notFound(message: String)                                 = ApiError(404, "not-found", message)
  def conflict(message: String)                                 = ApiError(409, "conflict", message)
  def gone(message: String)                                     = ApiError(410, "gone", message)
  def tooLarge(message: String)                                 = ApiError(413, "payload-too-large", message)
  def rateLimited(message: String)                              = ApiError(429, "rate-limited", message)

/**
 * Every model- and platform-API route answers through here, so an error is always an `ErrorInfo` and
 * never ankka's own `{status, error}` (which the runtime uses only for what it refuses itself: an
 * ACL's 401/403, an unknown route).
 */
object Replies:
  type Reply = Respond[Bytes]

  private val log = LoggerFactory.getLogger("satisfactory.api")

  def json[A](value: A, status: Int = 200, headers: Vector[(String, String)] = Vector.empty)(using
      codec: JsonValueCodec[A]
  ): Reply =
    Respond(Bytes("application/json", writeToArray(value)), status, headers)

  def raw(bytes: Array[Byte], contentType: String = "application/json", status: Int = 200): Reply =
    Respond(Bytes(contentType, bytes), status)

  def noContent: Reply = Respond(Bytes("application/json", Array.emptyByteArray), 204)

  def error(e: ApiError): Reply =
    json(ErrorInfo(Ids.ulid(), e.code, e.message, e.details), e.status)

  private def codeFor(code: ErrorCode): (Int, String) = code match
    case ErrorCode.BadRequest   => 400 -> "bad-request"
    case ErrorCode.NotFound     => 404 -> "not-found"
    case ErrorCode.Conflict     => 409 -> "conflict"
    case ErrorCode.Forbidden    => 403 -> "forbidden"
    case ErrorCode.Unauthorized => 401 -> "unauthorized"
    case ErrorCode.Unavailable  => 503 -> "unavailable"
    case ErrorCode.Timeout      => 504 -> "timeout"
    case ErrorCode.Internal     => 500 -> "internal"

  def handle(block: => Reply): Reply =
    try block
    catch
      case e: ApiError     => error(e)
      case e: CommandError =>
        val (status, code) = codeFor(e.code)
        error(ApiError(status, code, e.message))
      case e: HttpProblem     => error(ApiError(e.status, "bad-request", e.message))
      case e: InvalidDataset  => error(ApiError.validation(e.getMessage, e.details().toArray.toList.map(_.toString)))
      case _: java.util.concurrent.TimeoutException =>
        error(ApiError(503, "unavailable", "the request timed out inside the service; retry"))
      case NonFatal(e) =>
        val id = Ids.ulid()
        log.error(s"internal error $id", e)
        json(ErrorInfo(id, "internal", "internal error", Nil), 500)
