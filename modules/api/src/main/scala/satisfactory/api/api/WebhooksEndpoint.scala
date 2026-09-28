package satisfactory.api.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.SqlFragment
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.*
import satisfactory.api.api.Bodies.given
import satisfactory.api.api.Replies.*
import satisfactory.api.application.*
import satisfactory.api.domain.ApiContext
import satisfactory.api.webhooks.*
import satisfactory.protocol.{EventTypes, Ids, Page, Signing, WireCodecs}

import java.net.URI
import java.time.Instant
import scala.util.Try

final case class SigningDto(method: String = "hmac-sha256", over: String = Signing.OverBody)
final case class SubscriptionRequest(
    url: String,
    events: List[String],
    filters: Option[SubscriptionFilters] = None,
    signing: Option[SigningDto] = None,
    headers: Option[Map[String, String]] = None
)
final case class SubscriptionDto(
    id: String,
    url: String,
    events: List[String],
    filters: SubscriptionFilters,
    signing: SigningDto,
    headers: Map[String, String],
    createdAt: Instant,
    lastFailingNotifiedAt: Option[Instant],
    secret: Option[String]
)
final case class DeliveryLogEntry(
    deliveryId: String,
    subscriptionId: String,
    eventId: String,
    eventType: String,
    datasetId: Option[String],
    target: String,
    attempts: List[Attempt],
    outcome: String,
    createdAt: Instant
)

object WebhookApiCodecs:
  given request: JsonValueCodec[SubscriptionRequest]      = WireCodecs.make
  given subscription: JsonValueCodec[SubscriptionDto]      = WireCodecs.make
  given subscriptions: JsonValueCodec[List[SubscriptionDto]] = WireCodecs.make
  given deliveries: JsonValueCodec[Page[DeliveryLogEntry]] = WireCodecs.make

/**
 * Webhook subscriptions and their delivery log (API.md §2.7), managed through the platform API rather
 * than a UI. The signing secret is returned when a subscription is made or its secret rotated, and
 * never again.
 */
