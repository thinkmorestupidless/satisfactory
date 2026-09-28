package satisfactory.runner

import satisfactory.protocol.*
import satisfactory.spi.ModelCatalog

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.control.NonFatal

/**
 * A worker: one claim loop per slot, each on a virtual thread, pulling work when it has room. There
 * is no scheduler to get wrong: a busy worker simply does not ask (DESIGN.md §2).
 *
 * Two ways to stop taking work: `shutdown` (SIGTERM — release everything and exit) and an operator's
 * drain, which the API relays in every reply (release, keep running, resume when undrained).
 */
final class Worker(
    config: WorkerConfig,
    catalog: ModelCatalog,
    channel: ControlChannel,
    log: String => Unit = _ => ()
):
  private val running        = AtomicBoolean(false)
  private val shuttingDown   = AtomicBoolean(false)
  private val operatorDrain  = AtomicBoolean(false)
  private val busy           = AtomicInteger(0)
  @volatile private var threads: Vector[Thread] = Vector.empty

  val models: List[String] = catalog.keys().asScala.toList

  def isDraining: Boolean = shuttingDown.get() || operatorDrain.get()

  /** Why sessions should hand their work back, if they should. */
  private def stopReason: Option[String] =
    if shuttingDown.get() then Some(ReleaseReason.Shutdown)
    else if operatorDrain.get() then Some(ReleaseReason.Drained)
    else None
  def busySlots: Int      = busy.get()

  def start(): Unit =
    if running.compareAndSet(false, true) then
      catalog.all().asScala.foreach(_.startSolving(config.slots))
      registerOnce()
      val loops = (1 to config.slots).map(slot => Thread.ofVirtual().name(s"slot-$slot").start(() => loop(slot)))
      val beat  = Thread.ofVirtual().name("registration").start(() => registrationLoop())
      threads = loops.toVector :+ beat
      log(s"worker ${config.workerId} started: ${config.slots} slot(s), models ${models.mkString(", ")}")

  /**
   * SIGTERM: every session stops its solver, reports its best and releases its lease; then the worker
   * deregisters. Returns when everything has been handed back or `drainTimeout` has passed.
   */
  def stop(): Unit =
    if running.get() then
      shuttingDown.set(true)
      val deadline = System.nanoTime() + config.drainTimeout.toNanos
      threads.foreach { t =>
        val remaining = math.max(1L, (deadline - System.nanoTime()) / 1_000_000L)
        t.join(remaining)
      }
      running.set(false)
      threads.foreach(_.interrupt())
      try channel.deregister(config.workerId): Unit
      catch case NonFatal(_) => ()
      catalog.all().asScala.foreach(_.close())
      log(s"worker ${config.workerId} stopped")

  /**
   * Stops dead, as a crash would: no release, no deregistration; the leases it held expire and their
   * datasets are re-queued warm by whoever claims them next. For recovery tests.
   */
  def halt(): Unit =
    running.set(false)
    shuttingDown.set(true)
    threads.foreach(_.interrupt())
    catalog.all().asScala.foreach(_.close())

  private def loop(slot: Int): Unit =
    while running.get() && !shuttingDown.get() do
      if operatorDrain.get() then sleep(config.claimBackoff.toMillis)
      else
        try
          channel.claim(ClaimRequest(config.workerId, models)) match
            case Right(Some(claim)) =>
              busy.incrementAndGet(): Unit
              try runSession(claim)
              finally busy.decrementAndGet(): Unit
            case Right(None) => sleep(config.claimBackoff.toMillis)
            case Left(error) =>
              log(s"slot $slot: claim failed: ${error.describe}")
              sleep(config.claimBackoff.toMillis * 2)
        catch
          case _: InterruptedException => ()
          case NonFatal(error) =>
            log(s"slot $slot: ${error.getMessage}")
            sleep(config.claimBackoff.toMillis * 2)

  private def runSession(claim: ClaimResponse): Unit =
    if claim.drain then operatorDrain.set(true)
    catalog.byKey(claim.spec.modelKey).toScala match
      case Some(model) =>
        val outcome = SolveSession(claim, model, channel, config, () => stopReason, log).run()
        log(s"${claim.datasetId}: $outcome")
      case None =>
        channel.fail(claim.datasetId, FailRequest(claim.epoch, s"this worker has no model ${claim.spec.modelKey}")): Unit

  private def registrationLoop(): Unit =
    while running.get() && !shuttingDown.get() do
      sleep(config.registrationInterval.toMillis)
      if running.get() && !shuttingDown.get() then registerOnce()

  private def registerOnce(): Unit =
    try
      channel.register(config.workerId, WorkerRegistration(models, config.slots)) match
        case Right(ack) =>
          if ack.drain != operatorDrain.get() then log(s"operator drain: ${ack.drain}")
          operatorDrain.set(ack.drain)
        case Left(error) => log(s"registration failed: ${error.describe}")
    catch case NonFatal(error) => log(s"registration failed: ${error.getMessage}")

  private def sleep(millis: Long): Unit =
    try Thread.sleep(millis)
    catch case _: InterruptedException => ()
