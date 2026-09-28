package satisfactory.api.application

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Done, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant
import scala.concurrent.duration.*

/**
 * An API key, found by the SHA-256 of its plaintext: the request names a key, not a tenant, so the
 * lookup needs its own entity (research R8). Revocation is exact here.
 */
final case class ApiKey(tenantId: String, keyId: String, role: String, revoked: Boolean):
  def exists: Boolean = tenantId.nonEmpty

final class ApiKeyEntity extends KeyValueEntity[ApiKey]:
  def emptyState: ApiKey = ApiKey("", "", "", revoked = true)

  def create(key: ApiKey): Effect[Done] =
    if currentState.exists then effects.error("key already exists", ErrorCode.Conflict)
    else effects.updateState(key.copy(revoked = false)).thenReply(_ => Done)

  def revoke: Effect[Done] =
    if !currentState.exists then effects.reply(Done)
    else effects.updateState(currentState.copy(revoked = true)).thenReply(_ => Done)

  def get: ReadOnlyEffect[ApiKey] = effects.reply(currentState)

object ApiKeyEntity extends KeyValueEntity.Companion[ApiKeyEntity, ApiKey](
      componentId = ComponentId("api-key"),
      stateSerializer = Codecs.serializer[ApiKey]("api-key")
    ):
  def create(context: KeyValueEntityContext) = new ApiKeyEntity

  val create = command("create")(_.create)
  val revoke = command("revoke")(_.revoke)
  val get    = query("get")(_.get)

/** `Idempotency-Key` → the dataset it created, for 24 hours (API.md §8). */
final case class IdempotencyRecord(datasetId: String, createdAt: Instant):
  def exists: Boolean = datasetId.nonEmpty

final class IdempotencyEntity extends KeyValueEntity[IdempotencyRecord]:
  def emptyState: IdempotencyRecord = IdempotencyRecord("", Instant.EPOCH)

  def put(record: IdempotencyRecord): Effect[IdempotencyRecord] =
    if currentState.exists then effects.reply(currentState)
    else effects.updateState(record).expireAfter(24.hours).thenReplyState

  def get: ReadOnlyEffect[IdempotencyRecord] = effects.reply(currentState)

object IdempotencyEntity extends KeyValueEntity.Companion[IdempotencyEntity, IdempotencyRecord](
      componentId = ComponentId("idempotency"),
      stateSerializer = Codecs.serializer[IdempotencyRecord]("idempotency-record")
    ):
  def create(context: KeyValueEntityContext) = new IdempotencyEntity

  val put = command("put")(_.put)
  val get = query("get")(_.get)
