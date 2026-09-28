package satisfactory.api.webhooks

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Done, EntityId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import satisfactory.api.application.{Subscription, TenantEntity, FailingNote}
import satisfactory.api.blobs.ApiServices
import satisfactory.protocol.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Duration, Instant}
import scala.concurrent.duration.*
import scala.util.control.NonFatal

final case class Attempt(at: Instant, status: Option[Int], error: Option[String], durationMs: Long)

/** One event to one subscription: what to send, and what happened when it was sent. */
final case class Delivery(
    deliveryId: String,
    tenantId: String,
    subscriptionId: String,
    eventId: String,
    eventType: String,
    datasetId: Option[String],
    target: String,
    body: RawJson,
    attempts: List[Attempt],
    outcome: String,
    createdAt: Instant
):
  def exists: Boolean = tenantId.nonEmpty

object Outcome:
  val Pending   = "pending"
  val Delivered = "delivered"
  val Exhausted = "exhausted"

final case class StartDelivery(
    tenantId: String,
    subscriptionId: String,
    eventId: String,
    eventType: String,
    datasetId: Option[String],
    body: RawJson,
    at: Instant
)

/**
 * Delivers one event to one subscription with Timefold's timing (API.md §2.4, §2.7): an answer within
 * the timeout, else another attempt after the retry interval, up to the maximum — including after a
 * read timeout, which Timefold declines to retry. We retry it, and stay at-least-once, because every
 * event carries an id a receiver dedupes on. Exhausted deliveries raise `webhook.failing`.
 *
 * Id `<eventId>:<subscriptionId>`, so a fan-out delivered twice starts nothing twice.
 */
