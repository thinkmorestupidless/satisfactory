package satisfactory.api

import satisfactory.protocol.SolvingStatus

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Story 1: submit a planning problem and get a solution (quickstart tier 3). */
class SubmitHttpSuite extends ApiFixture:

  test("the catalog describes the model: schemas, constraints with defaults, KPIs, issue types") {
    val model = get(s"$es/model").json
    assertEquals(model.get("id").asText(), "employee-scheduling")
    assertEquals(model.get("entity").asText(), "schedules")
    assertEquals(model.get("maturity").asText(), "STABLE")
    assert(model.at("/schemas/input/properties/shifts").isObject)
    assert(model.at("/schemas/overrides/properties/missingRequiredSkillWeight").isObject)
    val constraints = model.get("constraints").elements().asScala.map(_.get("key").asText()).toList
    assert(constraints.contains("undesiredDayForEmployee"), constraints)
    assert(model.get("kpis").elements().asScala.exists(_.get("id").asText() == "disruptionPercentage"))
    assert(model.get("issueTypes").elements().asScala.exists(_.get("code").asText() == "ShiftUnknownEmployee"))
  }

  test("demo datasets are listed and retrievable as a request or as input only") {
    val list = get(s"$es/demo-data").json
    assertEquals(list.elements().asScala.map(_.get("id").asText()).toList, List("SMALL", "LARGE"))
    val full = get(s"$es/demo-data/SMALL").json
    assert(full.at("/modelInput/shifts").isArray)
    assert(full.at("/config/run/termination/spentLimit").isTextual)
    assertEquals(get(s"$es/demo-data/NOPE").status, 404)
  }

  test("submit → poll → fetch: a feasible schedule with score, KPIs and input metrics (SC-001)") {
    val started = System.nanoTime()
    val created = submit(spentLimit = "PT3S", query = "?name=week-39&tags=site:leeds&tags=weekly")
    assertEquals(created.get("solverStatus").asText(), SolvingStatus.Scheduled)
    assertEquals(created.get("name").asText(), "week-39")
    val id    = created.get("id").asText()
    val final_ = awaitFinal(id, 60.seconds)
    assert((System.nanoTime() - started).nanos < 60.seconds)
    assertEquals(final_.get("solverStatus").asText(), SolvingStatus.Completed)
    assert(final_.get("completeDateTime").isTextual)
    assert(final_.get("startDateTime").isTextual && final_.get("activeDateTime").isTextual)
    val dataset = get(s"$es/schedules/$id").json
    assert(dataset.at("/metadata/score").asText().startsWith("0hard/"), dataset.at("/metadata/score").toString)
    assert(dataset.at("/modelOutput/shifts/0/employee").isTextual)
    assertEquals(dataset.at("/kpis/unassignedShifts").asInt(), 0)
    assert(dataset.at("/inputMetrics/shifts").asInt() > 0)
    assertEquals(get(s"$es/schedules/$id/validation-result").json.get("status").asText(), "OK")
    assert(get(s"$es/schedules/$id/logs").json.get("details").asText().contains("solving"))
  }

  test("acceptance is answered within 2 seconds (SC-002)") {
    val started = System.nanoTime()
    val id      = submit(spentLimit = "PT1S").get("id").asText()
    assert((System.nanoTime() - started).nanos < 2.seconds)
    val _ = awaitFinal(id)
  }

  test("a body that does not match the input schema is refused with the reasons, and nothing is created") {
    val before = get(s"$es/schedules?size=200").json.get("content").size()
    val reply  = post(s"$es/schedules", """{"modelInput":{"employees":[{"skills":[]}],"shifts":"no"}}""")
    assertEquals(reply.status, 400, reply.toString)
    assertEquals(reply.json.get("code").asText(), "validation")
    assert(reply.json.get("details").size() >= 2, reply.toString)
    val after = get(s"$es/schedules?size=200").json.get("content").size()
    assertEquals(after, before)
  }

  test("a schema-valid dataset the model rejects ends DATASET_INVALID with typed issues") {
    val input =
      """{"employees":[{"name":"Ann","skills":["Nurse"]}],
        | "shifts":[{"id":"s-17","start":"2026-10-05T06:00","end":"2026-10-05T14:00","location":"A","requiredSkill":"Nurse","employee":"e-9"}]}""".stripMargin
    val id    = submit(input = input).get("id").asText()
    val final_ = awaitFinal(id)
    assertEquals(final_.get("solverStatus").asText(), SolvingStatus.DatasetInvalid)
    assertEquals(final_.at("/validationResult/errors/0").asText(), "ShiftUnknownEmployee")
    val result = get(s"$es/schedules/$id/validation-result").json
    assertEquals(result.get("status").asText(), "ERRORS")
    val issue = result.get("issues").elements().asScala.find(_.get("code").asText() == "ShiftUnknownEmployee").get
    assertEquals((issue.get("shift").asText(), issue.get("employee").asText()), ("s-17", "e-9"))
  }

  test("operation=NONE validates and scores the plan as submitted, and POST /{id} solves it later") {
    val id       = submit(query = "?operation=NONE").get("id").asText()
    val computed = awaitStatus(id, Set(SolvingStatus.DatasetComputed))
    assert(computed.get("score").isTextual, computed.toString)
    Thread.sleep(500)
    assertEquals(status(id), SolvingStatus.DatasetComputed, "computed is final for NONE")
    val solving = post(s"$es/schedules/$id?priority=7", "")
    assertEquals(solving.status, 202, solving.toString)
    assertEquals(solving.json.get("solverStatus").asText(), SolvingStatus.Scheduled)
    assertEquals(awaitFinal(id).get("solverStatus").asText(), SolvingStatus.Completed)
    assertEquals(post(s"$es/schedules/$id", "").status, 400, "only a computed NONE dataset can be solved")
  }

  test("a dataset still solving answers with its best so far, quickly (SC-013)") {
    val id = submit(spentLimit = "PT20S").get("id").asText()
    val _  = awaitStatus(id, Set(SolvingStatus.Active))
    val _  = eventually()(get(s"$es/schedules/$id").json)(_.get("modelOutput").isObject)
    val started = System.nanoTime()
    val reply   = get(s"$es/schedules/$id")
    assert((System.nanoTime() - started).nanos < 1.second)
    assertEquals(reply.json.at("/metadata/solverStatus").asText(), SolvingStatus.Active)
  }

  test("an Idempotency-Key submitted twice creates one dataset") {
    val headers = Seq("Idempotency-Key" -> "retry-1")
    val first   = post(s"$es/schedules", submitBody(spentLimit = "PT1S"), headers = headers)
    val second  = post(s"$es/schedules", submitBody(spentLimit = "PT1S"), headers = headers)
    assertEquals((first.status, second.status), (202, 202))
    assertEquals(second.json.get("id").asText(), first.json.get("id").asText())
    val other = post(s"$es/schedules", submitBody(spentLimit = "PT1S"), key = Some(keyB), headers = headers)
    assertNotEquals(other.json.get("id").asText(), first.json.get("id").asText(), "keys are per tenant")
  }

  test("no key or a wrong key is 401; a read-only key cannot submit") {
    assertEquals(get(s"$es/schedules", key = None).status, 401)
    assertEquals(get(s"$es/schedules", key = Some("sk_nope")).status, 401)
    assertEquals(get(s"$es/schedules", key = Some(keyAReadOnly)).status, 200)
    val refused = post(s"$es/schedules", submitBody(), key = Some(keyAReadOnly))
    assertEquals(refused.status, 403, refused.toString)
  }

  test("another tenant's dataset does not exist for you") {
    val id = submit(spentLimit = "PT1S").get("id").asText()
    assertEquals(get(s"$es/schedules/$id", key = Some(keyB)).status, 404)
    assertEquals(get(s"$es/schedules/$id/metadata", key = Some(keyB)).status, 404)
    assertEquals(post(s"$es/schedules/$id", "", key = Some(keyB)).status, 404)
    assert(!get(s"$es/schedules?size=200", key = Some(keyB)).body.contains(id))
  }

  test("the request, the input and the resolved configuration are what was submitted and resolved") {
    val body  = submitBody(spentLimit = "PT2S", extra = ""","model":{"overrides":{"undesiredDayForEmployeeWeight":7}}""")
    val reply = post(s"$es/schedules", body)
    val id    = reply.json.get("id").asText()
    val config = get(s"$es/schedules/$id/config").json
    assertEquals(config.at("/run/termination/spentLimit").asText(), "PT2S")
    assertEquals(config.at("/run/maxThreadCount").asInt(), 1)
    assertEquals(config.at("/model/overrides/undesiredDayForEmployeeWeight").asInt(), 7)
    assertEquals(config.at("/model/overrides/missingRequiredSkillWeight").asInt(), 1)
    assertEquals(get(s"$es/schedules/$id/input").json, get(s"$es/demo-data/SMALL/input").json)
    assertEquals(get(s"$es/schedules/$id/model-request").json.at("/config/model/overrides/undesiredDayForEmployeeWeight").asInt(), 7)
    val _ = awaitFinal(id)
  }

  test("weights outside the schema, or a thread count over the plan's, are handled") {
    val bad = post(s"$es/schedules", submitBody(extra = ""","model":{"overrides":{"noSuchWeight":1}}"""))
    assertEquals(bad.status, 400, bad.toString)
    val capped = post(s"$es/schedules", s"""{"modelInput":$smallInput,"config":{"run":{"maxThreadCount":8,"termination":{"spentLimit":"PT1S"}}}}""")
    val id = capped.json.get("id").asText()
    assertEquals(get(s"$es/schedules/$id/config").json.at("/run/maxThreadCount").asInt(), 1)
    val _ = awaitFinal(id)
  }

  test("a gzip body is accepted; one over 100 MB compressed is refused (SC-012)") {
    val zipped = gzip(submitBody(spentLimit = "PT1S").getBytes("UTF-8"))
    val reply  = send("POST", s"$es/schedules", Some(zipped), headers = Seq("Content-Encoding" -> "gzip"))
    assertEquals(reply.status, 202, reply.toString)
    val huge = send("POST", s"$es/schedules", Some(new Array[Byte](101 * 1024 * 1024)))
    assertEquals(huge.status, 413, huge.toString.take(200))
    val _ = awaitFinal(reply.json.get("id").asText())
  }

  test("the listing pages and filters by status and tag") {
    val tagged = submit(spentLimit = "PT1S", query = "?tags=listing-test").get("id").asText()
    val _      = awaitFinal(tagged)
    val byTag  = eventually()(get(s"$es/schedules?tag=listing-test").json)(_.get("content").size() == 1)
    assertEquals(byTag.at("/content/0/id").asText(), tagged)
    val byStatus = get(s"$es/schedules?status=SOLVING_COMPLETED&size=1").json
    assertEquals(byStatus.get("content").size(), 1)
    assert(byStatus.get("hasNext").asBoolean())
  }

  test("aboutme names the tenant and the key's role") {
    val me = get("/api/aboutme", key = Some(keyAReadOnly)).json
    assertEquals(me.get("tenantId").asText(), "t_a")
    assertEquals(me.get("role").asText(), "read-only")
  }

  test("vehicle routing: its own entity name, its demo data, a feasible routed plan") {
    val vr = "/api/models/vehicle-routing/v1"
    assertEquals(get(s"$vr/model").json.get("entity").asText(), "route-plans")
    val input = get(s"$vr/demo-data/PHILADELPHIA/input").body
    val reply = post(s"$vr/route-plans", s"""{"modelInput":$input,"config":{"run":{"termination":{"spentLimit":"PT4S"}}}}""")
    assertEquals(reply.status, 202, reply.toString)
    val id = reply.json.get("id").asText()
    val done = eventually(90.seconds)(get(s"$vr/route-plans/$id/metadata").json)(m => SolvingStatus.terminal.contains(m.get("solverStatus").asText()))
    assertEquals(done.get("solverStatus").asText(), SolvingStatus.Completed)
    assert(done.get("score").asText().startsWith("0hard/0medium/"), done.get("score").asText())
    val plan = get(s"$vr/route-plans/$id").json
    assertEquals(plan.at("/kpis/unassignedVisits").asInt(), 0)
    assert(plan.at("/modelOutput/vehicles/0/visits").isArray)
    assertEquals(get(s"$es/schedules/$id").status, 404, "a dataset belongs to its model's API")
  }
