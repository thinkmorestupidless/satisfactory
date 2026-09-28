package satisfactory.api.domain

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import satisfactory.api.api.{ApiError, Hashing, TenantCaller}
import satisfactory.api.application.*
import satisfactory.protocol.*
import satisfactory.spi.{Json, ModelRuntime}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** What the query string of a submit (or a derivation) says. */
final case class SubmitParams(
    name: Option[String] = None,
    operation: Option[String] = None,
    configurationId: Option[String] = None,
    priority: Option[Int] = None,
    tags: List[String] = Nil,
    idempotencyKey: Option[String] = None
)

/** Where a derived dataset comes from (API.md §2.5). */
final case class Lineage(
    parent: Dataset,
    select: String,
    patch: Option[Array[Byte]]
)

/**
 * The one path every dataset is created by — submit, `from-input` and `from-patch` all land here:
 * rate limit, idempotency, schema validation (400 with the model's own messages, nothing created),
 * configuration resolution, the input blob, the entity, the lifetime timer.
 */
final class Submissions(ctx: ApiContext):

  private def tenant(id: String)  = ctx.client.forEventSourcedEntity(EntityId(id))
  private def dataset(id: String) = ctx.client.forEventSourcedEntity(EntityId(id))

  def create(
      caller: TenantCaller,
      runtime: ModelRuntime[?, ?],
      modelInput: JsonNode,
      submitted: Option[ModelConfiguration],
      params: SubmitParams,
      lineage: Option[Lineage] = None
  ): Metadata =
    caller.requireWrite()
    val owner = tenant(caller.tenantId).call(TenantEntity.get).invoke()

    val idempotency = params.idempotencyKey.map(k => s"${caller.tenantId}:${Hashing.sha256(k)}")
    val existing = idempotency.flatMap { key =>
      val record = ctx.client.forKeyValueEntity(EntityId(key)).call(IdempotencyEntity.get).invoke()
      Option.when(record.exists)(record.datasetId)
    }
    existing match
      case Some(datasetId) => dataset(datasetId).call(DatasetEntity.metadata).invoke()
      case None =>
        if !tenant(caller.tenantId).call(TenantEntity.takeSubmitToken).invoke(At(ctx.now())) then
          throw ApiError.rateLimited(s"tenant ${caller.tenantId} is over ${owner.limits.submitPerMinute} submissions a minute")

        val schemaProblems = runtime.validateInput(modelInput).asScala.toList
        if schemaProblems.nonEmpty then
          throw ApiError.validation("the modelInput does not match the model's input schema", schemaProblems)

        val operation = params.operation.map(_.toUpperCase).getOrElse(Operation.Solve)
        if operation != Operation.Solve && operation != Operation.None then
          throw ApiError.badRequest(s"operation must be SOLVE or NONE, not ${params.operation.get}")
        val priority = params.priority.getOrElse(5)
        if priority < 0 || priority > 10 then throw ApiError.badRequest("priority must be between 0 and 10")

        val modelKey = runtime.key().key()
        val profile  = params.configurationId.flatMap(id => ProfileLookup.find(owner, modelKey, id))
        if params.configurationId.exists(id => id != "standard" && profile.isEmpty) then
          throw ApiError.badRequest(s"no configuration profile '${params.configurationId.get}' for $modelKey")

        val config = ConfigResolver.resolve(
          runtime,
          lineage.flatMap(_.parent.spec).map(_.config),
          profile,
          submitted,
          owner.limits.lifetimeCeilingSeconds.seconds
        ) match
          case Left(problems) => throw ApiError.validation("the configuration is invalid", problems)
          case Right(resolved) => resolved

        val run  = submitted.flatMap(_.run)
        val name = params.name.orElse(run.flatMap(_.name))
        val tags = (params.tags ++ run.flatMap(_.tags).getOrElse(Nil)).distinct
        MetadataRules.check(name, Some(tags)).foreach(problem => throw ApiError.badRequest(problem))

        val id       = Ids.dataset()
        val inputRef = BlobRefs.input(id)
        ctx.blobs.put(inputRef, "application/json", Json.bytes(modelInput))
        val patchRef = lineage.flatMap(_.patch).map { bytes =>
          val ref = BlobRefs.patch(id)
          ctx.blobs.put(ref, "application/json", bytes)
          ref
        }
        val parent = lineage.map(_.parent)
        val spec = DatasetSpec(
          tenantId = caller.tenantId,
          modelId = runtime.key().registrationKey(),
          modelVersion = runtime.key().version(),
          entity = runtime.key().entity(),
          operation = operation,
          priority = priority,
          inputRef = inputRef,
          patchRef = patchRef,
          parentId = parent.map(_.id),
          originId = parent.map(p => p.spec.flatMap(_.originId).getOrElse(p.id)),
          select = lineage.map(_.select),
          config = config,
          submittedConfig = submitted
        )
        val metadata =
          try dataset(id).call(DatasetEntity.create).invoke(CreateDataset(spec, name, tags, ctx.now()))
          catch
            case e: CommandError =>
              ctx.blobs.deleteAll(id): Unit
              throw e
        DatasetTimers.armLifetime(ctx.timers, id, owner.limits.lifetimeCeilingSeconds.seconds)
        idempotency.foreach(key =>
          ctx.client.forKeyValueEntity(EntityId(key)).call(IdempotencyEntity.put).invoke(IdempotencyRecord(id, ctx.now())): Unit
        )
        metadata

/** A tenant's profiles for a model, found by id or by name; `standard` is the model's defaults. */
object ProfileLookup:
  def find(tenant: Tenant, modelKey: String, idOrName: String): Option[ProfileLayer] =
    if idOrName == "standard" then None
    else
      tenant.profiles.values
        .find(p => p.modelKey == modelKey && (p.id == idOrName || p.name == idOrName))
        .map(p => ProfileLayer(p.id, p.runConfiguration, p.weights))

  def requireKnown(tenant: Tenant, modelKey: String, idOrName: String): Unit =
    if idOrName != "standard" && find(tenant, modelKey, idOrName).isEmpty then
      throw CommandError(s"no configuration profile '$idOrName'", ErrorCode.BadRequest)
