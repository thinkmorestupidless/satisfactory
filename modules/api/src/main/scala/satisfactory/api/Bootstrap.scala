package satisfactory.api

import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.runtime.{AnkkaService, RuntimeExtension}
import org.slf4j.LoggerFactory
import satisfactory.api.api.Hashing
import satisfactory.api.application.*

import java.time.Instant

/**
 * Development only: a tenant `t_local` with a read-write key whose plaintext is
 * `satisfactory.bootstrap-key`, so `sbt api/run` can be used with curl at once. Refuses to run when
 * the platform API's identity provider is configured — a deployment creates tenants through it.
 */
final class Bootstrap(settings: Settings) extends RuntimeExtension:
  private val log = LoggerFactory.getLogger("satisfactory.bootstrap")

  def name: String = "bootstrap"

  def start(service: AnkkaService): Unit =
    settings.bootstrapKey.foreach { key =>
      if settings.authIssuer.isDefined then
        log.warn("satisfactory.bootstrap-key is ignored: an identity provider is configured")
      else Bootstrap.seed(service.componentClient, settings, "t_local", "local", "k_local", key)
    }

object Bootstrap:
  def limits(settings: Settings): Limits =
    Limits(
      settings.defaultConcurrency,
      settings.defaultSubmitPerMinute,
      settings.defaultLifetimeCeiling.toSeconds,
      settings.defaultRetention.toSeconds
    )

  /** Creates the tenant and a key if they are not there; safe to run on every start. */
  def seed(
      client: com.thinkmorestupidless.ankka.sdk.ComponentClient,
      settings: Settings,
      tenantId: String,
      name: String,
      keyId: String,
      plaintext: String,
      role: String = Roles.ReadWrite,
      limits: Option[Limits] = None
  ): Unit =
    val tenant = client.forEventSourcedEntity(EntityId(tenantId))
    try tenant.call(TenantEntity.create).invoke(CreateTenant(name, None, limits.getOrElse(Bootstrap.limits(settings)), "bootstrap", Instant.now())): Unit
    catch case e: CommandError if e.code == ErrorCode.Conflict => ()
    try
      client
        .forKeyValueEntity(EntityId(Hashing.sha256(plaintext)))
        .call(ApiKeyEntity.create)
        .invoke(ApiKey(tenantId, keyId, role, revoked = false)): Unit
      tenant.call(TenantEntity.createKey).invoke(CreateKey(keyId, "bootstrap", role, Instant.now(), Hashing.sha256(plaintext))): Unit
      LoggerFactory.getLogger("satisfactory.bootstrap").info(s"tenant $tenantId has key $keyId ($role)")
    catch case e: CommandError if e.code == ErrorCode.Conflict => ()