final class WebhookDeliveryWorkflow(context: WorkflowContext) extends Workflow[Delivery]:
  private val client = context.componentClient

  def emptyState: Delivery =
    Delivery(context.workflowId, "", "", "", "", None, "", RawJson.emptyObject, Nil, Outcome.Pending, Instant.EPOCH)

  override def settings: WorkflowSettings =
    WorkflowSettings.builder.defaultStepTimeout(60.seconds).build

  private def config = ApiServices.settings

  def start(request: StartDelivery): Effect[Done] =
    if currentState.exists then effects.reply(Done)
    else
      effects
        .updateState(
          Delivery(context.workflowId, request.tenantId, request.subscriptionId, request.eventId, request.eventType,
            request.datasetId, "", request.body, Nil, Outcome.Pending, request.at)
        )
        .transitionTo(WebhookDeliveryWorkflow.deliver.ref)
        .thenReply(Done)

  /** A delivery-log retry: from the start, whatever happened before. */
  def retry: Effect[Done] =
    if !currentState.exists then effects.error("no such delivery", ErrorCode.NotFound)
    else effects.updateState(currentState.copy(outcome = Outcome.Pending, attempts = Nil)).transitionTo(WebhookDeliveryWorkflow.deliver.ref).thenReply(Done)

  def get: ReadOnlyEffect[Delivery] = effects.reply(currentState)

  def deliverStep: StepEffect =
    val d = currentState
    subscription(d) match
      case None =>
        stepEffects.updateState(d.copy(outcome = Outcome.Exhausted)).thenEnd
      case Some(sub) =>
        val attempt = send(sub, d)
        val next    = d.copy(target = sub.url, attempts = d.attempts :+ attempt)
        val max     = config.fold(10)(_.webhookMaxAttempts)
        val wait    = config.fold(10.seconds)(_.webhookRetryInterval)
        if attempt.status.exists(s => s >= 200 && s < 300) then
          record(next.copy(outcome = Outcome.Delivered))
          stepEffects.updateState(next.copy(outcome = Outcome.Delivered)).thenEnd
        else if next.attempts.size >= max then
          stepEffects.updateState(next).thenTransitionTo(WebhookDeliveryWorkflow.exhausted.ref)
        else
          record(next)
          stepEffects.updateState(next).thenPause(wait, WebhookDeliveryWorkflow.deliver.ref)

  def exhaustedStep: StepEffect =
    val d = currentState.copy(outcome = Outcome.Exhausted)
    record(d)
    satisfactory.api.metrics.Counters.webhookFailures.incrementAndGet(): Unit
    if d.eventType != EventTypes.Failing then Fanout.failing(client, d, Instant.now())
    stepEffects.updateState(d).thenEnd

  private def subscription(d: Delivery): Option[Subscription] =
    try client.forEventSourcedEntity(EntityId(d.tenantId)).call(TenantEntity.get).invoke().subscriptions.get(d.subscriptionId)
    catch case NonFatal(_) => None

  private def record(d: Delivery): Unit =
    try client.forKeyValueEntity(EntityId(d.deliveryId)).call(WebhookDeliveryLog.put).invoke(d): Unit
    catch case NonFatal(_) => ()

  private def send(sub: Subscription, d: Delivery): Attempt =
    val started   = System.nanoTime()
    val timeout   = config.fold(5.seconds)(_.webhookTimeout)
    val timestamp = Instant.now().toString
    val secret    = SecretBox(config.fold("")(_.secretKey)).open(sub.secretCipher)
    val uri       = URI.create(sub.url)
    val payload   = if sub.signOver == Signing.OverPath then Option(uri.getRawPath).getOrElse("/").getBytes("UTF-8") else d.body.bytes
    val signature = Signing.sign(secret, timestamp, payload)
    val builder = HttpRequest.newBuilder(uri)
      .timeout(Duration.ofMillis(timeout.toMillis))
      .header("Content-Type", "application/json")
      .header(Signing.SignatureHeader, signature)
      .header(Signing.TimestampHeader, timestamp)
      .header(Signing.EventHeader, d.eventType)
      .header(Signing.DeliveryHeader, d.deliveryId)
    sub.headers.foreach((k, v) => builder.header(k, Signing.substitute(v, signature, timestamp)): Unit)
    try
      val response = WebhookDeliveryWorkflow.http.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(d.body.bytes)).build(), HttpResponse.BodyHandlers.discarding())
      Attempt(Instant.now(), Some(response.statusCode), None, (System.nanoTime() - started) / 1_000_000)
    catch
      case e: InterruptedException => throw e
      case NonFatal(e) =>
        Attempt(Instant.now(), None, Some(s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}".take(300)), (System.nanoTime() - started) / 1_000_000)

object WebhookDeliveryWorkflow
    extends Workflow.Companion[WebhookDeliveryWorkflow, Delivery](
      componentId = ComponentId("webhook-delivery"),
      stateSerializer = Codecs.serializer[Delivery]("webhook-delivery")
    ):
  private[webhooks] val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

  given Serializer[StartDelivery] = Codecs.serializer[StartDelivery]("start-delivery")

  def create(ctx: WorkflowContext) = new WebhookDeliveryWorkflow(ctx)

  val deliver   = step("deliver")(_.deliverStep)
  val exhausted = step("exhausted")(_.exhaustedStep)
  val start     = command("start")(_.start)
  val retry     = command("retry")(_.retry)
  val get       = query("get")(_.get)

/** The delivery log: one entry per delivery, updated after every attempt, kept 30 days. */
final class WebhookDeliveryLog extends KeyValueEntity[Delivery]:
  def emptyState: Delivery = Delivery("", "", "", "", "", None, "", RawJson.emptyObject, Nil, Outcome.Pending, Instant.EPOCH)

  def put(d: Delivery): Effect[Done] = effects.updateState(d).expireAfter(30.days).thenReply(_ => Done)
  def get: ReadOnlyEffect[Delivery]  = effects.reply(currentState)

object WebhookDeliveryLog
    extends KeyValueEntity.Companion[WebhookDeliveryLog, Delivery](
      componentId = ComponentId("webhook-delivery-log"),
      stateSerializer = Codecs.serializer[Delivery]("webhook-delivery-entry")
    ):
  def create(ctx: KeyValueEntityContext) = new WebhookDeliveryLog

  val put = command("put")(_.put)
  val get = query("get")(_.get)

final class WebhookDeliveryRowsView extends View[Delivery, Delivery]:
  def onChange(d: Delivery): Effect = effects.updateRow(d.copy(body = RawJson.emptyObject))

object WebhookDeliveryRows
    extends View.Companion[WebhookDeliveryRowsView, Delivery, Delivery](
      componentId = ComponentId("webhook-delivery-rows"),
      source = ChangeSource.stateOf(WebhookDeliveryLog),
      rowSerializer = Codecs.serializer[Delivery]("webhook-delivery-row")
    ):
  def create(ctx: ViewComponentContext) = new WebhookDeliveryRowsView

/** Starting deliveries: for a dataset's final event, and for a subscription that has stopped answering. */
object Fanout:

  private def encode[A](value: A)(using c: com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[A]): RawJson =
    RawJson(WireCodecs.encode(value))

  def start(client: ComponentClient, tenantId: String, sub: Subscription, eventId: String, eventType: String, datasetId: Option[String], body: RawJson, at: Instant): Unit =
    client
      .forWorkflow(EntityId(s"$eventId:${sub.id}"))
      .call(WebhookDeliveryWorkflow.start)
      .invoke(StartDelivery(tenantId, sub.id, eventId, eventType, datasetId, body, at)): Unit

  /** `webhook.failing` to the tenant's other subscriptions that ask for it, at most once per two hours. */
  def failing(client: ComponentClient, d: Delivery, at: Instant): Unit =
    val tenant = client.forEventSourcedEntity(EntityId(d.tenantId))
    if tenant.call(TenantEntity.noteFailingNotified).invoke(FailingNote(d.subscriptionId, at)) then
      val subs  = tenant.call(TenantEntity.get).invoke().subscriptions.values
      val event = WebhookFailingEvent(
        s"evt_failing_${d.subscriptionId}_${at.toEpochMilli}",
        EventTypes.Failing,
        at,
        d.tenantId,
        WebhookFailingData(d.subscriptionId, d.target, d.eventId, d.eventType, d.attempts.size, d.attempts.lastOption.flatMap(_.status), d.attempts.lastOption.flatMap(_.error))
      )
      subs
        .filter(s => s.id != d.subscriptionId && EventTypes.matches(s.events, EventTypes.Failing))
        .foreach(s => start(client, d.tenantId, s, event.id, EventTypes.Failing, None, encode(event)(using WebhookCodecs.failingEvent), at))
