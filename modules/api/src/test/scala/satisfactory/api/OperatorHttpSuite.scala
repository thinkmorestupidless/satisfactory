package satisfactory.api

import com.thinkmorestupidless.ankka.core.EntityId
import satisfactory.api.application.*
import satisfactory.protocol.SolvingStatus

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Story 10: operate the service (quickstart tier 3). */
class OperatorHttpSuite extends ApiFixture:

  override protected def configOverrides: Map[String, Any] =
    TestTokens.config ++ Map("satisfactory.metrics-token" -> "scrape-me")

  private def asOperator(method: String, path: String, token: String = TestTokens.operator): Reply =
    send(method, path, if method == "GET" then None else Some(Array.emptyByteArray), key = None, headers = Seq("Authorization" -> s"Bearer $token"))

  test("operators see datasets across tenants, with who holds them, and never their content") {
    val a = submit(spentLimit = "PT1S").get("id").asText()
    val b = submit(spentLimit = "PT1S", key = Some(keyB)).get("id").asText()
    val _ = awaitFinal(a)
    val all  = eventually()(asOperator("GET", "/api/platform/v1/ops/datasets?size=200"))(r => r.body.contains(a) && r.body.contains(b))
    assertEquals(all.status, 200)
    assert(!all.body.contains("employees") && !all.body.contains("shifts"), "no input or solution content")
    val justB = asOperator("GET", "/api/platform/v1/ops/datasets?tenantId=t_b")
    assert(justB.body.contains(b) && !justB.body.contains(a))
    val row = all.json.get("content").elements().asScala.find(_.get("datasetId").asText() == a).get
    assertEquals(row.get("tenantId").asText(), "t_a")
  }

  test("only operators: a member's token is forbidden, no token is unauthenticated, a forged one is refused") {
    assertEquals(asOperator("GET", "/api/platform/v1/ops/datasets", TestTokens.alice).status, 403)
    assertEquals(send("GET", "/api/platform/v1/ops/datasets", key = None).status, 401)
    val forged = TestTokens.token("op-1", Set("satisfactory-operator"), iss = "http://evil.test")
    assertEquals(asOperator("GET", "/api/platform/v1/ops/datasets", forged).status, 401)
    val expired = TestTokens.token("op-1", Set("satisfactory-operator"), expiresInSeconds = -120)
    assertEquals(asOperator("GET", "/api/platform/v1/ops/datasets", expired).status, 401)
  }

  test("workers are listed with slots and what they hold; drain and undrain are recorded") {
    val workers = eventually()(asOperator("GET", "/api/platform/v1/ops/workers").json)(_.size() >= 1)
    val local   = workers.get(0)
    assert(local.get("workerId").asText().startsWith("local-"))
    assertEquals(local.get("models").get(0).asText(), "employee-scheduling/v1")
    val id = local.get("workerId").asText()
    assert(asOperator("POST", s"/api/platform/v1/ops/workers/$id/drain").json.get("draining").asBoolean())
    val queued = submit(spentLimit = "PT1S").get("id").asText()
    Thread.sleep(3000)
    assertEquals(status(queued), SolvingStatus.Scheduled, "a drained worker claims nothing")
    assert(!asOperator("POST", s"/api/platform/v1/ops/workers/$id/undrain").json.get("draining").asBoolean())
    val _ = awaitFinal(queued)
  }

  test("pausing a tenant's queue starts nothing of theirs while other tenants' work goes on; resume lets it run") {
    assertEquals(asOperator("POST", "/api/platform/v1/ops/tenants/t_a/queue/pause").status, 204)
    val paused = submit(spentLimit = "PT1S").get("id").asText()
    val other  = submit(spentLimit = "PT1S", key = Some(keyB)).get("id").asText()
    assertEquals(awaitFinal(other, key = Some(keyB)).get("solverStatus").asText(), SolvingStatus.Completed)
    assertEquals(status(paused), SolvingStatus.Scheduled)
    assert(metadata(paused).get("queuePaused").asBoolean())
    assert(get("/api/aboutme").json.get("queuePaused").asBoolean())
    assertEquals(asOperator("POST", "/api/platform/v1/ops/tenants/t_a/queue/resume").status, 204)
    assertEquals(awaitFinal(paused).get("solverStatus").asText(), SolvingStatus.Completed)
  }

  test("force-terminate finishes any tenant's dataset now, with its best") {
    val id = submit(spentLimit = "PT120S", key = Some(keyB)).get("id").asText()
    val _  = eventually(60.seconds)(get(s"$es/schedules/$id", key = Some(keyB)).json)(_.get("modelOutput").isObject)
    val reply = asOperator("POST", s"/api/platform/v1/ops/datasets/$id/terminate")
    assertEquals(reply.json.get("solverStatus").asText(), SolvingStatus.Completed)
  }

  test("every operator action is in the audit trail, attributed") {
    val audit = asOperator("GET", "/api/platform/v1/ops/audit").json
    val actions = audit.elements().asScala.map(a => (a.get("subject").asText(), a.get("action").asText())).toSet
    assert(actions.contains(("op-1", "force-terminate")), actions.toString)
    assert(actions.contains(("op-1", "pause-queue")), actions.toString)
    assert(actions.contains(("op-1", "drain")), actions.toString)
  }

  test("metrics: queue depth, slots, workers and counters, for a scraper with the token only") {
    assertEquals(send("GET", "/metrics", key = None).status, 403)
    val text = send("GET", "/metrics", key = None, headers = Seq("Authorization" -> "Bearer scrape-me"))
    assertEquals(text.status, 200)
    Seq(
      "satisfactory_queued{model=\"employee-scheduling/v1\"}",
      "satisfactory_slots{state=\"busy\"}",
      "satisfactory_workers{state=\"ready\"}",
      "satisfactory_lease_losses_total",
      "satisfactory_solves_total{outcome=\"completed\"}",
      "satisfactory_webhook_failures_total"
    ).foreach(series => assert(text.body.contains(series), s"$series missing from\n${text.body}"))
  }
