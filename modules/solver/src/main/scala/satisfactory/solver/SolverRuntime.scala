package satisfactory.solver

import com.thinkmorestupidless.ankka.runtime.{AnkkaService, RuntimeExtension}
import com.typesafe.config.Config
import org.slf4j.LoggerFactory
import satisfactory.models.employeescheduling.EmployeeScheduling
import satisfactory.models.vehiclerouting.VehicleRouting
import satisfactory.protocol.Ids
import satisfactory.runner.{ControlChannel, Worker, WorkerConfig}
import satisfactory.spi.ModelCatalog

import scala.concurrent.duration.*

/** The `satisfactory.solver` block, and the environment a deployment sets. */
final case class SolverSettings(
    /**
     * Set: `api` over plain HTTP at this address (a laptop, a test). Empty: the ankka service
     * `apiService`.
     */
    apiUrl: String,
    apiService: String,
    runnerToken: String,
    workerId: String,
    slots: Int,
    heartbeat: FiniteDuration,
    reportInterval: FiniteDuration
)

object SolverSettings:
  def from(config: Config): SolverSettings =
    val c     = config.getConfig("satisfactory.solver")
    val cores = Runtime.getRuntime.availableProcessors()
    SolverSettings(
      apiUrl = c.getString("api-url").trim,
      apiService = c.getString("api-service"),
      runnerToken = c.getString("runner-token"),
      workerId = sys.env.get("HOSTNAME").filter(_.nonEmpty).getOrElse(s"solver-${Ids.ulid()}"),
      slots = if c.getInt("slots") > 0 then c.getInt("slots") else math.max(1, cores - 1),
      heartbeat = FiniteDuration(c.getDuration("heartbeat").toMillis, MILLISECONDS),
      reportInterval = FiniteDuration(c.getDuration("report-interval").toMillis, MILLISECONDS)
    )

/**
 * The worker as an ankka runtime extension: `start` runs once the node has joined its cluster, and
 * `stop` is the rolling-deploy drain — every solve stops, reports its best and releases its lease
 * inside the grace period (DESIGN.md §6). Solving happens on Timefold's own threads, one per slot
 * (cores − 1: the spare core keeps cluster heartbeats on time), never on Pekko's dispatchers.
 */
final class SolverRuntime(
    settings: SolverSettings,
    catalog: ModelCatalog,
    channel: AnkkaService => ControlChannel
) extends RuntimeExtension:
  private val log                              = LoggerFactory.getLogger("satisfactory.solver")
  @volatile private var worker: Option[Worker] = None

  def name: String = "solver"

  def start(service: AnkkaService): Unit =
    val w = Worker(
      WorkerConfig(
        settings.workerId,
        slots = settings.slots,
        minReportInterval = settings.reportInterval,
        heartbeat = settings.heartbeat
      ),
      catalog,
      channel(service),
      line => log.info(line)
    )
    w.start()
    worker = Some(w)

  override def stop(): Unit = worker.foreach(_.stop())

  override def readiness: Option[() => Boolean] = Some(() => worker.isDefined)

object SolverRuntime:
  def catalog: ModelCatalog = ModelCatalog.of(EmployeeScheduling.V1, VehicleRouting.V1)

  /**
   * How `api` is reached: by URL over plain HTTP when one is configured, otherwise as the ankka
   * service of that name, called as this service — mutual TLS in a cluster, where nothing else gets
   * onto a service's port, and the local registry on a laptop.
   */
  def channel(settings: SolverSettings): AnkkaService => ControlChannel =
    if settings.apiUrl.nonEmpty then _ => HttpControlChannel(settings.apiUrl, settings.runnerToken)
    else
      service => ServiceControlChannel(service.services(settings.apiService), settings.runnerToken)
