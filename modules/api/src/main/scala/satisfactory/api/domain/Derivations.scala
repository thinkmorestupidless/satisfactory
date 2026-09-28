package satisfactory.api.domain

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import satisfactory.api.api.{ApiError, TenantCaller}
import satisfactory.api.application.*
import satisfactory.protocol.*
import satisfactory.spi.{Json, ModelRuntime}

/**
 * Changing a problem is making a new dataset (API.md §2.5): from the parent's input or its solved
 * output, optionally patched, with `parentId` and `originId`. Deriving from a parent that is still
 * solving supersedes it (API.md §2.6): the parent completes with its best so far and the child starts
 * from that best.
 */
final class Derivations(ctx: ApiContext):

  private def dataset(id: String) = ctx.client.forEventSourcedEntity(EntityId(id))

  def derive(
      caller: TenantCaller,
      runtime: ModelRuntime[?, ?],
      parent: Dataset,
      select: String,
      patch: Option[List[PatchOp]],
      config: Option[ModelConfiguration],
      params: SubmitParams
  ): Metadata =
    caller.requireWrite()
    if parent.purged then throw ApiError.gone(s"dataset ${parent.id} has been purged")
    val base: JsonNode = select match
      case Select.Unsolved =>
        parent.spec
          .flatMap(s => ctx.blobs.get(s.inputRef))
          .map(b => Json.parse(b.bytes))
          .getOrElse(throw ApiError.gone(s"the input of ${parent.id} is no longer stored"))
      case Select.Solved =>
        parent.best
          .flatMap(b => ctx.blobs.get(b.solutionRef))
          .map(b => Json.parse(b.bytes))
          .getOrElse(throw ApiError(400, "no-solution", s"dataset ${parent.id} has no solution to derive from"))
      case other => throw ApiError.badRequest(s"select must be UNSOLVED or SOLVED, not $other")
    val input = patch match
      case None => base
      case Some(ops) =>
        Patch(base, ops) match
          case Left(error)   => throw ApiError.validation("the patch cannot be applied", List(error.describe))
          case Right(result) => result
    val child = ctx.submissions.create(
      caller,
      runtime,
      input,
      config,
      params,
      Some(Lineage(parent, select, patch.map(ops => WireCodecs.encode(PatchRequest(ops))(using WireCodecs.patchRequest))))
    )
    if parent.isActive then
      dataset(parent.id).call(DatasetEntity.supersede).invoke(SupersedeDataset(child.id, ctx.now())): Unit
    child
