package satisfactory.api.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.*
import com.thinkmorestupidless.ankka.http.*
import satisfactory.api.api.Bodies.given
import satisfactory.api.api.Replies.*
import satisfactory.api.application.*
import satisfactory.api.auth.PlatformAuth
import satisfactory.api.domain.ApiContext
import satisfactory.protocol.*
import satisfactory.spi.Json

import java.time.{Duration, Instant}
import scala.jdk.CollectionConverters.*

// ── Wire types of the platform API (contracts/platform-api.md) ─────────────────────────────

final case class LimitsDto(concurrency: Int, submitPerMinute: Int, lifetimeCeiling: String, retention: String)
final case class NewTenant(name: String, firstAdminSubject: String, limits: Option[LimitsDto] = None)
final case class TenantDto(id: String, name: String, createdAt: Option[Instant], limits: LimitsDto, queuePaused: Boolean, memberCount: Int, keyCount: Int)
final case class MemberDto(subject: String, role: String, addedAt: Instant)
final case class NewMember(subject: String, role: String)
final case class RoleChange(role: String)
final case class TenantMembership(tenantId: String, role: String)
final case class PlatformWhoAmI(subject: String, operator: Boolean, tenants: List[TenantMembership])
final case class NewKey(label: String, role: String)
final case class KeyDto(keyId: String, label: String, role: String, createdAt: Instant, revokedAt: Option[Instant])
final case class CreatedKey(keyId: String, key: String, role: String)
final case class ProfileDto(
    id: Option[String] = None,
    model: String,
    name: String,
    description: Option[String] = None,
    defaultConfigProfileId: Option[String] = None,
    runConfiguration: Option[RunConfiguration] = None,
    modelConfiguration: Option[Map[String, Long]] = None,
    resourcesConfiguration: Option[RawJson] = None,
    updatedAt: Option[Instant] = None,
    readOnly: Option[Boolean] = None
)

object PlatformCodecs:
  given newTenant: JsonValueCodec[NewTenant] = WireCodecs.make
  given tenant: JsonValueCodec[TenantDto] = WireCodecs.make
  given tenants: JsonValueCodec[List[TenantDto]] = WireCodecs.make
  given members: JsonValueCodec[List[MemberDto]] = WireCodecs.make
  given newMember: JsonValueCodec[NewMember] = WireCodecs.make
  given roleChange: JsonValueCodec[RoleChange] = WireCodecs.make
  given whoAmI: JsonValueCodec[PlatformWhoAmI] = WireCodecs.make
  given newKey: JsonValueCodec[NewKey] = WireCodecs.make
  given keys: JsonValueCodec[List[KeyDto]] = WireCodecs.make
  given createdKey: JsonValueCodec[CreatedKey] = WireCodecs.make
  given limits: JsonValueCodec[LimitsDto] = WireCodecs.make
  given profile: JsonValueCodec[ProfileDto] = WireCodecs.make
  given profiles: JsonValueCodec[List[ProfileDto]] = WireCodecs.make
  given member: JsonValueCodec[MemberDto] = WireCodecs.make

/**
 * The platform API (API.md §3): tenants, their members, keys, limits and configuration profiles,
 * authenticated by the installation's identity provider, never by an API key. Every tenant route
 * checks the caller's membership on the tenant's own entity; a tenant you are not a member of
 * answers `404`, exactly as one that does not exist. Admins change things; members read.
 */
