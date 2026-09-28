package satisfactory.api.application

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Done, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import satisfactory.protocol.{RawJson, RunConfiguration}

import java.time.Instant

final case class Limits(
    concurrency: Int,
    submitPerMinute: Int,
    lifetimeCeilingSeconds: Long,
    retentionSeconds: Long
)

final case class Member(subject: String, role: String, addedAt: Instant, addedBy: String)

object Roles:
  val Admin     = "admin"
  val Member    = "member"
  val ReadOnly  = "read-only"
  val ReadWrite = "read-write"

/** A key's record in its tenant. `hash` (SHA-256 of a 256-bit random key) is how revocation finds it. */
final case class KeyRecord(keyId: String, label: String, role: String, createdAt: Instant, revokedAt: Option[Instant], hash: String = "")

/**
 * A configuration profile (API.md §3): run configuration, weights and resources under a name, for one
 * model. The read-only `standard` profile is synthesised, never stored.
 */
final case class Profile(
    id: String,
    modelKey: String,
    name: String,
    description: String,
    defaultConfigProfileId: String,
    runConfiguration: Option[RunConfiguration],
    weights: Map[String, Long],
    resourcesConfiguration: Option[RawJson],
    updatedAt: Instant
)

final case class SubscriptionFilters(
    status: Option[List[String]] = None,
    nameRegex: Option[String] = None,
    tags: Option[List[String]] = None,
    model: Option[String] = None
)

/** A webhook subscription; the signing secret is held encrypted with the service's secret key. */
final case class Subscription(
    id: String,
    url: String,
    events: List[String],
    filters: SubscriptionFilters,
    signOver: String,
    headers: Map[String, String],
    secretCipher: String,
    createdAt: Instant,
    lastFailingNotifiedAt: Option[Instant]
)

/**
 * A tenant: the isolation boundary for keys, datasets, profiles and webhooks, and the one place
 * concurrency is counted exactly (a count from a view is eventually consistent; a single writer is
 * not — DESIGN.md §3.4).
 */
final case class Tenant(
    id: String,
    name: String = "",
    createdAt: Option[Instant] = None,
    createdBy: String = "",
    members: Map[String, Member] = Map.empty,
    keys: Map[String, KeyRecord] = Map.empty,
    limits: Limits = Limits(2, 60, 43200, 2592000),
    activeSlots: Set[String] = Set.empty,
    queuePaused: Boolean = false,
    profiles: Map[String, Profile] = Map.empty,
    subscriptions: Map[String, Subscription] = Map.empty,
    tokens: Double = 0,
    tokensAt: Option[Instant] = None
):
  def exists: Boolean = createdAt.isDefined

enum TenantEvent:
  case TenantCreated(name: String, limits: Limits, by: String, at: Instant)
  case MemberAdded(member: Member)
  case MemberRemoved(subject: String)
  case MemberRoleChanged(subject: String, role: String)
  case KeyCreated(key: KeyRecord)
  case KeyRevoked(keyId: String, at: Instant)
  case LimitsChanged(limits: Limits)
  case SlotAcquired(datasetId: String)
  case SlotReleased(datasetId: String)
  case QueuePaused(by: String, at: Instant)
  case QueueResumed(by: String, at: Instant)
  case ProfileSaved(profile: Profile)
  case ProfileDeleted(id: String)
  case SubscriptionSaved(subscription: Subscription)
  case SubscriptionDeleted(id: String)
  case FailingNotified(subscriptionId: String, at: Instant)
  case SubmitTokenTaken(tokens: Double, at: Instant)

final case class CreateTenant(name: String, firstAdmin: Option[String], limits: Limits, by: String, at: Instant)
final case class MemberChange(subject: String, role: String, by: String, at: Instant)
final case class RemoveMember(subject: String)
final case class CreateKey(keyId: String, label: String, role: String, at: Instant, hash: String = "")
final case class RevokeKey(keyId: String, at: Instant)
final case class SlotRequest(datasetId: String)
final case class ByWhom(by: String, at: Instant)
final case class DeleteById(id: String)
final case class FailingNote(subscriptionId: String, at: Instant)

/** A tenant's listing row: enough to answer "which tenants is this person a member of". */
final case class TenantRow(tenantId: String, name: String, memberSubjects: List[String], queuePaused: Boolean)

