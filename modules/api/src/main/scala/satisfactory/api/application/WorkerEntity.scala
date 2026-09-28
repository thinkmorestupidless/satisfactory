package satisfactory.api.application

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Done, Serializer}
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant

/**
 * A worker as the API knows it: what it solves, how many slots, what it holds, and whether an operator
 * has asked it to drain. A pull protocol has no other record of its workers (Story 10).
 */
final case class WorkerState(
    workerId: String,
    models: List[String],
    slots: Int,
    busy: Set[String],
    draining: Boolean,
    lastSeenAt: Option[Instant]
):
  def exists: Boolean = lastSeenAt.isDefined

final case class WorkerSeen(models: List[String], slots: Int, at: Instant)
final case class WorkerHolds(datasetId: String, holding: Boolean)

final class WorkerEntity(context: KeyValueEntityContext) extends KeyValueEntity[WorkerState]:
  def emptyState: WorkerState = WorkerState(context.entityId, Nil, 0, Set.empty, draining = false, None)

  private def w = currentState

  /** Registration doubles as the worker's liveness: every few seconds. Replies with the drain flag. */
  def register(seen: WorkerSeen): Effect[Boolean] =
    effects.updateState(w.copy(models = seen.models, slots = seen.slots, lastSeenAt = Some(seen.at))).thenReply(_.draining)

  def holds(change: WorkerHolds): Effect[Done] =
    if !w.exists then effects.reply(Done)
    else
      val busy = if change.holding then w.busy + change.datasetId else w.busy - change.datasetId
      if busy == w.busy then effects.reply(Done) else effects.updateState(w.copy(busy = busy)).thenReply(_ => Done)

  def drain(on: Boolean): Effect[WorkerState] =
    effects.updateState(w.copy(draining = on)).thenReplyState

  def deregister: Effect[Done] = effects.deleteEntity().thenReply(_ => Done)

  def get: ReadOnlyEffect[WorkerState] = effects.reply(w)

object WorkerEntity
    extends KeyValueEntity.Companion[WorkerEntity, WorkerState](
      componentId = ComponentId("worker"),
      stateSerializer = Codecs.serializer[WorkerState]("worker")
    ):
  given Serializer[WorkerSeen]  = Codecs.serializer[WorkerSeen]("worker-seen")
  given Serializer[WorkerHolds] = Codecs.serializer[WorkerHolds]("worker-holds")

  def create(context: KeyValueEntityContext) = new WorkerEntity(context)

  val register   = command("register")(_.register)
  val holds      = command("holds")(_.holds)
  val drain      = command("drain")(_.drain)
  val deregister = command("deregister")(_.deregister)
  val get        = query("get")(_.get)

final class WorkerRowsView extends View[WorkerState, WorkerState]:
  def onChange(state: WorkerState): Effect = effects.updateRow(state)

object WorkerRows
    extends View.Companion[WorkerRowsView, WorkerState, WorkerState](
      componentId = ComponentId("worker-rows"),
      source = ChangeSource.stateOf(WorkerEntity),
      rowSerializer = Codecs.serializer[WorkerState]("worker-row")
    ):
  def create(ctx: ViewComponentContext) = new WorkerRowsView

  /** A worker unseen for this long is shown as stale. */
  val StaleAfterSeconds = 60L
