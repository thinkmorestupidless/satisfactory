package satisfactory.api

import com.thinkmorestupidless.ankka.http.{EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, AnkkaService, ClusterConfig, ProjectionRuntime, RuntimeExtension, TimerRuntime}
import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import satisfactory.api.api.*
import satisfactory.api.application.*
import satisfactory.api.blobs.{ApiServices, BlobStoreExtension}
import satisfactory.api.domain.ApiContext
import satisfactory.api.local.LocalRunner
import satisfactory.models.employeescheduling.EmployeeScheduling
import satisfactory.models.vehiclerouting.VehicleRouting
import satisfactory.spi.ModelCatalog

import scala.jdk.CollectionConverters.*

/**
 * The api service: every component it hosts and every extension it runs, listed explicitly — there
 * is no classpath scanning, so this is the complete inventory.
 */
object ApiService:

  def catalog: ModelCatalog = ModelCatalog.of(EmployeeScheduling.V1, VehicleRouting.V1)

  val components: Seq[ComponentDescriptor] = Seq(
    DatasetEntity.descriptor,
    TenantEntity.descriptor,
    TenantRows.descriptor,
    ApiKeyEntity.descriptor,
    IdempotencyEntity.descriptor,
    WorkerEntity.descriptor,
    WorkerRows.descriptor,
    AuditEntity.descriptor,
    satisfactory.api.webhooks.WebhookDeliveryWorkflow.descriptor,
    satisfactory.api.webhooks.WebhookDeliveryLog.descriptor,
    satisfactory.api.webhooks.WebhookDeliveryRows.descriptor,
    satisfactory.api.webhooks.DatasetEventFanout.descriptor,
    DatasetRows.descriptor,
    DatasetTimers.descriptor,
    DatasetReactions.descriptor
  )

  /** The endpoints, each built from the shared context once the HTTP server hands over its clients. */
  def endpoints(catalog: ModelCatalog, ctx: EndpointClients => ApiContext): Seq[EndpointClients => HttpEndpoint] =
    catalog.all().asScala.toSeq.map(runtime => (c: EndpointClients) => ModelsEndpoint(runtime, ctx(c))) ++
      Seq[EndpointClients => HttpEndpoint](
        c => RunnerEndpoint(ctx(c)),
        c => AboutMeEndpoint(ctx(c)),
        c => OpsEndpoint(ctx(c)),
        c => satisfactory.api.api.WebhooksEndpoint(ctx(c)),
        c => MetricsEndpoint(ctx(c))
      )

  /**
   * The extensions in start order: projections and timers before anything uses them, the blob store
   * before the HTTP server, the in-process runner last.
   */
  final class Wiring(
      settings: Settings,
      catalog: ModelCatalog,
      server: Seq[EndpointClients => HttpEndpoint] => HttpServer
  ):
    val timers = TimerRuntime()
    val blobs  = BlobStoreExtension(settings.blobs)
    @volatile private var context: Option[ApiContext] = None

    ApiServices.settings = Some(settings)

    def current: ApiContext = context.getOrElse(throw IllegalStateException("the api has not started"))

    def contextFor(
        client: com.thinkmorestupidless.ankka.sdk.ComponentClient,
        views: com.thinkmorestupidless.ankka.runtime.ViewClient
    ): ApiContext = synchronized {
      context.getOrElse {
        val c = ApiContext(client, views, catalog, settings, () => blobs.store, () => timers.timerScheduler)
        context = Some(c)
        c
      }
    }

    private def published: RuntimeExtension = new RuntimeExtension:
      def name = "api-services"
      def start(service: AnkkaService): Unit =
        ApiServices.timers = Some(timers.timerScheduler)
        val _ = contextFor(service.componentClient, service.viewClient)

    val http: HttpServer =
      server(ApiService.endpoints(catalog, clients => contextFor(clients.componentClient, clients.viewClient)))

    def extensions: Seq[RuntimeExtension] =
      Seq(ProjectionRuntime(), timers, blobs, published, http, Bootstrap(settings)) ++
        Option.when(settings.localRunner)(LocalRunner(() => current, catalog, settings.localSlots))

@main def run(): Unit =
  val config   = ClusterConfig.load()
  val settings = Settings.from(config)
  val wiring   = ApiService.Wiring(settings, ApiService.catalog, factories => HttpServer.of(factories*))
  val service = Ankka.service
    .registerAll(ApiService.components)
    .pipe(builder => wiring.extensions.foldLeft(builder)(_.withExtension(_)))
    .start("satisfactory-api", config)
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit

extension [A](a: A) private def pipe[B](f: A => B): B = f(a)