final class TenantRowsView extends View[TenantEvent, TenantRow]:
  def onChange(event: TenantEvent): Effect =
    val row = rowState.getOrElse(TenantRow(updateContext.subject, "", Nil, queuePaused = false))
    event match
      case TenantEvent.TenantCreated(name, _, _, _)  => effects.updateRow(row.copy(name = name))
      case TenantEvent.MemberAdded(member)            => effects.updateRow(row.copy(memberSubjects = (row.memberSubjects :+ member.subject).distinct))
      case TenantEvent.MemberRemoved(subject)         => effects.updateRow(row.copy(memberSubjects = row.memberSubjects.filterNot(_ == subject)))
      case TenantEvent.QueuePaused(_, _)              => effects.updateRow(row.copy(queuePaused = true))
      case TenantEvent.QueueResumed(_, _)             => effects.updateRow(row.copy(queuePaused = false))
      case _                                          => effects.ignore()

object TenantRows
    extends View.Companion[TenantRowsView, TenantEvent, TenantRow](
      componentId = ComponentId("tenant-rows"),
      source = ChangeSource.eventsOf(TenantEntity),
      rowSerializer = Codecs.serializer[TenantRow]("tenant-row")
    ):
  def create(ctx: ViewComponentContext) = new TenantRowsView

