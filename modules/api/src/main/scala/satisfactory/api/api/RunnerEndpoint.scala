package satisfactory.api.api

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.http.*
import satisfactory.api.api.Bodies.given
import satisfactory.api.domain.ApiContext
import satisfactory.protocol.*
import satisfactory.protocol.WireCodecs.given

import scala.util.control.NonFatal

/**
 * The runner protocol over HTTP (contracts/runner-protocol.md), for the `solver` service. Guarded by
 * the runner token alone: `api` is exposed, so this ACL is the only thing in front of these routes.
 * A stale lease is `409`, which tells the worker to stop.
 */
final class RunnerEndpoint(ctx: ApiContext) extends HttpEndpoint("/internal"):

  val acl: Acl = RunnerAuth.acl(ctx.settings.runnerToken)

  private val runner = ctx.runner

  private def answer[A](body: => A)(using codec: com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[A]): Respond[Bytes] =
    guarded(Replies.json(body))

  private def done(body: => Unit): Respond[Bytes] =
    guarded {
      body
      Replies.noContent
    }

  private def guarded(reply: => Respond[Bytes]): Respond[Bytes] =
    try reply
    catch
      case e: CommandError if e.code == ErrorCode.Conflict => Replies.error(ApiError.conflict(e.message))
      case e: ApiError                                     => Replies.error(e)
      case e: CommandError                                 => Replies.handle(throw e)
      case NonFatal(e)                                     => Replies.handle(throw e)

  postBody("/workers/{workerId}") { (workerId: String, body: Array[Byte]) =>
    answer(runner.register(workerId, Bodies.parse[WorkerRegistration](body)))
  }

  delete("/workers/{workerId}") { (workerId: String) =>
    done(runner.deregister(workerId))
  }

  /** Long-polls: `204` when nothing becomes claimable within the wait. */
  postBody("/leases") { (body: Array[Byte]) =>
    guarded {
      runner.claim(Bodies.parse[ClaimRequest](body), ctx.settings.claimWait) match
        case Some(claim) => Replies.json(claim)
        case None        => Replies.noContent
    }
  }

  postBody("/datasets/{id}/phase") { (id: String, body: Array[Byte]) =>
    done(runner.phase(id, Bodies.parse[PhaseRequest](body)))
  }

  postBody("/datasets/{id}/solutions") { (id: String, body: Array[Byte]) =>
    answer(runner.report(id, Bodies.parse[ReportRequest](body)))
  }

  postBody("/datasets/{id}/heartbeat") { (id: String, body: Array[Byte]) =>
    answer(runner.heartbeat(id, Bodies.parse[HeartbeatRequest](body)))
  }

  postBody("/datasets/{id}/complete") { (id: String, body: Array[Byte]) =>
    done(runner.complete(id, Bodies.parse[CompleteRequest](body)))
  }

  postBody("/datasets/{id}/fail") { (id: String, body: Array[Byte]) =>
    done(runner.fail(id, Bodies.parse[FailRequest](body)))
  }

  postBody("/datasets/{id}/release") { (id: String, body: Array[Byte]) =>
    done(runner.release(id, Bodies.parse[ReleaseRequest](body)))
  }

  /** Blob refs are `<datasetId>/<kind>/<part>`: the dataset id and the rest, as two segments. */
  putBody("/blobs/{datasetId}/{rest}") { (datasetId: String, rest: String, body: Array[Byte]) =>
    done(runner.putBlob(s"$datasetId/${decode(rest)}", request.header("Content-Type").getOrElse("application/json"), body))
  }

  get("/blobs/{datasetId}/{rest}") { (datasetId: String, rest: String) =>
    guarded {
      runner.getBlob(s"$datasetId/${decode(rest)}") match
        case Some(blob) => Replies.raw(blob.bytes, blob.contentType)
        case None       => Replies.error(ApiError.notFound(s"no blob $datasetId/$rest"))
    }
  }

  private def decode(segment: String): String = java.net.URLDecoder.decode(segment, "UTF-8")

/** `GET /api/aboutme`: who this key is (API.md §2). */
final class AboutMeEndpoint(ctx: ApiContext) extends HttpEndpoint("/api/aboutme"):
  val acl: Acl = ctx.keys.acl

  get("/") { () =>
    Replies.handle {
      val caller = TenantCaller.of(principal)
      val tenant = ctx.client
        .forEventSourcedEntity(com.thinkmorestupidless.ankka.core.EntityId(caller.tenantId))
        .call(satisfactory.api.application.TenantEntity.get)
        .invoke()
      Replies.json(WhoAmI(caller.tenantId, tenant.name, caller.keyId, caller.role, tenant.queuePaused))
    }
  }
