package satisfactory.api

import com.typesafe.config.Config

import scala.concurrent.duration.*

/** The `satisfactory` block of the service's configuration, read once at startup. */
final case class Settings(
    runnerToken: String,
    secretKey: String,
    localRunner: Boolean,
    localSlots: Int,
    bootstrapKey: Option[String],
    blobs: String,
    publicUrl: String,
    leaseTtl: FiniteDuration,
    heartbeat: FiniteDuration,
    maxAttempts: Int,
    claimWait: FiniteDuration,
    defaultConcurrency: Int,
    defaultSubmitPerMinute: Int,
    defaultLifetimeCeiling: FiniteDuration,
    defaultRetention: FiniteDuration,
    authIssuer: Option[String],
    authJwksUrl: Option[String],
    operatorRole: String,
    metricsToken: Option[String],
    webhookTimeout: FiniteDuration,
    webhookRetryInterval: FiniteDuration,
    webhookMaxAttempts: Int
)

object Settings:
  private def duration(c: Config, path: String): FiniteDuration =
    FiniteDuration(c.getDuration(path).toMillis, MILLISECONDS)

  private def optional(c: Config, path: String): Option[String] =
    Option(c.getString(path)).map(_.trim).filter(_.nonEmpty)

  def from(root: Config): Settings =
    val c = root.getConfig("satisfactory")
    Settings(
      runnerToken = c.getString("runner-token"),
      secretKey = c.getString("secret-key"),
      localRunner = c.getBoolean("local-runner"),
      localSlots = c.getInt("local-slots"),
      bootstrapKey = optional(c, "bootstrap-key"),
      blobs = c.getString("blobs"),
      publicUrl = c.getString("public-url").stripSuffix("/"),
      leaseTtl = duration(c, "lease-ttl"),
      heartbeat = duration(c, "heartbeat"),
      maxAttempts = c.getInt("max-attempts"),
      claimWait = duration(c, "claim-wait"),
      defaultConcurrency = c.getInt("defaults.concurrency"),
      defaultSubmitPerMinute = c.getInt("defaults.submit-per-minute"),
      defaultLifetimeCeiling = duration(c, "defaults.lifetime-ceiling"),
      defaultRetention = duration(c, "defaults.retention"),
      authIssuer = optional(c, "auth.issuer"),
      authJwksUrl = optional(c, "auth.jwks-url"),
      operatorRole = c.getString("auth.operator-role"),
      metricsToken = optional(c, "metrics-token"),
      webhookTimeout = duration(c, "webhooks.timeout"),
      webhookRetryInterval = duration(c, "webhooks.retry-interval"),
      webhookMaxAttempts = c.getInt("webhooks.max-attempts")
    )
