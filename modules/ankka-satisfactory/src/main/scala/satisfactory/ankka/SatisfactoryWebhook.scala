package satisfactory.ankka

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray}
import com.thinkmorestupidless.ankka.http.{FromBody, RequestContext}
import satisfactory.protocol.*

import java.time.{Duration, Instant}
import scala.concurrent.duration.*
import scala.util.Try

/**
 * Receiving satisfactory's webhooks in an ankka service: check and decode, nothing more
 * (DESIGN.md §12). The developer writes the endpoint — its path and its `acl` — like any other;
 * a library that mounted a route with an ACL nobody declared would break ankka's rule.
 *
 * {{{
 *   final class Hooks(client: ComponentClient) extends HttpEndpoint("/hooks"):
 *     val acl = Acl.AllowIf(SatisfactoryWebhook.looksSigned())
 *     postBody("/satisfactory") { (body: Array[Byte]) =>
 *       SatisfactoryWebhook.verify(secret, request, body) match
 *         case Right(SatisfactoryWebhook.Event.Dataset(e)) => … resume the workflow in e's tags …
 *         case Left(refusal) => throw HttpProblem.unauthorized(refusal.toString)
 *     }
 * }}}
 */
object SatisfactoryWebhook:

  /** The raw body, exactly as sent: the signature covers these bytes. */
  given rawBody: FromBody[Array[Byte]] = new FromBody[Array[Byte]]:
    val contentType              = "application/json"
    def read(bytes: Array[Byte]) = Right(bytes)

  enum Event:
    case Dataset(event: DatasetEvent)
    case WebhookFailing(event: WebhookFailingEvent)

  enum Refusal:
    case Unsigned, Stale, BadSignature
    case Malformed(reason: String)

  private final case class Envelope(`type`: String)
  private given envelope: JsonValueCodec[Envelope] = WireCodecs.make

  /** For `Acl.AllowIf`: both headers present and the timestamp fresh. The signature is checked by `verify`. */
  def looksSigned(maxSkew: FiniteDuration = 5.minutes): RequestContext => Boolean = request =>
    request.header(Signing.SignatureHeader).isDefined &&
      request.header(Signing.TimestampHeader).flatMap(t => Try(Instant.parse(t)).toOption)
        .exists(at => Duration.between(at, Instant.now()).abs().toMillis <= maxSkew.toMillis)

  /**
   * HMAC-SHA256 over the exact bytes received (or the path, for a subscription that signs the path),
   * then the typed event.
   */
  def verify(
      secret: String,
      request: RequestContext,
      body: Array[Byte],
      over: String = Signing.OverBody,
      maxSkew: FiniteDuration = 5.minutes
  ): Either[Refusal, Event] =
    val payload = if over == Signing.OverPath then request.path.getBytes("UTF-8") else body
    Signing.verify(
      secret,
      request.header(Signing.SignatureHeader),
      request.header(Signing.TimestampHeader),
      payload,
      Instant.now(),
      Duration.ofMillis(maxSkew.toMillis)
    ) match
      case Left(Signing.Refusal.Missing)  => Left(Refusal.Unsigned)
      case Left(Signing.Refusal.Stale)    => Left(Refusal.Stale)
      case Left(Signing.Refusal.Mismatch) => Left(Refusal.BadSignature)
      case Right(()) =>
        Try(readFromArray[Envelope](body).`type`).toEither.left.map(e => Refusal.Malformed(e.getMessage)).flatMap {
          case EventTypes.Failing => Try(Event.WebhookFailing(readFromArray(body)(using WebhookCodecs.failingEvent))).toEither.left.map(e => Refusal.Malformed(e.getMessage))
          case _                  => Try(Event.Dataset(readFromArray(body)(using WebhookCodecs.datasetEvent))).toEither.left.map(e => Refusal.Malformed(e.getMessage))
        }

  /** The workflow a submission made through the tools belongs to, from its `ankka-workflow:` tag. */
  def workflowOf(event: DatasetEvent): Option[String] =
    event.data.tags.collectFirst { case t if t.startsWith("ankka-workflow:") => t.stripPrefix("ankka-workflow:") }

  def sessionOf(event: DatasetEvent): Option[String] =
    event.data.tags.collectFirst { case t if t.startsWith("ankka-session:") => t.stripPrefix("ankka-session:") }
