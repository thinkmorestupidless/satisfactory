package satisfactory.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.{Duration, Instant}
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.util.Try

/** What a dataset event carries (contracts/webhook-events.md): metadata and links, never a solution. */
final case class DatasetEventData(
    id: String,
    name: Option[String],
    model: String,
    modelVersion: String,
    tags: List[String],
    status: String,
    score: Option[String],
    seq: Long,
    parentId: Option[String],
    originId: Option[String],
    supersededBy: Option[String],
    runLink: String,
    outputLink: String
)

/** `webhook.failing`: a subscription's deliveries were exhausted. */
final case class WebhookFailingData(
    subscriptionId: String,
    url: String,
    failedEventId: String,
    failedEventType: String,
    attempts: Int,
    lastStatus: Option[Int],
    lastError: Option[String]
)

/** The envelope every webhook body is. `id` and the dataset's `seq` let a receiver dedupe. */
final case class DatasetEvent(id: String, `type`: String, occurredAt: Instant, tenant: String, data: DatasetEventData)
final case class WebhookFailingEvent(id: String, `type`: String, occurredAt: Instant, tenant: String, data: WebhookFailingData)

object EventTypes:
  val Computed   = "dataset.computed"
  val Invalid    = "dataset.invalid"
  val Completed  = "dataset.completed"
  val Incomplete = "dataset.incomplete"
  val Failed     = "dataset.failed"
  val Failing    = "webhook.failing"

  def forStatus(status: String): Option[String] = status match
    case SolvingStatus.DatasetComputed => Some(Computed)
    case SolvingStatus.DatasetInvalid  => Some(Invalid)
    case SolvingStatus.Completed       => Some(Completed)
    case SolvingStatus.Incomplete      => Some(Incomplete)
    case SolvingStatus.Failed          => Some(Failed)
    case _                             => None

  /** `dataset.*` and exact names. */
  def matches(patterns: List[String], eventType: String): Boolean =
    patterns.exists(p => p == eventType || (p.endsWith(".*") && eventType.startsWith(p.dropRight(1))))

object WebhookCodecs:
  given datasetEvent: JsonValueCodec[DatasetEvent]               = WireCodecs.make
  given failingEvent: JsonValueCodec[WebhookFailingEvent]        = WireCodecs.make

/**
 * Webhook signing, shared by the sender and every receiver (`ankka-satisfactory`): HMAC-SHA256,
 * base64, over `<timestamp>.<payload>` where the payload is the request body (default) or the URL
 * path. The timestamp is in the signed text, so a replayed request cannot be given a fresh one.
 */
object Signing:
  val SignatureHeader = "X-Satisfactory-Signature"
  val TimestampHeader = "X-Satisfactory-Timestamp"
  val EventHeader     = "X-Satisfactory-Event"
  val DeliveryHeader  = "X-Satisfactory-Delivery"

  val OverBody = "body"
  val OverPath = "path"

  def sign(secret: String, timestamp: String, payload: Array[Byte]): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"))
    mac.update(timestamp.getBytes(UTF_8))
    mac.update('.'.toByte)
    Base64.getEncoder.encodeToString(mac.doFinal(payload))

  /** Custom headers may carry the signature and timestamp: `{hmac_signature}`, `{hmac_timestamp}`. */
  def substitute(template: String, signature: String, timestamp: String): String =
    template.replace("{hmac_signature}", signature).replace("{hmac_timestamp}", timestamp)

  enum Refusal:
    case Missing, Stale, Mismatch

  /** Verifies a received request; a timestamp older (or newer) than `maxSkew` is refused. */
  def verify(
      secret: String,
      signature: Option[String],
      timestamp: Option[String],
      payload: Array[Byte],
      now: Instant = Instant.now(),
      maxSkew: Duration = Duration.ofMinutes(5)
  ): Either[Refusal, Unit] =
    (signature, timestamp) match
      case (Some(sig), Some(ts)) =>
        Try(Instant.parse(ts)).toOption match
          case None => Left(Refusal.Stale)
          case Some(at) if Duration.between(at, now).abs().compareTo(maxSkew) > 0 => Left(Refusal.Stale)
          case Some(_) =>
            val expected = sign(secret, ts, payload)
            if MessageDigest.isEqual(expected.getBytes(UTF_8), sig.trim.getBytes(UTF_8)) then Right(())
            else Left(Refusal.Mismatch)
      case _ => Left(Refusal.Missing)