final class TenantEntity(context: EventSourcedEntityContext) extends EventSourcedEntity[Tenant, TenantEvent]:
  import TenantEvent.*

  def emptyState: Tenant = Tenant(context.entityId)

  def applyEvent(event: TenantEvent): Tenant =
    val t = currentState
    event match
      case TenantCreated(name, limits, by, at) =>
        t.copy(name = name, limits = limits, createdBy = by, createdAt = Some(at), tokens = limits.submitPerMinute.toDouble, tokensAt = Some(at))
      case MemberAdded(member)          => t.copy(members = t.members + (member.subject -> member))
      case MemberRemoved(subject)       => t.copy(members = t.members - subject)
      case MemberRoleChanged(s, role)   => t.copy(members = t.members.updatedWith(s)(_.map(_.copy(role = role))))
      case KeyCreated(key)              => t.copy(keys = t.keys + (key.keyId -> key))
      case KeyRevoked(keyId, at)        => t.copy(keys = t.keys.updatedWith(keyId)(_.map(_.copy(revokedAt = Some(at)))))
      case LimitsChanged(limits)        => t.copy(limits = limits, tokens = limits.submitPerMinute.toDouble)
      case SlotAcquired(datasetId)      => t.copy(activeSlots = t.activeSlots + datasetId)
      case SlotReleased(datasetId)      => t.copy(activeSlots = t.activeSlots - datasetId)
      case QueuePaused(_, _)            => t.copy(queuePaused = true)
      case QueueResumed(_, _)           => t.copy(queuePaused = false)
      case ProfileSaved(profile)        => t.copy(profiles = t.profiles + (profile.id -> profile))
      case ProfileDeleted(id)           => t.copy(profiles = t.profiles - id)
      case SubscriptionSaved(sub)       => t.copy(subscriptions = t.subscriptions + (sub.id -> sub))
      case SubscriptionDeleted(id)      => t.copy(subscriptions = t.subscriptions - id)
      case FailingNotified(id, at) =>
        t.copy(subscriptions = t.subscriptions.updatedWith(id)(_.map(_.copy(lastFailingNotifiedAt = Some(at)))))
      case SubmitTokenTaken(tokens, at) => t.copy(tokens = tokens, tokensAt = Some(at))

  private def t = currentState

  private def missing[R]: ReadOnlyEffect[R] =
    effects.error(s"tenant ${context.entityId} does not exist", ErrorCode.NotFound)

  def create(request: CreateTenant): Effect[Tenant] =
    if t.exists then effects.error(s"tenant ${context.entityId} already exists", ErrorCode.Conflict)
    else
      val admin = request.firstAdmin.map(s => MemberAdded(Member(s, Roles.Admin, request.at, request.by)))
      effects
        .persistAll(Vector(TenantCreated(request.name, request.limits, request.by, request.at)) ++ admin)
        .thenReplyState

  def get: ReadOnlyEffect[Tenant] = if t.exists then effects.reply(t) else missing

  def addMember(request: MemberChange): Effect[Tenant] =
    if !t.exists then missing
    else if !Set(Roles.Admin, Roles.Member).contains(request.role) then
      effects.error(s"role must be admin or member, not ${request.role}")
    else if t.members.contains(request.subject) then effects.error("already a member", ErrorCode.Conflict)
    else effects.persist(MemberAdded(Member(request.subject, request.role, request.at, request.by))).thenReplyState

  def changeMemberRole(request: MemberChange): Effect[Tenant] =
    if !t.exists then missing
    else if !t.members.contains(request.subject) then effects.error("not a member", ErrorCode.NotFound)
    else if !Set(Roles.Admin, Roles.Member).contains(request.role) then
      effects.error(s"role must be admin or member, not ${request.role}")
    else if request.role != Roles.Admin && isLastAdmin(request.subject) then
      effects.error("the last admin cannot be demoted", ErrorCode.Conflict)
    else effects.persist(MemberRoleChanged(request.subject, request.role)).thenReplyState

  def removeMember(request: RemoveMember): Effect[Tenant] =
    if !t.exists then missing
    else if !t.members.contains(request.subject) then effects.error("not a member", ErrorCode.NotFound)
    else if isLastAdmin(request.subject) then effects.error("the last admin cannot be removed", ErrorCode.Conflict)
    else effects.persist(MemberRemoved(request.subject)).thenReplyState

  private def isLastAdmin(subject: String): Boolean =
    t.members.get(subject).exists(_.role == Roles.Admin) && t.members.values.count(_.role == Roles.Admin) == 1

  def createKey(request: CreateKey): Effect[KeyRecord] =
    if !t.exists then missing
    else if !Set(Roles.ReadOnly, Roles.ReadWrite).contains(request.role) then
      effects.error(s"a key's role is read-only or read-write, not ${request.role}")
    else
      val key = KeyRecord(request.keyId, request.label, request.role, request.at, None, request.hash)
      effects.persist(KeyCreated(key)).thenReply(_ => key)

  def revokeKey(request: RevokeKey): Effect[Done] =
    if !t.exists then missing
    else if !t.keys.contains(request.keyId) then effects.error("no such key", ErrorCode.NotFound)
    else if t.keys(request.keyId).revokedAt.isDefined then effects.reply(Done)
    else effects.persist(KeyRevoked(request.keyId, request.at)).thenReply(_ => Done)

  def setLimits(limits: Limits): Effect[Tenant] =
    if !t.exists then missing
    else if limits.concurrency < 0 || limits.submitPerMinute < 0 || limits.lifetimeCeilingSeconds <= 0 || limits.retentionSeconds <= 0
    then effects.error("limits must be positive")
    else effects.persist(LimitsChanged(limits)).thenReplyState

  /**
   * Exact concurrency: granted while the tenant has room and its queue is not paused. Idempotent — a
   * dataset that already holds a slot (re-queued after a lost lease) keeps it.
   */
  def acquireSlot(request: SlotRequest): Effect[Boolean] =
    if !t.exists then missing
    else if t.activeSlots.contains(request.datasetId) then effects.reply(true)
    else if t.queuePaused || t.activeSlots.size >= t.limits.concurrency then effects.reply(false)
    else effects.persist(SlotAcquired(request.datasetId)).thenReply(_ => true)

  def releaseSlot(request: SlotRequest): Effect[Done] =
    if !t.activeSlots.contains(request.datasetId) then effects.reply(Done)
    else effects.persist(SlotReleased(request.datasetId)).thenReply(_ => Done)

  def pauseQueue(request: ByWhom): Effect[Tenant] =
    if !t.exists then missing
    else if t.queuePaused then effects.reply(t)
    else effects.persist(QueuePaused(request.by, request.at)).thenReplyState

  def resumeQueue(request: ByWhom): Effect[Tenant] =
    if !t.exists then missing
    else if !t.queuePaused then effects.reply(t)
    else effects.persist(QueueResumed(request.by, request.at)).thenReplyState

  def saveProfile(profile: Profile): Effect[Profile] =
    if !t.exists then missing
    else if profile.name.length > 60 || profile.description.length > 1000 then
      effects.error("a profile's name is at most 60 characters and its description at most 1000")
    else if profile.id == "standard" || profile.name == "standard" then
      effects.error("the standard profile is read-only", ErrorCode.Conflict)
    else if t.profiles.values.exists(p => p.modelKey == profile.modelKey && p.name == profile.name && p.id != profile.id) then
      effects.error(s"a profile named ${profile.name} already exists", ErrorCode.Conflict)
    else if !t.profiles.contains(profile.id) && t.profiles.values.count(_.modelKey == profile.modelKey) >= 50 then
      effects.error("a model has at most 50 configuration profiles", ErrorCode.Conflict)
    else effects.persist(ProfileSaved(profile)).thenReply(_ => profile)

  def deleteProfile(request: DeleteById): Effect[Done] =
    if !t.profiles.contains(request.id) then effects.error("no such profile", ErrorCode.NotFound)
    else effects.persist(ProfileDeleted(request.id)).thenReply(_ => Done)

  def saveSubscription(subscription: Subscription): Effect[Subscription] =
    if !t.exists then missing
    else effects.persist(SubscriptionSaved(subscription)).thenReply(_ => subscription)

  def deleteSubscription(request: DeleteById): Effect[Done] =
    if !t.subscriptions.contains(request.id) then effects.error("no such subscription", ErrorCode.NotFound)
    else effects.persist(SubscriptionDeleted(request.id)).thenReply(_ => Done)

  /** Whether a `webhook.failing` event may be sent for this subscription now: at most once per two hours. */
  def noteFailingNotified(request: FailingNote): Effect[Boolean] =
    t.subscriptions.get(request.subscriptionId) match
      case None => effects.reply(false)
      case Some(sub) if sub.lastFailingNotifiedAt.exists(_.plusSeconds(7200).isAfter(request.at)) =>
        effects.reply(false)
      case Some(_) => effects.persist(FailingNotified(request.subscriptionId, request.at)).thenReply(_ => true)

  /** A token bucket refilled at `submitPerMinute` per minute; false when empty. New limits start full. */
  def takeSubmitToken(request: At): Effect[Boolean] =
    if !t.exists then missing
    else
      val rate     = t.limits.submitPerMinute.toDouble
      val elapsed  = t.tokensAt.fold(0.0)(last => math.max(0L, request.at.toEpochMilli - last.toEpochMilli) / 60000.0)
      val refilled = math.min(rate, t.tokens + elapsed * rate)
      if refilled < 1.0 then effects.reply(false)
      else effects.persist(SubmitTokenTaken(refilled - 1.0, request.at)).thenReply(_ => true)

