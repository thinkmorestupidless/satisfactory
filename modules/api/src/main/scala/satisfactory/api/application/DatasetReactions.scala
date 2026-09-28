package satisfactory.api.application

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import satisfactory.api.blobs.ApiServices

import scala.concurrent.duration.*

/**
 * What follows from a dataset finishing, kept out of the entity: its tenant's concurrency slot is
 * released, its lease and lifetime timers are cancelled, and its retention timer is armed.
 * At-least-once, and every step is idempotent.
 *
 * A dataset that loses its lease keeps its slot: it is still the tenant's work in flight, and
 * releasing it here could race the re-claim that re-acquires it.
 */
final class DatasetReactions(context: ConsumerContext) extends Consumer[DatasetEvent, Nothing]:
  import DatasetEvent.*

  private val client = context.componentClient

  def onMessage(event: DatasetEvent): Effect = event match
    case _: Finished | _: Failed | _: Invalidated | _: Computed => settle(messageContext.subject)
    case _                                                      => effects.ignore()

  private def settle(datasetId: String): Effect =
    val state = client.forEventSourcedEntity(EntityId(datasetId)).call(DatasetEntity.get).invoke()
    if !state.isFinal then effects.ignore()
    else
      client.forEventSourcedEntity(EntityId(state.tenantId)).call(TenantEntity.releaseSlot).invoke(SlotRequest(datasetId)): Unit
      ApiServices.timers.foreach { timers =>
        timers.delete(DatasetTimers.leaseName(datasetId))
        timers.delete(DatasetTimers.lifetimeName(datasetId))
        val retention =
          try client.forEventSourcedEntity(EntityId(state.tenantId)).call(TenantEntity.get).invoke().limits.retentionSeconds.seconds
          catch case scala.util.control.NonFatal(_) => ApiServices.settings.fold(30.days)(_.defaultRetention)
        if !timers.exists(DatasetTimers.retentionName(datasetId)) then
          DatasetTimers.armRetention(timers, datasetId, retention)
      }
      effects.done()

object DatasetReactions
    extends Consumer.Companion[DatasetReactions, DatasetEvent, Nothing](
      componentId = ComponentId("dataset-reactions"),
      source = ChangeSource.eventsOf(DatasetEntity)
    ):
  def create(ctx: ConsumerContext) = new DatasetReactions(ctx)
