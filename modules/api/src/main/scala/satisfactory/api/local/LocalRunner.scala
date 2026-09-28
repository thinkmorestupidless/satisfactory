package satisfactory.api.local

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.runtime.{AnkkaService, RuntimeExtension}
import org.slf4j.LoggerFactory
import satisfactory.api.domain.{ApiContext, RunnerService}
import satisfactory.protocol.*
import satisfactory.runner.{ChannelError, ControlChannel, Worker, WorkerConfig}
import satisfactory.spi.ModelCatalog

import scala.util.control.NonFatal

/**
 * `ControlChannel.Local`: the runner protocol as direct calls into the runner service, so `api`
 * alone is the whole system (DESIGN.md §2) — `sbt api/run`, and every in-JVM HTTP suite.
 */
final class LocalControlChannel(runner: RunnerService) extends ControlChannel:

  private def call[A](body: => A): Either[ChannelError, A] =
    try Right(body)
    catch
      case e: CommandError if e.code == ErrorCode.Conflict => Left(ChannelError.Stale(e.message))
      case e: CommandError if e.code == ErrorCode.NotFound => Left(ChannelError.Stale(e.message))
      case e: CommandError                                 => Left(ChannelError.Rejected(400, e.message))
      case NonFatal(e)                                     => Left(ChannelError.Unavailable(String.valueOf(e.getMessage)))

  def register(workerId: String, registration: WorkerRegistration) = call(runner.register(workerId, registration))
  def deregister(workerId: String)                                 = call(runner.deregister(workerId))
  def claim(request: ClaimRequest)                                 = call(runner.claim(request))
  def phase(datasetId: String, request: PhaseRequest)              = call(runner.phase(datasetId, request))
  def report(datasetId: String, request: ReportRequest)            = call(runner.report(datasetId, request))
  def heartbeat(datasetId: String, request: HeartbeatRequest)      = call(runner.heartbeat(datasetId, request))
  def complete(datasetId: String, request: CompleteRequest)        = call(runner.complete(datasetId, request))
  def fail(datasetId: String, request: FailRequest)                = call(runner.fail(datasetId, request))
  def release(datasetId: String, request: ReleaseRequest)          = call(runner.release(datasetId, request))

  def getBlob(ref: String) =
    call(runner.getBlob(ref)).flatMap(_.map(_.bytes).toRight(ChannelError.Rejected(404, s"no blob $ref")))

  def putBlob(ref: String, contentType: String, bytes: Array[Byte]) = call(runner.putBlob(ref, contentType, bytes))

/**
 * Starts a worker inside `api` once the service is up, when `satisfactory.local-runner` is on. Its
 * solves run on Timefold's own threads, never on Pekko's dispatchers.
 */
final class LocalRunner(context: () => ApiContext, catalog: ModelCatalog, slots: Int) extends RuntimeExtension:
  private val log                         = LoggerFactory.getLogger("satisfactory.local-runner")
  @volatile private var worker: Option[Worker] = None

  def name: String = "local-runner"

  def start(service: AnkkaService): Unit =
    val w = Worker(
      WorkerConfig(s"local-${Ids.ulid()}", slots = slots),
      catalog,
      LocalControlChannel(context().runner),
      line => log.info(line)
    )
    w.start()
    worker = Some(w)

  override def stop(): Unit = worker.foreach(_.stop())

  override def readiness: Option[() => Boolean] = Some(() => worker.isDefined)