final class WebhooksEndpoint(ctx: ApiContext) extends PlatformEndpoint(ctx):
  import WebhookApiCodecs.given

  private val box = SecretBox(ctx.settings.secretKey)

  private def dto(s: Subscription, secret: Option[String] = None) =
    SubscriptionDto(s.id, s.url, s.events, s.filters, SigningDto(over = s.signOver), s.headers, s.createdAt, s.lastFailingNotifiedAt, secret)

  private def validated(r: SubscriptionRequest): SubscriptionRequest =
    val uri = Try(URI.create(r.url)).getOrElse(throw ApiError.badRequest(s"'${r.url}' is not a URL"))
    if !Set("http", "https").contains(Option(uri.getScheme).getOrElse("")) || uri.getHost == null then
      throw ApiError.badRequest("a webhook url is an absolute http(s) URL")
    val known = List("dataset.*", EventTypes.Computed, EventTypes.Invalid, EventTypes.Completed, EventTypes.Incomplete, EventTypes.Failed, EventTypes.Failing)
    if r.events.isEmpty || !r.events.forall(known.contains) then
      throw ApiError.badRequest(s"events must be some of ${known.mkString(", ")}")
    r.signing.foreach(s =>
      if s.method != "hmac-sha256" || !Set(Signing.OverBody, Signing.OverPath).contains(s.over) then
        throw ApiError.badRequest("signing is hmac-sha256 over body or path")
    )
    r.filters.flatMap(_.nameRegex).foreach(re =>
      if Try(java.util.regex.Pattern.compile(re)).isFailure then throw ApiError.badRequest(s"'$re' is not a regular expression")
    )
    r

  private def save(tenantId: String, id: String, r: SubscriptionRequest, secret: String, createdAt: Instant): Subscription =
    val sub = Subscription(id, r.url, r.events.distinct, r.filters.getOrElse(SubscriptionFilters()), r.signing.fold(Signing.OverBody)(_.over),
      r.headers.getOrElse(Map.empty), box.seal(secret), createdAt, None)
    tenantEntity(tenantId).call(TenantEntity.saveSubscription).invoke(sub)

  get("/tenants/{tenantId}/webhooks") { (tenantId: String) =>
    handle(json(visible(tenantId).subscriptions.values.toList.sortBy(_.createdAt).map(dto(_))))
  }

  postBody("/tenants/{tenantId}/webhooks") { (tenantId: String, body: Array[Byte]) =>
    handle {
      val _      = administered(tenantId)
      val secret = Ids.secret("whsec_")
      val sub    = save(tenantId, Ids.subscription(), validated(Bodies.parse[SubscriptionRequest](body)), secret, ctx.now())
      json(dto(sub, Some(secret)), 201)
    }
  }

  get("/tenants/{tenantId}/webhooks/{subscriptionId}") { (tenantId: String, id: String) =>
    handle(json(dto(visible(tenantId).subscriptions.getOrElse(id, throw ApiError.notFound(s"no subscription $id")))))
  }

  putBody("/tenants/{tenantId}/webhooks/{subscriptionId}") { (tenantId: String, id: String, body: Array[Byte]) =>
    handle {
      val existing = administered(tenantId).subscriptions.getOrElse(id, throw ApiError.notFound(s"no subscription $id"))
      val updated = validated(Bodies.parse[SubscriptionRequest](body))
      val sub = tenantEntity(tenantId).call(TenantEntity.saveSubscription).invoke(
        existing.copy(url = updated.url, events = updated.events.distinct, filters = updated.filters.getOrElse(SubscriptionFilters()),
          signOver = updated.signing.fold(existing.signOver)(_.over), headers = updated.headers.getOrElse(existing.headers))
      )
      json(dto(sub))
    }
  }

  delete("/tenants/{tenantId}/webhooks/{subscriptionId}") { (tenantId: String, id: String) =>
    handle {
      val _ = administered(tenantId)
      val _ = tenantEntity(tenantId).call(TenantEntity.deleteSubscription).invoke(DeleteById(id))
      noContent
    }
  }

  post("/tenants/{tenantId}/webhooks/{subscriptionId}/rotate-secret") { (tenantId: String, id: String) =>
    handle {
      val existing = administered(tenantId).subscriptions.getOrElse(id, throw ApiError.notFound(s"no subscription $id"))
      val secret   = Ids.secret("whsec_")
      val sub = tenantEntity(tenantId).call(TenantEntity.saveSubscription).invoke(existing.copy(secretCipher = box.seal(secret)))
      json(dto(sub, Some(secret)))
    }
  }

  get("/tenants/{tenantId}/webhooks/deliveries") { (tenantId: String) =>
    handle {
      val _    = visible(tenantId)
      val size = query.optional[Int]("size").getOrElse(50).max(1).min(200)
      val page = query.optional[Int]("page").getOrElse(0).max(0)
      val bySub     = query.raw("subscriptionId").fold(SqlFragment.raw(""))(s => SqlFragment.raw(" AND ") ++ jsonText("subscriptionId") ++ sql" = $s")
      val byOutcome = query.raw("outcome").fold(SqlFragment.raw(""))(o => SqlFragment.raw(" AND ") ++ jsonText("outcome") ++ sql" = $o")
      val rows = ctx.views.forView(WebhookDeliveryRows).ordered(
        jsonText("tenantId") ++ sql" = $tenantId" ++ bySub ++ byOutcome,
        SqlFragment.raw("payload::jsonb->>'createdAt' DESC"),
        (page + 1) * size + 1
      )
      val content = rows.slice(page * size, page * size + size).toList.map(d =>
        DeliveryLogEntry(d.deliveryId, d.subscriptionId, d.eventId, d.eventType, d.datasetId, d.target, d.attempts, d.outcome, d.createdAt)
      )
      json(Page(content, page, size, rows.size > (page + 1) * size))
    }
  }

  post("/tenants/{tenantId}/webhooks/deliveries/{deliveryId}/retry") { (tenantId: String, deliveryId: String) =>
    handle {
      val _ = administered(tenantId)
      val d = ctx.client.forKeyValueEntity(EntityId(deliveryId)).call(WebhookDeliveryLog.get).invoke()
      if d.tenantId != tenantId then throw ApiError.notFound(s"no delivery $deliveryId")
      val _ = ctx.client.forWorkflow(EntityId(deliveryId)).call(WebhookDeliveryWorkflow.retry).invoke()
      noContent
    }
  }
