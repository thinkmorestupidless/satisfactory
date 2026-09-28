package satisfactory.api.application

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.runtime.{SqlFragment, ViewQueries}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.*
import com.thinkmorestupidless.ankka.sdk.*
import satisfactory.protocol.Metadata

/**
 * One row per dataset, for every question that is not "this dataset, by id": a tenant's listing, the
 * queue head for a worker's models, who holds what. The row carries the dataset's compacted state,
 * folded by the same [[DatasetFold]] the entity uses, so the two cannot disagree about a status —
 * only lag. Flat copies of the queried fields sit beside it for SQL.
 */
final case class DatasetRow(
    datasetId: String,
    tenantId: String,
    modelKey: String,
    status: String,
    queued: Boolean,
    isFinal: Boolean,
    priority: Int,
    submittedAtMillis: Long,
    tags: List[String],
    workerId: Option[String],
    epoch: Long,
    attempt: Int,
    purged: Boolean,
    expired: Boolean,
    state: Dataset
):
  def metadata: Metadata = state.metadata

object DatasetRow:
  def of(state: Dataset): DatasetRow =
    DatasetRow(
      datasetId = state.id,
      tenantId = state.tenantId,
      modelKey = state.spec.fold("")(_.modelKey),
      status = state.status,
      queued = state.queued,
      isFinal = state.isFinal,
      priority = state.spec.fold(0)(_.priority),
      submittedAtMillis = state.submitted.fold(0L)(_.toEpochMilli),
      tags = state.tags,
      workerId = state.lease.map(_.workerId),
      epoch = state.lease.fold(0L)(_.epoch),
      attempt = state.attempt,
      purged = state.purged,
      expired = state.expired,
      state = state.compact
    )

final class DatasetRowsView extends View[DatasetEvent, DatasetRow]:
  def onChange(event: DatasetEvent): Effect =
    val previous = rowState.map(_.state).getOrElse(DatasetFold.empty(updateContext.subject))
    effects.updateRow(DatasetRow.of(DatasetFold(previous, event).compact))

object DatasetRows
    extends View.Companion[DatasetRowsView, DatasetEvent, DatasetRow](
      componentId = ComponentId("dataset-rows"),
      source = ChangeSource.eventsOf(DatasetEntity),
      rowSerializer = Codecs.serializer[DatasetRow]("dataset-row")
    ):
  def create(ctx: ViewComponentContext) = new DatasetRowsView

  private def anyOf(field: SqlFragment, values: Seq[String]): SqlFragment =
    if values.isEmpty then SqlFragment.raw("true")
    else
      values.zipWithIndex
        .map((v, i) => (if i == 0 then SqlFragment.raw("(") else SqlFragment.raw(" OR ")) ++ field ++ sql" = $v")
        .reduce(_ ++ _) ++ SqlFragment.raw(")")

  /** Claimable datasets for these models: highest priority first, then oldest (research R6). */
  def claimCandidates(rows: ViewQueries[DatasetRow], models: Seq[String], limit: Int = 10): Vector[DatasetRow] =
    rows.ordered(
      SqlFragment.raw("payload::jsonb->>'queued' = 'true' AND ") ++ anyOf(jsonText("modelKey"), models),
      SqlFragment.raw("(payload::jsonb->>'priority')::int DESC, (payload::jsonb->>'submittedAtMillis')::bigint ASC"),
      limit
    )

  /**
   * A tenant's datasets, newest first, filtered by status and tag. Paged by fetching through the page
   * and dropping what precedes it; `size` is capped at 200.
   */
  def forTenant(
      rows: ViewQueries[DatasetRow],
      tenantId: String,
      model: Option[String],
      statuses: Seq[String],
      tags: Seq[String],
      includeExpired: Boolean,
      page: Int,
      size: Int
  ): (Vector[DatasetRow], Boolean) =
    val base = jsonText("tenantId") ++ sql" = $tenantId"
    val byModel  = model.fold(SqlFragment.raw(""))(m => SqlFragment.raw(" AND ") ++ jsonText("modelKey") ++ sql" = $m")
    val byStatus = if statuses.isEmpty then SqlFragment.raw("") else SqlFragment.raw(" AND ") ++ anyOf(jsonText("status"), statuses)
    val byTags   = tags.map(tag => SqlFragment.raw(" AND ") ++ jsonContains("tags", tag)).foldLeft(SqlFragment.raw(""))(_ ++ _)
    val expiry   = if includeExpired then SqlFragment.raw("") else SqlFragment.raw(" AND payload::jsonb->>'expired' = 'false'")
    val fetched = rows.ordered(
      base ++ byModel ++ byStatus ++ byTags ++ expiry,
      SqlFragment.raw("(payload::jsonb->>'submittedAtMillis')::bigint DESC"),
      (page + 1) * size + 1
    )
    val content = fetched.slice(page * size, page * size + size)
    (content, fetched.size > (page + 1) * size)

  /** Across every tenant, for operators: filtered by tenant, model and status, newest first. */
  def forOperators(
      rows: ViewQueries[DatasetRow],
      tenantId: Option[String],
      model: Option[String],
      statuses: Seq[String],
      page: Int,
      size: Int
  ): (Vector[DatasetRow], Boolean) =
    val byTenant = tenantId.fold(SqlFragment.raw("true"))(t => jsonText("tenantId") ++ sql" = $t")
    val byModel  = model.fold(SqlFragment.raw(""))(m => SqlFragment.raw(" AND ") ++ jsonText("modelKey") ++ sql" = $m")
    val byStatus = if statuses.isEmpty then SqlFragment.raw("") else SqlFragment.raw(" AND ") ++ anyOf(jsonText("status"), statuses)
    val fetched = rows.ordered(
      byTenant ++ byModel ++ byStatus,
      SqlFragment.raw("(payload::jsonb->>'submittedAtMillis')::bigint DESC"),
      (page + 1) * size + 1
    )
    (fetched.slice(page * size, page * size + size), fetched.size > (page + 1) * size)
