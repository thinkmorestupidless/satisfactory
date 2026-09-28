package satisfactory.api.domain

import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.{ComponentClient, TimerScheduler}
import satisfactory.api.Settings
import satisfactory.api.api.ApiKeys
import satisfactory.api.blobs.BlobStore
import satisfactory.spi.ModelCatalog

import java.time.{Clock, Instant}

/**
 * What every endpoint and domain service is built from. The blob store and the timer scheduler exist
 * only once the service has started, so they are functions.
 */
final class ApiContext(
    val client: ComponentClient,
    val views: ViewClient,
    val catalog: ModelCatalog,
    val settings: Settings,
    blobStore: () => BlobStore,
    timerScheduler: () => TimerScheduler,
    val clock: Clock = Clock.systemUTC()
):
  def blobs: BlobStore           = blobStore()
  def timers: TimerScheduler     = timerScheduler()
  def now(): Instant             = clock.instant()
  lazy val keys: ApiKeys         = ApiKeys(client)
  lazy val runner: RunnerService = RunnerService(this)
  lazy val submissions: Submissions = Submissions(this)
  lazy val derivations: Derivations = Derivations(this)

  /** The platform API's token verifier; absent until an identity provider is configured. */
  lazy val verifier: Option[satisfactory.api.auth.TokenVerifier] =
    for
      issuer <- settings.authIssuer
      jwks   <- settings.authJwksUrl
    yield satisfactory.api.auth.TokenVerifier(issuer, jwks)

  /** Records an operator action, attributed. */
  def audit(subject: String, action: String, target: String): Unit =
    client
      .forEventSourcedEntity(com.thinkmorestupidless.ankka.core.EntityId(satisfactory.api.application.AuditEntity.Id))
      .call(satisfactory.api.application.AuditEntity.record)
      .invoke(satisfactory.api.application.OperatorAction(subject, action, target, now())): Unit
