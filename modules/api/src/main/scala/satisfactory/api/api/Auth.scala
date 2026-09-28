package satisfactory.api.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Principal, RequestContext}
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import satisfactory.api.application.{ApiKey, ApiKeyEntity, Roles}

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*

/** Who a model-API request is: a key of a tenant, with its role. */
final case class TenantCaller(tenantId: String, keyId: String, role: String):
  def canWrite: Boolean = role == Roles.ReadWrite

  def requireWrite(): Unit =
    if !canWrite then throw ApiError.forbidden("this key is read-only")

object TenantCaller:
  def of(principal: Principal): TenantCaller =
    TenantCaller(principal.claims.getOrElse("tenant", ""), principal.subject, principal.roles.headOption.getOrElse(""))

object Hashing:
  def sha256(text: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF_8)))

  /** Equal in time independent of where they differ. */
  def constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.getBytes(UTF_8), b.getBytes(UTF_8))

  private val random = SecureRandom()

  def newApiKey(): String =
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    "sk_" + java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

/**
 * `X-API-KEY` → SHA-256 → the key's entity (research R8). A 5 s cache in front of the entity bounds
 * the cost to one lookup per key per 5 s; revocation is exact on the entity and at most 5 s late
 * through the cache.
 */
final class ApiKeys(client: ComponentClient, ttl: FiniteDuration = 5.seconds, maxEntries: Int = 10_000):
  private val cache = ConcurrentHashMap[String, (ApiKey, Long)]()

  def lookup(plaintext: String): Option[ApiKey] =
    val hash = Hashing.sha256(plaintext)
    val now  = System.nanoTime()
    val key = Option(cache.get(hash)) match
      case Some((key, expires)) if expires > now => key
      case _ =>
        val fresh = client.forKeyValueEntity(EntityId(hash)).call(ApiKeyEntity.get).invoke()
        if cache.size >= maxEntries then cache.clear()
        cache.put(hash, (fresh, now + ttl.toNanos)): Unit
        fresh
    Option.when(key.exists && !key.revoked)(key)

  def forget(plaintext: String): Unit = cache.remove(Hashing.sha256(plaintext)): Unit

  def acl: Acl = Acl.Authenticate(decide)

  private def decide(request: RequestContext): AuthDecision =
    request.header("X-API-KEY").map(_.trim).filter(_.nonEmpty) match
      case None => AuthDecision.Unauthenticated("realm=\"satisfactory\", error=\"missing X-API-KEY\"")
      case Some(plaintext) =>
        try
          lookup(plaintext) match
            case Some(key) =>
              AuthDecision.Allow(Principal(key.keyId, roles = Set(key.role), claims = Map("tenant" -> key.tenantId)))
            case None => AuthDecision.Unauthenticated("realm=\"satisfactory\", error=\"invalid X-API-KEY\"")
        catch case scala.util.control.NonFatal(e) => AuthDecision.Unavailable(s"key lookup failed: ${e.getMessage}")

/** The runner routes (`/internal/…`): the runner token from the shared secret, compared in constant time. */
object RunnerAuth:
  def acl(token: String): Acl = Acl.AllowIf(request =>
    request.header("X-Runner-Token").exists(presented => Hashing.constantTimeEquals(presented, token))
  )