class PlatformEndpoint(ctx: ApiContext) extends HttpEndpoint("/api/platform/v1"):
  import PlatformCodecs.given

  val acl: Acl = PlatformAuth.acl(ctx.verifier)

  protected def tenantEntity(id: String) = ctx.client.forEventSourcedEntity(EntityId(id))

  protected def isOperator: Boolean = principal.roles.contains(ctx.settings.operatorRole)

  /** The tenant, if the caller may see it: a member, or a platform operator. */
  protected def visible(tenantId: String): Tenant =
    val tenant =
      try tenantEntity(tenantId).call(TenantEntity.get).invoke()
      catch case e: CommandError if e.code == ErrorCode.NotFound => throw ApiError.notFound(s"no tenant $tenantId")
    if !isOperator && !tenant.members.contains(principal.subject) then throw ApiError.notFound(s"no tenant $tenantId")
    tenant

  protected def administered(tenantId: String): Tenant =
    val tenant = visible(tenantId)
    if !isOperator && !tenant.members.get(principal.subject).exists(_.role == Roles.Admin) then
      throw ApiError.forbidden("tenant admins only")
    tenant

  private def limitsDto(l: Limits) =
    LimitsDto(l.concurrency, l.submitPerMinute, Duration.ofSeconds(l.lifetimeCeilingSeconds).toString, Duration.ofSeconds(l.retentionSeconds).toString)

  private def limitsOf(d: LimitsDto): Limits =
    try Limits(d.concurrency, d.submitPerMinute, Duration.parse(d.lifetimeCeiling).toSeconds, Duration.parse(d.retention).toSeconds)
    catch case _: java.time.format.DateTimeParseException => throw ApiError.badRequest("lifetimeCeiling and retention are ISO-8601 durations")

  private def tenantDto(t: Tenant) =
    TenantDto(t.id, t.name, t.createdAt, limitsDto(t.limits), t.queuePaused, t.members.size, t.keys.values.count(_.revokedAt.isEmpty))

  private val defaults = satisfactory.api.Bootstrap.limits(ctx.settings)

  // ── Tenants and membership ─────────────────────────────────────────────────────────────────

  postBody("/tenants") { (body: Array[Byte]) =>
    handle {
      if !isOperator then throw ApiError.forbidden("only platform operators create tenants")
      val request = Bodies.parse[NewTenant](body)
      if request.name.isBlank || request.firstAdminSubject.isBlank then
        throw ApiError.badRequest("a tenant needs a name and a first administrator")
      val id     = Ids.tenant()
      val limits = request.limits.map(limitsOf).getOrElse(defaults)
      val tenant = tenantEntity(id)
        .call(TenantEntity.create)
        .invoke(CreateTenant(request.name, Some(request.firstAdminSubject), limits, principal.subject, ctx.now()))
      ctx.audit(principal.subject, "create-tenant", id)
      json(tenantDto(tenant), 201)
    }
  }

  get("/tenants") { () =>
    handle {
      val rows  = ctx.views.forView(TenantRows)
      val found = if isOperator then rows.all() else rows.where(jsonContains("memberSubjects", principal.subject))
      json(found.toList.flatMap(r => scala.util.Try(tenantDto(tenantEntity(r.tenantId).call(TenantEntity.get).invoke())).toOption))
    }
  }

  get("/tenants/{tenantId}") { (tenantId: String) =>
    handle(json(tenantDto(visible(tenantId))))
  }

  get("/tenants/{tenantId}/members") { (tenantId: String) =>
    handle(json(visible(tenantId).members.values.toList.sortBy(_.subject).map(m => MemberDto(m.subject, m.role, m.addedAt))))
  }

  postBody("/tenants/{tenantId}/members") { (tenantId: String, body: Array[Byte]) =>
    handle {
      val _       = administered(tenantId)
      val request = Bodies.parse[NewMember](body)
      val _ = tenantEntity(tenantId).call(TenantEntity.addMember).invoke(MemberChange(request.subject, request.role, principal.subject, ctx.now()))
      json(MemberDto(request.subject, request.role, ctx.now()), 201)
    }
  }

  patchBody("/tenants/{tenantId}/members/{subject}") { (tenantId: String, subject: String, body: Array[Byte]) =>
    handle {
      val _    = administered(tenantId)
      val role = Bodies.parse[RoleChange](body).role
      val t = tenantEntity(tenantId).call(TenantEntity.changeMemberRole).invoke(MemberChange(subject, role, principal.subject, ctx.now()))
      val m = t.members(subject)
      json(MemberDto(m.subject, m.role, m.addedAt))
    }
  }

  delete("/tenants/{tenantId}/members/{subject}") { (tenantId: String, subject: String) =>
    handle {
      val _ = administered(tenantId)
      val _ = tenantEntity(tenantId).call(TenantEntity.removeMember).invoke(RemoveMember(subject))
      noContent
    }
  }

  get("/whoami") { () =>
    handle {
      val rows = ctx.views.forView(TenantRows).where(jsonContains("memberSubjects", principal.subject))
      val memberships = rows.toList.flatMap { r =>
        scala.util.Try(tenantEntity(r.tenantId).call(TenantEntity.get).invoke()).toOption
          .flatMap(_.members.get(principal.subject))
          .map(m => TenantMembership(r.tenantId, m.role))
      }
      json(PlatformWhoAmI(principal.subject, isOperator, memberships))
    }
  }

  // ── API keys ───────────────────────────────────────────────────────────────────────────────

  get("/tenants/{tenantId}/keys") { (tenantId: String) =>
    handle(json(visible(tenantId).keys.values.toList.sortBy(_.createdAt).map(k => KeyDto(k.keyId, k.label, k.role, k.createdAt, k.revokedAt))))
  }

  /** The plaintext key is in this response and nowhere else, ever: it is stored only as a hash. */
  postBody("/tenants/{tenantId}/keys") { (tenantId: String, body: Array[Byte]) =>
    handle {
      val _         = administered(tenantId)
      val request   = Bodies.parse[NewKey](body)
      if !Set(Roles.ReadOnly, Roles.ReadWrite).contains(request.role) then
        throw ApiError.badRequest("a key's role is read-only or read-write")
      val plaintext = Hashing.newApiKey()
      val hash      = Hashing.sha256(plaintext)
      val keyId     = Ids.apiKey()
      ctx.client.forKeyValueEntity(EntityId(hash)).call(ApiKeyEntity.create).invoke(ApiKey(tenantId, keyId, request.role, revoked = false)): Unit
      val _ = tenantEntity(tenantId).call(TenantEntity.createKey).invoke(CreateKey(keyId, request.label, request.role, ctx.now(), hash))
      json(CreatedKey(keyId, plaintext, request.role), 201)
    }
  }

  /** Revoked on the key's own entity first, so a crash between the two writes leaves it unusable. */
  delete("/tenants/{tenantId}/keys/{keyId}") { (tenantId: String, keyId: String) =>
    handle {
      val tenant = administered(tenantId)
      val record = tenant.keys.getOrElse(keyId, throw ApiError.notFound(s"no key $keyId"))
      ctx.client.forKeyValueEntity(EntityId(record.hash)).call(ApiKeyEntity.revoke).invoke(): Unit
      val _ = tenantEntity(tenantId).call(TenantEntity.revokeKey).invoke(RevokeKey(keyId, ctx.now()))
      noContent
    }
  }

  // ── Limits ─────────────────────────────────────────────────────────────────────────────────

  get("/tenants/{tenantId}/limits") { (tenantId: String) =>
    handle(json(limitsDto(visible(tenantId).limits)))
  }

  /** Admins may set limits up to the installation's defaults; operators may raise them past it. */
  putBody("/tenants/{tenantId}/limits") { (tenantId: String, body: Array[Byte]) =>
    handle {
      val _      = administered(tenantId)
      val limits = limitsOf(Bodies.parse[LimitsDto](body))
      val within =
        limits.concurrency <= defaults.concurrency && limits.submitPerMinute <= defaults.submitPerMinute &&
          limits.lifetimeCeilingSeconds <= defaults.lifetimeCeilingSeconds && limits.retentionSeconds <= defaults.retentionSeconds
      if !within && !isOperator then throw ApiError.forbidden("only a platform operator can raise limits past the installation's defaults")
      json(limitsDto(tenantEntity(tenantId).call(TenantEntity.setLimits).invoke(limits).limits))
    }
  }

  // ── Configuration profiles ─────────────────────────────────────────────────────────────────

  private def runtimeFor(model: String) =
    ctx.catalog.byKey(model).toScala.getOrElse(throw ApiError.badRequest(s"no model $model; use <id>/<version>"))

  private def standard(model: String): ProfileDto =
    val runtime = runtimeFor(model)
    ProfileDto(
      id = Some("standard"),
      model = model,
      name = "standard",
      description = Some("The model's defaults. Read-only."),
      runConfiguration = Some(RunConfiguration(maxThreadCount = Some(1), termination = Some(satisfactory.api.domain.ConfigResolver.DefaultTermination))),
      modelConfiguration = Some(runtime.model().constraints().asScala.map(c => c.weightField() -> c.defaultWeight()).toMap),
      readOnly = Some(true)
    )

  private def dto(p: Profile) =
    ProfileDto(Some(p.id), p.modelKey, p.name, Some(p.description), Some(p.defaultConfigProfileId), p.runConfiguration, Some(p.weights), p.resourcesConfiguration, Some(p.updatedAt), Some(false))

  get("/tenants/{tenantId}/configurations") { (tenantId: String) =>
    handle {
      val tenant = visible(tenantId)
      val models = query.raw("model").map(List(_)).getOrElse(ctx.catalog.keys().asScala.toList)
      json(models.map(standard) ++ tenant.profiles.values.filter(p => models.contains(p.modelKey)).toList.sortBy(_.name).map(dto))
    }
  }

  get("/tenants/{tenantId}/configurations/{configurationId}") { (tenantId: String, configurationId: String) =>
    handle {
      val tenant = visible(tenantId)
      if configurationId == "standard" then json(standard(query.raw("model").getOrElse(ctx.catalog.keys().get(0))))
      else json(dto(tenant.profiles.getOrElse(configurationId, throw ApiError.notFound(s"no profile $configurationId"))))
    }
  }

  private def save(tenantId: String, id: String, request: ProfileDto): Profile =
    val runtime  = runtimeFor(request.model)
    val weights  = request.modelConfiguration.getOrElse(Map.empty)
    val problems = if weights.isEmpty then Nil else runtime.validateOverrides(Json.tree(weights.asJava)).asScala.toList
    if problems.nonEmpty then throw ApiError.validation("the profile's weights do not match the model", problems)
    val profile = Profile(
      id, request.model, request.name, request.description.getOrElse(""), request.defaultConfigProfileId.getOrElse("standard"),
      request.runConfiguration.map(r => r.copy(maxThreadCount = r.maxThreadCount.map(_.min(1)))), weights, request.resourcesConfiguration, ctx.now()
    )
    tenantEntity(tenantId).call(TenantEntity.saveProfile).invoke(profile)

  postBody("/tenants/{tenantId}/configurations") { (tenantId: String, body: Array[Byte]) =>
    handle {
      val _ = administered(tenantId)
      json(dto(save(tenantId, Ids.profile(), Bodies.parse[ProfileDto](body))), 201)
    }
  }

  putBody("/tenants/{tenantId}/configurations/{configurationId}") { (tenantId: String, configurationId: String, body: Array[Byte]) =>
    handle {
      if configurationId == "standard" then throw ApiError(405, "read-only", "the standard profile is read-only")
      val tenant = administered(tenantId)
      if !tenant.profiles.contains(configurationId) then throw ApiError.notFound(s"no profile $configurationId")
      json(dto(save(tenantId, configurationId, Bodies.parse[ProfileDto](body))))
    }
  }

  delete("/tenants/{tenantId}/configurations/{configurationId}") { (tenantId: String, configurationId: String) =>
    handle {
      if configurationId == "standard" then throw ApiError(405, "read-only", "the standard profile is read-only")
      val _ = administered(tenantId)
      val _ = tenantEntity(tenantId).call(TenantEntity.deleteProfile).invoke(DeleteById(configurationId))
      noContent
    }
  }

  extension [A](o: java.util.Optional[A]) private def toScala: Option[A] = if o.isPresent then Some(o.get) else None
