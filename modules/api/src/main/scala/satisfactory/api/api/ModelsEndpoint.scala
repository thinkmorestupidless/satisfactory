package satisfactory.api.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.*
import satisfactory.api.api.Bodies.given
import satisfactory.api.api.Replies.*
import satisfactory.api.application.*
import satisfactory.api.domain.*
import satisfactory.protocol.*
import satisfactory.protocol.WireCodecs.given
import satisfactory.spi.{Json, ModelRuntime}

import scala.jdk.CollectionConverters.*

/**
 * The model API for one model (API.md §2): `/api/models/<model>/v1`, with the model's entity name
 * (`schedules`, `route-plans`) as a literal segment. One endpoint per catalog model keeps every route
 * within ankka's two path parameters.
 *
 * Every route authenticates an API key; another tenant's dataset is `404`, never `403`.
 */
final class ModelsEndpoint(runtime: ModelRuntime[?, ?], ctx: ApiContext)
    extends HttpEndpoint(s"/api/models/${runtime.key().registrationKey()}/${runtime.key().version()}"):

  val acl: Acl = ctx.keys.acl

  private val entity = s"/${runtime.key().entity()}"
  private val rows   = ctx.views.forView(DatasetRows)

  protected def caller: TenantCaller = TenantCaller.of(principal)

  protected def dataset(id: String) = ctx.client.forEventSourcedEntity(EntityId(id))

  /** The dataset, if it exists and belongs to the caller's tenant; otherwise the same 404 either way. */
  protected def owned(id: String): Dataset =
    val state =
      try dataset(id).call(DatasetEntity.get).invoke()
      catch
        case e: com.thinkmorestupidless.ankka.core.CommandError
            if e.code == com.thinkmorestupidless.ankka.core.ErrorCode.NotFound =>
          throw ApiError.notFound(s"no dataset $id")
    if state.tenantId != caller.tenantId || state.spec.exists(_.modelKey != runtime.key().key()) then
      throw ApiError.notFound(s"no dataset $id")
    state

  protected def bodiesAvailable(state: Dataset): Unit =
    if state.purged then throw ApiError.gone(s"dataset ${state.id} has been purged")

  protected def params(): SubmitParams =
    SubmitParams(
      name = query.raw("name"),
      operation = query.raw("operation"),
      configurationId = query.raw("configurationId"),
      priority = query.optional[Int]("priority"),
      tags = query.rawAll("tags").toList,
      idempotencyKey = request.header("Idempotency-Key")
    )

  protected def blobJson(ref: String): Option[RawJson] = ctx.blobs.get(ref).map(b => RawJson(b.bytes))

  // ── The catalog ────────────────────────────────────────────────────────────────────────────

  get("/model") { () =>
    handle(json(Catalog.descriptor(runtime)))
  }

  get("/demo-data") { () =>
    handle(json(Catalog.demoSummaries(runtime)))
  }

  get("/demo-data/{demoDataId}") { (demoId: String) =>
    handle {
      val demo = Catalog.demo(runtime, demoId)
      json(SubmitRequest(RawJson(Json.bytes(demo.input().get())), Some(Catalog.demoConfig(demo))))
    }
  }

  get("/demo-data/{demoDataId}/input") { (demoId: String) =>
    handle(raw(Json.bytes(Catalog.demo(runtime, demoId).input().get())))
  }

  get(s"$entity/validation-issue-types") { () =>
    handle(raw(Json.bytes(java.util.Map.of("issueTypes", Catalog.issueTypes(runtime).asJava))))
  }

  get(s"$entity/validation-issue-types/{code}") { (code: String) =>
    handle {
      Catalog.issueTypes(runtime).find(_.get("code") == code) match
        case Some(issue) => raw(Json.bytes(issue))
        case None        => throw ApiError.notFound(s"no issue type $code")
    }
  }

  // ── Submitting ─────────────────────────────────────────────────────────────────────────────

  postBody(entity) { (body: Array[Byte]) =>
    handle {
      val submit = Bodies.parse[SubmitRequest](Bodies.decoded(request, body))
      val metadata = ctx.submissions.create(
        caller,
        runtime,
        Json.parse(submit.modelInput.bytes),
        submit.config,
        params()
      )
      json(metadata, 202)
    }
  }

  /** Solve a dataset submitted with `operation=NONE` (API.md §2.1). */
  post(s"$entity/{id}") { (id: String) =>
    handle {
      caller.requireWrite()
      val _ = owned(id)
      json(dataset(id).call(DatasetEntity.solve).invoke(SolveDataset(query.optional[Int]("priority"), ctx.now())), 202)
    }
  }

  // ── Reading ────────────────────────────────────────────────────────────────────────────────

  get(entity) { () =>
    handle {
      val size = query.optional[Int]("size").getOrElse(50).max(1).min(200)
      val page = query.optional[Int]("page").getOrElse(0).max(0)
      val (content, more) = DatasetRows.forTenant(
        rows,
        caller.tenantId,
        Some(runtime.key().key()),
        query.rawAll("status"),
        query.rawAll("tag") ++ query.rawAll("tags"),
        query.flag("includeExpired"),
        page,
        size
      )
      json(Page(content.map(_.metadata).toList, page, size, more))
    }
  }

  get(s"$entity/{id}") { (id: String) =>
    handle {
      val state = owned(id)
      bodiesAvailable(state)
      json(
        DatasetResponse(
          state.metadata,
          state.best.flatMap(b => blobJson(b.solutionRef)),
          state.inputMetrics,
          state.best.flatMap(_.kpis)
        )
      )
    }
  }

  /** A queued dataset says whether its tenant's queue is paused, so a caller can tell why it waits. */
  get(s"$entity/{id}/metadata") { (id: String) =>
    handle {
      val state = owned(id)
      val paused =
        if !state.queued then None
        else Some(ctx.client.forEventSourcedEntity(EntityId(state.tenantId)).call(TenantEntity.get).invoke().queuePaused)
      json(state.metadata.copy(queuePaused = paused))
    }
  }

  get(s"$entity/{id}/input") { (id: String) =>
    handle {
      val state = owned(id)
      bodiesAvailable(state)
      state.spec.flatMap(s => blobJson(s.inputRef)) match
        case Some(input) => raw(input.bytes)
        case None        => throw ApiError.gone(s"the input of $id is no longer stored")
    }
  }

  get(s"$entity/{id}/model-request") { (id: String) =>
    handle {
      val state = owned(id)
      bodiesAvailable(state)
      val spec  = state.spec.get
      val input = blobJson(spec.inputRef).getOrElse(throw ApiError.gone(s"the input of $id is no longer stored"))
      json(SubmitRequest(input, spec.submittedConfig))
    }
  }

  get(s"$entity/{id}/config") { (id: String) =>
    handle(json(Catalog.asConfiguration(owned(id).spec.get.config)))
  }

  get(s"$entity/{id}/validation-result") { (id: String) =>
    handle {
      owned(id).validation match
        case Some(result) => json(result)
        case None         => json(ValidationResult("PENDING", RawJson.emptyArray))
    }
  }

  get(s"$entity/{id}/logs") { (id: String) =>
    handle(json(Logs(owned(id).logs.mkString("\n"))))
  }

  // ── Following and stopping (Story 2) ───────────────────────────────────────────────────────

  /**
   * Server-sent events of the dataset's metadata until a final state (API.md §2.4, §8): `after` to
   * resume, `status` to filter, `follow=lineage` to continue into a superseding child. `410` when the
   * dataset is already final and there is nothing after `after` to send.
   */
  sse(s"$entity/{id}/events") { (id: String) =>
    val state =
      try owned(id)
      catch case e: ApiError => throw com.thinkmorestupidless.ankka.http.HttpProblem(e.status, e.message)
    val after = query.optional[Long]("after").getOrElse(0L)
    val follow = query.raw("follow").contains("lineage")
    val resuming = query.raw("after").isDefined && after < state.seq
    if state.isFinal && !resuming && !(follow && state.supersededBy.isDefined) then
      throw com.thinkmorestupidless.ankka.http.HttpProblem(410, s"dataset $id is ${state.status}; fetch it instead")
    StreamSource(id, StreamSource.Options(after, query.rawAll("status").toSet, follow)) { (datasetId, since) =>
      dataset(datasetId).call(DatasetEntity.updatesSince).invokeAsync(since)
    }
  }

  /**
   * Terminate: stop solving and keep the best (API.md §2.2). A success — `SOLVING_COMPLETED` with a
   * solution, `SOLVING_INCOMPLETE` without. A final dataset is returned unchanged. `force` finishes
   * at once, whatever the worker is doing.
   */
  delete(s"$entity/{id}") { (id: String) =>
    handle {
      caller.requireWrite()
      val _ = owned(id)
      val _ = dataset(id)
        .call(DatasetEntity.requestTerminate)
        .invoke(TerminateDataset(caller.keyId, query.flag("force"), ctx.now()))
      val state = owned(id)
      json(
        DatasetResponse(
          state.metadata,
          if state.purged then None else state.best.flatMap(b => blobJson(b.solutionRef)),
          state.inputMetrics,
          state.best.flatMap(_.kpis)
        )
      )
    }
  }

  // ── Changing the problem: lineage, not mutation (Story 3) ─────────────────────────────────

  private def select(default: String): String = query.raw("select").map(_.toUpperCase).getOrElse(default)

  /** A new dataset from this one's input (default) or its solved output, with optional new config. */
  postBody(s"$entity/{id}/from-input") { (id: String, body: Array[Byte]) =>
    handle {
      val parent  = owned(id)
      val decoded = Bodies.decoded(request, body)
      val config  = if decoded.isEmpty then None else Bodies.parse[FromInputRequest](decoded).config
      json(ctx.derivations.derive(caller, runtime, parent, select(Select.Unsolved), None, config, params()), 202)
    }
  }

  /** A new dataset from a patch to this one's solved output (default) or its input. */
  postBody(s"$entity/{id}/from-patch") { (id: String, body: Array[Byte]) =>
    handle {
      val parent  = owned(id)
      val request = Bodies.parse[PatchRequest](Bodies.decoded(this.request, body))
      json(
        ctx.derivations.derive(caller, runtime, parent, select(Select.Solved), Some(request.patch), request.config, params()),
        202
      )
    }
  }

  // ── Understanding the result (Story 7) ────────────────────────────────────────────────────

  /** CPU-bound work on the api node, bounded so it cannot starve the node's cluster heartbeats. */
  private val analysisPermits = java.util.concurrent.Semaphore(2)

  private def analyse(input: com.fasterxml.jackson.databind.JsonNode, weights: Map[String, Long], asSolution: Boolean): ScoreAnalysis =
    analysisPermits.acquire()
    try
      val w = weights.map((k, v) => k -> java.lang.Long.valueOf(v)).asJava
      val solution = if asSolution then runtime.solution(input, w) else runtime.problem(input, w)
      val view = runtime.analyze(solution, w)
      ScoreAnalysis(view.score(), view.constraints().asScala.toList.map(c => ConstraintAnalysis(c.name(), c.weight(), c.score())))
    finally analysisPermits.release()

  private def withJustifications(analysis: ScoreAnalysis): ScoreAnalysis =
    if query.flag("includeJustifications") then analysis.copy(justifications = Some("unsupported")) else analysis

  /**
   * The best solution's score, per constraint (Community shape, research R2): the worker's own
   * analysis once solving has finished, otherwise computed now from the best so far — or from the
   * plan as submitted, before there is a best.
   */
  get(s"$entity/{id}/score-analysis") { (id: String) =>
    handle {
      val state = owned(id)
      bodiesAvailable(state)
      val stored = if state.isFinal then state.analysisRef.flatMap(ref => ctx.blobs.get(ref)) else None
      val analysis = stored match
        case Some(blob) => Bodies.parse[ScoreAnalysis](blob.bytes)
        case None =>
          val (bytes, solved) = state.best.flatMap(b => ctx.blobs.get(b.solutionRef)).map(_.bytes -> true)
            .orElse(state.spec.flatMap(s => ctx.blobs.get(s.inputRef)).map(_.bytes -> state.spec.exists(_.select.contains(Select.Solved))))
            .getOrElse(throw ApiError.gone(s"dataset $id has nothing to analyse"))
          analyse(Json.parse(bytes), state.spec.get.config.weights, solved)
      json(withJustifications(analysis))
    }
  }

  /** Scores a plan the caller already has, against a profile or its own weights; creates nothing. */
  postBody(s"$entity/score-analysis") { (body: Array[Byte]) =>
    handle {
      val request  = Bodies.parse[SubmitRequest](Bodies.decoded(this.request, body))
      val input    = Json.parse(request.modelInput.bytes)
      val problems = runtime.validateInput(input).asScala.toList
      if problems.nonEmpty then throw ApiError.validation("the modelInput does not match the model's input schema", problems)
      val owner   = ctx.client.forEventSourcedEntity(EntityId(caller.tenantId)).call(TenantEntity.get).invoke()
      val profile = query.raw("configurationId").flatMap(p => ProfileLookup.find(owner, runtime.key().key(), p))
      val config = ConfigResolver.resolve(runtime, None, profile, request.config, scala.concurrent.duration.Duration(owner.limits.lifetimeCeilingSeconds, "s")) match
        case Left(errors)  => throw ApiError.validation("the configuration is invalid", errors)
        case Right(config) => config
      json(withJustifications(analyse(input, config.weights, asSolution = true)))
    }
  }

  // ── Managing datasets over their lifetime (Story 8) ───────────────────────────────────────

  patchBody(s"$entity/{id}/metadata") { (id: String, body: Array[Byte]) =>
    handle {
      caller.requireWrite()
      val _      = owned(id)
      val change = Bodies.parse[MetadataPatch](body)
      json(dataset(id).call(DatasetEntity.updateMetadata).invoke(UpdateMetadata(change.name, change.tags)))
    }
  }

  /** Purge: the bodies are hidden at once and deleted when the restore window closes. */
  delete(s"$entity/{id}/purge") { (id: String) =>
    handle {
      caller.requireWrite()
      val _ = owned(id)
      val _ = dataset(id).call(DatasetEntity.purge).invoke(At(ctx.now()))
      noContent
    }
  }

  /** Restore a purged dataset while its retention lasts. */
  put(s"$entity/{id}") { (id: String) =>
    handle {
      caller.requireWrite()
      val _ = owned(id)
      val _ = dataset(id).call(DatasetEntity.restore).invoke(At(ctx.now()))
      noContent
    }
  }
