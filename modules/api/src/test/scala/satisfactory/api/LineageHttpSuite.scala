package satisfactory.api

import satisfactory.protocol.SolvingStatus
import satisfactory.spi.Json

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Story 3: change the problem without starting over (quickstart tier 3). */
class LineageHttpSuite extends ApiFixture:

  private def solved(spentLimit: String = "PT3S"): String =
    val id = submit(spentLimit = spentLimit).get("id").asText()
    val _  = awaitFinal(id)
    id

  private def firstEmployee: String = Json.parse(smallInput).at("/employees/0/name").asText()

  test("from-input: a new dataset with the parent's input and new weights; the parent is unchanged") {
    val parent = solved()
    val before = metadata(parent)
    val child = post(s"$es/schedules/$parent/from-input?name=reweighted",
      """{"config":{"model":{"overrides":{"desiredDayForEmployeeWeight":5}},"run":{"termination":{"spentLimit":"PT2S"}}}}""")
    assertEquals(child.status, 202, child.toString)
    val id = child.json.get("id").asText()
    assertEquals(child.json.get("parentId").asText(), parent)
    assertEquals(child.json.get("originId").asText(), parent)
    assertEquals(get(s"$es/schedules/$id/input").json, get(s"$es/schedules/$parent/input").json)
    assertEquals(get(s"$es/schedules/$id/config").json.at("/model/overrides/desiredDayForEmployeeWeight").asInt(), 5)
    val _ = awaitFinal(id)
    assertEquals(metadata(parent).get("seq").asLong(), before.get("seq").asLong())
  }

  test("from-input SOLVED continues from the parent's assignments; the chain keeps its origin") {
    val parent = solved()
    val child  = post(s"$es/schedules/$parent/from-input?select=SOLVED", """{"config":{"run":{"termination":{"spentLimit":"PT2S"}}}}""").json
    val grand  = awaitFinal(child.get("id").asText())
    val input  = get(s"$es/schedules/${child.get("id").asText()}/input").json
    assert(input.at("/shifts/0/employee").isTextual, "the child's input is the parent's solution")
    val next = post(s"$es/schedules/${child.get("id").asText()}/from-input", "").json
    assertEquals(next.get("originId").asText(), parent)
    assertEquals(next.get("parentId").asText(), child.get("id").asText())
    assert(grand.get("score").asText().startsWith("0hard"))
    val _ = awaitFinal(next.get("id").asText())
  }

  test("from-patch on a solved plan keeps assignments, reports disruption, and starts feasible (SC-014)") {
    val parent = solved()
    val patch =
      s"""{"patch":[{"op":"add","path":"/employees/[name=$firstEmployee]/desiredDates/-","value":"2026-10-06"}],
         | "config":{"run":{"termination":{"spentLimit":"PT3S"}}}}""".stripMargin
    val child = post(s"$es/schedules/$parent/from-patch", patch)
    assertEquals(child.status, 202, child.toString)
    val id    = child.json.get("id").asText()
    val input = get(s"$es/schedules/$id/input").json
    val ann   = input.get("employees").elements().asScala.find(_.get("name").asText() == firstEmployee).get
    assert(ann.get("desiredDates").elements().asScala.exists(_.asText() == "2026-10-06"))
    assert(input.at("/shifts/0/employee").isTextual)
    val done = awaitFinal(id)
    assertEquals(done.get("solverStatus").asText(), SolvingStatus.Completed)
    val result = get(s"$es/schedules/$id").json
    assert(result.at("/kpis/disruptionPercentage").isNumber, result.get("kpis").toString)
    assert(result.at("/kpis/disruptionPercentage").asDouble() < 50.0, result.get("kpis").toString)
  }

  test("a patch path that resolves to nothing is refused with the operation, and nothing is created") {
    val parent = solved("PT1S")
    val reply  = post(s"$es/schedules/$parent/from-patch", """{"patch":[{"op":"replace","path":"/employees/[name=Nobody]/skills","value":[]}]}""")
    assertEquals(reply.status, 400, reply.toString)
    assert(reply.json.at("/details/0").asText().startsWith("patch[0]"), reply.toString)
  }

  test("a patch that breaks the schema is refused before a dataset exists") {
    val parent = solved("PT1S")
    val reply  = post(s"$es/schedules/$parent/from-patch?select=UNSOLVED", """{"patch":[{"op":"replace","path":"/shifts","value":"none"}]}""")
    assertEquals(reply.status, 400, reply.toString)
    assertEquals(reply.json.get("code").asText(), "validation")
  }

  test("deriving from a solve in progress supersedes it; follow=lineage streams on into the child") {
    val parent = submit(spentLimit = "PT120S").get("id").asText()
    val _      = awaitStatus(parent, Set(SolvingStatus.Active))
    val _      = eventually()(get(s"$es/schedules/$parent").json)(_.get("modelOutput").isObject)
    val child  = post(s"$es/schedules/$parent/from-patch", """{"patch":[],"config":{"run":{"termination":{"spentLimit":"PT3S"}}}}""").json
    val childId = child.get("id").asText()
    val parentNow = awaitFinal(parent, 15.seconds)
    assertEquals(parentNow.get("solverStatus").asText(), SolvingStatus.Completed)
    assertEquals(parentNow.get("supersededBy").asText(), childId)
    assert(get(s"$es/schedules/$childId/input").json.at("/shifts/0/employee").isTextual, "the child starts from the parent's best")
    val followed = get(s"$es/schedules/$parent/events?follow=lineage")
    assertEquals(followed.status, 200)
    val frames = followed.body.linesIterator.filter(_.startsWith("data:"))
      .map(l => Json.parse(Json.parse(l.stripPrefix("data:").trim).asText())).filterNot(_.has("heartbeat")).toList
    assertEquals(frames.head.at("/metadata/id").asText(), parent)
    assertEquals(frames.last.at("/metadata/id").asText(), childId)
    assertEquals(frames.last.at("/metadata/solverStatus").asText(), SolvingStatus.Completed)
  }

  test("an invalid dataset can be fixed by patching its input") {
    val bad =
      """{"employees":[{"name":"Ann","skills":["Nurse"]}],
        | "shifts":[{"id":"s1","start":"2026-10-05T06:00","end":"2026-10-05T14:00","location":"A","requiredSkill":"Nurse","employee":"Zed"}]}""".stripMargin
    val id = submit(input = bad, spentLimit = "PT1S").get("id").asText()
    assertEquals(awaitFinal(id).get("solverStatus").asText(), SolvingStatus.DatasetInvalid)
    val fixed = post(s"$es/schedules/$id/from-patch?select=UNSOLVED", """{"patch":[{"op":"replace","path":"/shifts/[id=s1]/employee","value":null}],"config":{"run":{"termination":{"spentLimit":"PT1S"}}}}""")
    assertEquals(fixed.status, 202, fixed.toString)
    assertEquals(awaitFinal(fixed.json.get("id").asText()).get("solverStatus").asText(), SolvingStatus.Completed)
  }

  test("deriving from the solved state of a dataset without a solution is refused") {
    val bad = """{"employees":[],"shifts":[]}"""
    val id  = submit(input = bad, spentLimit = "PT1S").get("id").asText()
    val _   = awaitFinal(id)
    val reply = post(s"$es/schedules/$id/from-input?select=SOLVED", "")
    assertEquals(reply.status, 400, reply.toString)
    assertEquals(reply.json.get("code").asText(), "no-solution")
  }

  test("a derived dataset's configuration layers its own over the parent's") {
    val body   = submitBody(spentLimit = "PT1S", extra = ""","model":{"overrides":{"undesiredDayForEmployeeWeight":4}}""")
    val parent = post(s"$es/schedules", body).json.get("id").asText()
    val _      = awaitFinal(parent)
    val child  = post(s"$es/schedules/$parent/from-input", """{"config":{"model":{"overrides":{"desiredDayForEmployeeWeight":6}}}}""").json.get("id").asText()
    val config = get(s"$es/schedules/$child/config").json
    assertEquals(config.at("/model/overrides/undesiredDayForEmployeeWeight").asInt(), 4)
    assertEquals(config.at("/model/overrides/desiredDayForEmployeeWeight").asInt(), 6)
    assertEquals(config.at("/run/termination/spentLimit").asText(), "PT1S")
    val _ = awaitFinal(child)
  }

  test("vehicle routing: a patched plan continues from the solved routes (a list variable warm start)") {
    val vr     = "/api/models/vehicle-routing/v1"
    val input  = get(s"$vr/demo-data/PHILADELPHIA/input").body
    val parent = post(s"$vr/route-plans", s"""{"modelInput":$input,"config":{"run":{"termination":{"spentLimit":"PT3S"}}}}""").json.get("id").asText()
    val _      = eventually(90.seconds)(get(s"$vr/route-plans/$parent/metadata").json)(m => m.get("solverStatus").asText() == SolvingStatus.Completed)
    val patch =
      """{"patch":[{"op":"add","path":"/visits/-","value":{"id":"extra","name":"Late addition","location":[40.1,-75.5],"demand":1,
        |  "minStartTime":"2026-10-06T13:00","maxEndTime":"2026-10-06T18:00","serviceDuration":"PT10M"}}],
        | "config":{"run":{"termination":{"spentLimit":"PT3S"}}}}""".stripMargin
    val child = post(s"$vr/route-plans/$parent/from-patch", patch)
    assertEquals(child.status, 202, child.toString)
    val id   = child.json.get("id").asText()
    val done = eventually(90.seconds)(get(s"$vr/route-plans/$id/metadata").json)(m => SolvingStatus.terminal.contains(m.get("solverStatus").asText()))
    assertEquals(done.get("solverStatus").asText(), SolvingStatus.Completed)
    val kpis = get(s"$vr/route-plans/$id").json.get("kpis")
    assertEquals(kpis.get("unassignedVisits").asInt(), 0)
    assert(kpis.get("disruptionPercentage").asDouble() < 50.0, kpis.toString)
  }
