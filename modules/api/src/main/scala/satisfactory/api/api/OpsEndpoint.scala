package satisfactory.api.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.*
import satisfactory.api.api.Replies.*
import satisfactory.api.application.*
import satisfactory.api.auth.PlatformAuth
import satisfactory.api.domain.ApiContext
import satisfactory.protocol.{Page, WireCodecs}
import satisfactory.protocol.WireCodecs.given

import java.time.Instant

/** A dataset as an operator sees it: who, what, where — never its input or its solution. */
final case class OpsDatasetRow(
    datasetId: String,
    tenantId: String,
    model: String,
    status: String,
    priority: Int,
    submittedAt: Option[Instant],
    bestScore: Option[String],
    workerId: Option[String],
    epoch: Long,
    attempt: Int
)

final case class OpsWorkerRow(
    workerId: String,
    models: List[String],
    slots: Int,
    busy: List[String],
    draining: Boolean,
    lastSeenAt: Option[Instant],
    stale: Boolean
)

object OpsCodecs:
  given datasets: JsonValueCodec[Page[OpsDatasetRow]]     = WireCodecs.make
  given workers: JsonValueCodec[List[OpsWorkerRow]]       = WireCodecs.make
  given audit: JsonValueCodec[List[OperatorAction]]       = WireCodecs.make
  given worker: JsonValueCodec[OpsWorkerRow]              = WireCodecs.make

/**
 * The platform operator's view across tenants, and the three things an operator can do: force a
 * dataset to finish, drain a worker, pause a tenant's queue (Story 10). The realm role
 * `satisfactory-operator` only; every action is recorded with who did it.
 */
final class OpsEndpoint(ctx: ApiContext) extends HttpEndpoint("/api/platform/v1/ops"):
  import OpsCodecs.given

  val acl: Acl = PlatformAuth.acl(ctx.verifier)

  private val datasets = ctx.views.forView(DatasetRows)
  private val workers  = ctx.views.forView(WorkerRows)

  private def operator(): String =
    if !principal.roles.contains(ctx.settings.operatorRole) then throw ApiError.forbidden("platform operators only")
    principal.subject

  private def row(d: DatasetRow) =
    OpsDatasetRow(d.datasetId, d.tenantId, d.modelKey, d.status, d.priority, d.state.submitted, d.state.best.map(_.score), d.workerId, d.epoch, d.attempt)

  private def workerRow(w: WorkerState) =
    val stale = w.lastSeenAt.forall(_.plusSeconds(WorkerRows.StaleAfterSeconds).isBefore(ctx.now()))
    OpsWorkerRow(w.workerId, w.models, w.slots, w.busy.toList.sorted, w.draining, w.lastSeenAt, stale)

  get("/datasets") { () =>
    handle {
      val _    = operator()
      val size = query.optional[Int]("size").getOrElse(50).max(1).min(200)
      val page = query.optional[Int]("page").getOrElse(0).max(0)
      val (content, more) = DatasetRows.forOperators(datasets, query.raw("tenantId"), query.raw("model"), query.rawAll("status"), page, size)
      json(Page(content.map(row).toList, page, size, more))
    }
  }

  get("/workers") { () =>
    handle {
      val _ = operator()
      json(workers.all().map(workerRow).toList.sortBy(_.workerId))
    }
  }

  /** Always forced: finishes now with the best so far, whatever the worker is doing. */
  post("/datasets/{id}/terminate") { (id: String) =>
    handle {
      val who = operator()
      val metadata = ctx.client
        .forEventSourcedEntity(EntityId(id))
        .call(DatasetEntity.requestTerminate)
        .invoke(TerminateDataset(s"operator:$who", force = true, ctx.now()))
      ctx.audit(who, "force-terminate", id)
      json(metadata)
    }
  }

  post("/workers/{id}/drain") { (id: String) =>
    handle {
      val who = operator()
      val state = ctx.client.forKeyValueEntity(EntityId(id)).call(WorkerEntity.drain).invoke(true)
      ctx.audit(who, "drain", id)
      json(workerRow(state))
    }
  }

  post("/workers/{id}/undrain") { (id: String) =>
    handle {
      val who = operator()
      val state = ctx.client.forKeyValueEntity(EntityId(id)).call(WorkerEntity.drain).invoke(false)
      ctx.audit(who, "undrain", id)
      json(workerRow(state))
    }
  }

  post("/tenants/{id}/queue/pause") { (id: String) =>
    handle {
      val who = operator()
      val _   = ctx.client.forEventSourcedEntity(EntityId(id)).call(TenantEntity.pauseQueue).invoke(ByWhom(who, ctx.now()))
      ctx.audit(who, "pause-queue", id)
      noContent
    }
  }

  post("/tenants/{id}/queue/resume") { (id: String) =>
    handle {
      val who = operator()
      val _   = ctx.client.forEventSourcedEntity(EntityId(id)).call(TenantEntity.resumeQueue).invoke(ByWhom(who, ctx.now()))
      ctx.audit(who, "resume-queue", id)
      noContent
    }
  }

  get("/audit") { () =>
    handle {
      val _ = operator()
      json(
        ctx.client
          .forEventSourcedEntity(EntityId(AuditEntity.Id))
          .call(AuditEntity.recent)
          .invoke(query.optional[Int]("limit").getOrElse(AuditEntity.Kept))
      )
    }
  }

