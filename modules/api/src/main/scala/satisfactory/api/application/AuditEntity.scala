package satisfactory.api.application

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Done, Serializer}
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant

final case class OperatorAction(subject: String, action: String, target: String, at: Instant)

final case class Audit(entries: Vector[OperatorAction])

enum AuditEvent:
  case OperatorActed(action: OperatorAction)

/**
 * Every operator action, attributed (Story 10): one stream, id `ops`. Low volume by nature; the state
 * keeps the most recent 500, the journal keeps them all.
 */
final class AuditEntity extends EventSourcedEntity[Audit, AuditEvent]:
  def emptyState: Audit = Audit(Vector.empty)

  def applyEvent(event: AuditEvent): Audit = event match
    case AuditEvent.OperatorActed(action) => Audit((currentState.entries :+ action).takeRight(AuditEntity.Kept))

  def record(action: OperatorAction): Effect[Done] =
    effects.persist(AuditEvent.OperatorActed(action)).thenReply(_ => Done)

  def recent(limit: Int): ReadOnlyEffect[List[OperatorAction]] =
    effects.reply(currentState.entries.takeRight(limit.max(0).min(AuditEntity.Kept)).reverse.toList)

object AuditEntity
    extends EventSourcedEntity.Companion[AuditEntity, Audit, AuditEvent](
      componentId = ComponentId("audit"),
      stateSerializer = Codecs.serializer[Audit]("audit"),
      eventSerializer = Codecs.serializer[AuditEvent]("audit-event")
    ):
  val Kept = 500
  val Id   = "ops"

  given Serializer[OperatorAction]       = Codecs.serializer[OperatorAction]("operator-action")
  given Serializer[List[OperatorAction]] = Codecs.serializer[List[OperatorAction]]("operator-actions")

  def create(context: EventSourcedEntityContext) = new AuditEntity

  val record = command("record")(_.record)
  val recent = query("recent")(_.recent)