object TenantEntity
    extends EventSourcedEntity.Companion[TenantEntity, Tenant, TenantEvent](
      componentId = ComponentId("tenant"),
      stateSerializer = Codecs.serializer[Tenant]("tenant"),
      eventSerializer = Codecs.serializer[TenantEvent]("tenant-event")
    ):

  given Serializer[CreateTenant]  = Codecs.serializer[CreateTenant]("create-tenant")
  given Serializer[MemberChange]  = Codecs.serializer[MemberChange]("member-change")
  given Serializer[RemoveMember]  = Codecs.serializer[RemoveMember]("remove-member")
  given Serializer[CreateKey]     = Codecs.serializer[CreateKey]("create-key")
  given Serializer[RevokeKey]     = Codecs.serializer[RevokeKey]("revoke-key")
  given Serializer[KeyRecord]     = Codecs.serializer[KeyRecord]("key-record")
  given Serializer[Limits]        = Codecs.serializer[Limits]("limits")
  given Serializer[SlotRequest]   = Codecs.serializer[SlotRequest]("slot-request")
  given Serializer[ByWhom]        = Codecs.serializer[ByWhom]("by-whom")
  given Serializer[Profile]       = Codecs.serializer[Profile]("profile")
  given Serializer[DeleteById]    = Codecs.serializer[DeleteById]("delete-by-id")
  given Serializer[Subscription]  = Codecs.serializer[Subscription]("subscription")
  given Serializer[FailingNote]   = Codecs.serializer[FailingNote]("failing-note")
  given Serializer[At]            = Codecs.serializer[At]("at")

  def create(context: EventSourcedEntityContext) = new TenantEntity(context)

  val create             = command("create")(_.create)
  val get                = query("get")(_.get)
  val addMember          = command("add-member")(_.addMember)
  val changeMemberRole   = command("change-member-role")(_.changeMemberRole)
  val removeMember       = command("remove-member")(_.removeMember)
  val createKey          = command("create-key")(_.createKey)
  val revokeKey          = command("revoke-key")(_.revokeKey)
  val setLimits          = command("set-limits")(_.setLimits)
  val acquireSlot        = command("acquire-slot")(_.acquireSlot)
  val releaseSlot        = command("release-slot")(_.releaseSlot)
  val pauseQueue         = command("pause-queue")(_.pauseQueue)
  val resumeQueue        = command("resume-queue")(_.resumeQueue)
  val saveProfile        = command("save-profile")(_.saveProfile)
  val deleteProfile      = command("delete-profile")(_.deleteProfile)
  val saveSubscription   = command("save-subscription")(_.saveSubscription)
  val deleteSubscription = command("delete-subscription")(_.deleteSubscription)
  val noteFailingNotified = command("note-failing-notified")(_.noteFailingNotified)
  val takeSubmitToken    = command("take-submit-token")(_.takeSubmitToken)
