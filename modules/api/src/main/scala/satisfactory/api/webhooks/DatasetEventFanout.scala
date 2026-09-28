package satisfactory.api.webhooks

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import satisfactory.api.application.*
import satisfactory.api.blobs.ApiServices
import satisfactory.protocol.*

import java.time.Instant
import scala.util.Try

/**
 * A dataset's final event, to every subscription of its tenant that wants it (API.md §2.7): one
 * delivery workflow per subscription, under an id derived from the event, so a redelivered change
 * starts nothing new. Filters (status, name pattern, tags, model) apply to `dataset.*` events, and a
 * subscription hears only events that happen after it was made.
 */
final class DatasetEventFanout(context: ConsumerContext) extends Consumer[satisfactory.api.application.DatasetEvent, Nothing]:
  import satisfactory.api.application.DatasetEvent.*

  private val client = context.componentClient

  def onMessage(event: satisfactory.api.application.DatasetEvent): Effect = event match
    case _: Finished | _: Failed | _: Invalidated | _: Computed => fanOut(messageContext.subject)
    case _                                                      => effects.ignore()

  private def fanOut(datasetId: String): Effect =
    val d = client.forEventSourcedEntity(EntityId(datasetId)).call(DatasetEntity.get).invoke()
    EventTypes.forStatus(d.status).filter(_ => d.isFinal) match
      case None => effects.ignore()
      case Some(eventType) =>
        val spec   = d.spec.get
        val tenant = client.forEventSourcedEntity(EntityId(spec.tenantId)).call(TenantEntity.get).invoke()
        val link   = s"${ApiServices.settings.fold("")(_.publicUrl)}/api/models/${spec.modelId}/${spec.modelVersion}/${spec.entity}/${d.id}"
        val event = satisfactory.protocol.DatasetEvent(
          s"evt_${d.id}_${d.seq}",
          eventType,
          d.shutdown.orElse(d.completed).getOrElse(Instant.now()),
          spec.tenantId,
          DatasetEventData(d.id, d.name, spec.modelId, spec.modelVersion, d.tags, d.status, d.metadata.score, d.seq,
            spec.parentId, spec.originId, d.supersededBy, link, link)
        )
        val body = RawJson(WireCodecs.encode(event)(using WebhookCodecs.datasetEvent))
        // A subscription hears what happens after it exists: the consumer runs behind, so it would
        // otherwise see subscriptions made after the event.
        tenant.subscriptions.values
          .filter(s => !s.createdAt.isAfter(event.occurredAt))
          .filter(s => EventTypes.matches(s.events, eventType) && DatasetEventFanout.passes(s.filters, d, spec.modelKey))
          .foreach(s => Fanout.start(client, spec.tenantId, s, event.id, eventType, Some(d.id), body, Instant.now()))
        effects.done()

object DatasetEventFanout
    extends Consumer.Companion[DatasetEventFanout, satisfactory.api.application.DatasetEvent, Nothing](
      componentId = ComponentId("dataset-event-fanout"),
      source = ChangeSource.eventsOf(DatasetEntity)
    ):
  def create(ctx: ConsumerContext) = new DatasetEventFanout(ctx)

  def passes(filters: SubscriptionFilters, d: Dataset, modelKey: String): Boolean =
    filters.status.forall(_.contains(d.status)) &&
      filters.model.forall(m => m == modelKey || m == d.spec.fold("")(_.modelId)) &&
      filters.tags.forall(_.forall(d.tags.contains)) &&
      filters.nameRegex.forall(r => d.name.exists(n => Try(n.matches(r)).getOrElse(false)))
