package satisfactory.api.application

import com.thinkmorestupidless.ankka.core.{CommandError, Codecs, ComponentId, EntityId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import satisfactory.api.blobs.ApiServices

import java.time.Instant
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

final case class LeaseTimer(datasetId: String, epoch: Long)
final case class DatasetRef(datasetId: String)

/**
 * The time-driven half of a dataset's lifecycle (research R7). Each handler asks the entity, which
 * decides; "nothing left to do" is success, because a failing handler is retried forever.
 */
final class DatasetTimers(context: TimedActionContext) extends TimedAction:
  private val client = context.componentClient

  private def dataset(id: String) = client.forEventSourcedEntity(EntityId(id))

  private def maxAttempts = ApiServices.settings.fold(3)(_.maxAttempts)

  private def guarded(work: => Unit): Effect =
    try
      work
      effects.done()
    catch
      case e: CommandError if e.code == ErrorCode.NotFound => effects.done()
      case NonFatal(e) => effects.error(Option(e.getMessage).getOrElse(e.toString))

  def expireLease(timer: LeaseTimer): Effect = guarded {
    val holder = dataset(timer.datasetId).call(DatasetEntity.get).invoke().lease.filter(_.epoch == timer.epoch).map(_.workerId)
    dataset(timer.datasetId).call(DatasetEntity.expireLease).invoke(ExpireLease(timer.epoch, maxAttempts, Instant.now())): Unit
    if holder.isDefined then
      satisfactory.api.metrics.Counters.leaseLosses.incrementAndGet(): Unit
      satisfactory.api.metrics.Counters.requeues.incrementAndGet(): Unit
    holder.foreach(w =>
      client.forKeyValueEntity(EntityId(w)).call(WorkerEntity.holds).invoke(WorkerHolds(timer.datasetId, holding = false)): Unit
    )
  }

  def expireLifetime(ref: DatasetRef): Effect = guarded {
    dataset(ref.datasetId).call(DatasetEntity.expireLifetime).invoke(At(Instant.now())): Unit
  }

  /** Retention is over: the bodies are deleted for good, then the dataset records that it expired. */
  def expireRetention(ref: DatasetRef): Effect = guarded {
    val expired = dataset(ref.datasetId).call(DatasetEntity.expireRetention).invoke(At(Instant.now()))
    if expired then ApiServices.blobs.deleteAll(ref.datasetId): Unit
  }

object DatasetTimers extends TimedAction.Companion[DatasetTimers](ComponentId("dataset-timers")):
  given Serializer[LeaseTimer] = Codecs.serializer[LeaseTimer]("lease-timer")
  given Serializer[DatasetRef] = Codecs.serializer[DatasetRef]("dataset-ref")

  def create(ctx: TimedActionContext) = new DatasetTimers(ctx)

  val expireLease     = handler("expire-lease")(_.expireLease)
  val expireLifetime  = handler("expire-lifetime")(_.expireLifetime)
  val expireRetention = handler("expire-retention")(_.expireRetention)

  def leaseName(datasetId: String): String     = s"lease-$datasetId"
  def lifetimeName(datasetId: String): String  = s"lifetime-$datasetId"
  def retentionName(datasetId: String): String = s"retention-$datasetId"

  /** Armed on every lease and re-armed on every heartbeat: fires only when heartbeats have stopped. */
  def armLease(timers: TimerScheduler, datasetId: String, epoch: Long, ttl: FiniteDuration): Unit =
    timers.createSingleTimer(leaseName(datasetId), ttl, expireLease.deferred(LeaseTimer(datasetId, epoch)))

  def armLifetime(timers: TimerScheduler, datasetId: String, ceiling: FiniteDuration): Unit =
    timers.createSingleTimer(lifetimeName(datasetId), ceiling, expireLifetime.deferred(DatasetRef(datasetId)))

  def armRetention(timers: TimerScheduler, datasetId: String, period: FiniteDuration): Unit =
    timers.createSingleTimer(retentionName(datasetId), period, expireRetention.deferred(DatasetRef(datasetId)))