/**
 * `/metrics`, Prometheus text (research R15; extension routes are listings, not served, so this is an
 * endpoint on the HTTP port). Scraped with `Authorization: Bearer <satisfactory.metrics-token>`.
 */
final class MetricsEndpoint(ctx: ApiContext) extends HttpEndpoint("/metrics"):
  val acl: Acl = ctx.settings.metricsToken match
    case None => Acl.DenyAll
    case Some(token) =>
      Acl.AllowIf(r => r.header("Authorization").exists(h => Hashing.constantTimeEquals(h.trim, s"Bearer $token")))

  private val datasets = ctx.views.forView(DatasetRows)
  private val workers  = ctx.views.forView(WorkerRows)

  get("/") { () =>
    import com.thinkmorestupidless.ankka.runtime.SqlSyntax.*
    import com.thinkmorestupidless.ankka.runtime.SqlFragment
    import satisfactory.api.metrics.Counters
    val out = StringBuilder()
    def line(s: String): Unit = out.append(s).append('\n'): Unit
    line("# HELP satisfactory_queued Datasets waiting for a worker, per model.")
    line("# TYPE satisfactory_queued gauge")
    ctx.catalog.keys().forEach { model =>
      val n = datasets.count(SqlFragment.raw("payload::jsonb->>'queued' = 'true' AND ") ++ jsonText("modelKey") ++ sql" = $model")
      line(s"""satisfactory_queued{model="$model"} $n""")
    }
    val ws    = workers.all()
    val now   = ctx.now()
    val fresh = ws.filter(_.lastSeenAt.exists(_.plusSeconds(WorkerRows.StaleAfterSeconds).isAfter(now)))
    val busy  = fresh.map(_.busy.size).sum
    val slots = fresh.filterNot(_.draining).map(_.slots).sum
    line("# HELP satisfactory_slots Worker slots, free and busy.")
    line("# TYPE satisfactory_slots gauge")
    line(s"""satisfactory_slots{state="busy"} $busy""")
    line(s"""satisfactory_slots{state="free"} ${math.max(0, slots - busy)}""")
    line("# HELP satisfactory_workers Workers by state.")
    line("# TYPE satisfactory_workers gauge")
    line(s"""satisfactory_workers{state="ready"} ${fresh.count(!_.draining)}""")
    line(s"""satisfactory_workers{state="draining"} ${fresh.count(_.draining)}""")
    line(s"""satisfactory_workers{state="stale"} ${ws.size - fresh.size}""")
    def counter(name: String, help: String, value: Long, labels: String = ""): Unit =
      line(s"# HELP $name $help")
      line(s"# TYPE $name counter")
      line(s"$name$labels $value")
    counter("satisfactory_lease_losses_total", "Leases that expired without a heartbeat (this instance).", Counters.leaseLosses.get)
    counter("satisfactory_requeues_total", "Datasets put back in the queue (this instance).", Counters.requeues.get)
    line("# HELP satisfactory_solves_total Solves finished, by outcome (this instance).")
    line("# TYPE satisfactory_solves_total counter")
    line(s"""satisfactory_solves_total{outcome="completed"} ${Counters.solvesCompleted.get}""")
    line(s"""satisfactory_solves_total{outcome="failed"} ${Counters.solvesFailed.get}""")
    counter("satisfactory_webhook_failures_total", "Webhook deliveries that exhausted their retries (this instance).", Counters.webhookFailures.get)
    Replies.raw(out.toString.getBytes("UTF-8"), "text/plain; version=0.0.4")
  }
