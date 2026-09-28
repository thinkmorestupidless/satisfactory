package satisfactory.api

import ai.timefold.solver.core.api.score.HardSoftBigDecimalScore
import satisfactory.protocol.SolvingStatus

import scala.jdk.CollectionConverters.*

/** Story 7: understand and trust the result (quickstart tier 3). */
class AnalysisHttpSuite extends ApiFixture:

  private def parse(score: String) = HardSoftBigDecimalScore.parseScore(score)

  private lazy val solvedPlan: (String, String) =
    val id = submit(spentLimit = "PT2S").get("id").asText()
    val _  = awaitFinal(id)
    (id, get(s"$es/schedules/$id").json.get("modelOutput").toString)

  test("a solved dataset's analysis lists every constraint with its weight and contribution, summing to the score") {
    val (id, _)  = solvedPlan
    val analysis = get(s"$es/schedules/$id/score-analysis").json
    assertEquals(analysis.get("score").asText(), metadata(id).get("score").asText())
    val constraints = analysis.get("constraints").elements().asScala.toList
    assertEquals(constraints.size, 8)
    constraints.foreach(c => assert(c.has("name") && c.has("weight") && c.has("score") && !c.has("matchCount"), c.toString))
    val sum = constraints.map(c => parse(c.get("score").asText())).foldLeft(HardSoftBigDecimalScore.ZERO)(_.add(_))
    assertEquals(sum, parse(analysis.get("score").asText()))
    assert(!analysis.has("justifications") || analysis.get("justifications").isNull)
  }

  test("asking for justifications is answered, not refused: this edition does not have them") {
    val (id, _) = solvedPlan
    assertEquals(get(s"$es/schedules/$id/score-analysis?includeJustifications=true").json.get("justifications").asText(), "unsupported")
  }

  test("a plan the caller already has is scored without creating a dataset, against its own weights") {
    val (_, plan) = solvedPlan
    val before    = get(s"$es/schedules?size=200").json.get("content").size()
    val plain     = post(s"$es/schedules/score-analysis", s"""{"modelInput":$plan}""")
    assertEquals(plain.status, 200, plain.toString)
    val weighted = post(s"$es/schedules/score-analysis", s"""{"modelInput":$plan,"config":{"model":{"overrides":{"desiredDayForEmployeeWeight":0}}}}""").json
    val desired = weighted.get("constraints").elements().asScala.find(_.get("name").asText() == "Desired day for employee").get
    assertEquals(parse(desired.get("score").asText()), HardSoftBigDecimalScore.ZERO)
    assertEquals(get(s"$es/schedules?size=200").json.get("content").size(), before)
  }

  test("operation=NONE scores the plan as submitted, so the gain from solving can be measured") {
    val (_, plan) = solvedPlan
    val id        = submit(query = "?operation=NONE", input = plan).get("id").asText()
    val computed  = awaitStatus(id, Set(SolvingStatus.DatasetComputed))
    val stateless = post(s"$es/schedules/score-analysis", s"""{"modelInput":$plan}""").json
    assertEquals(computed.get("score").asText(), stateless.get("score").asText())
    val dataset = get(s"$es/schedules/$id").json
    assert(dataset.at("/inputMetrics/employees").asInt() > 0)
    assertEquals(get(s"$es/schedules/$id/score-analysis").json.get("score").asText(), stateless.get("score").asText())
  }

  test("KPIs and input metrics are declared fields with titles, descriptions, priorities and examples") {
    val model = get(s"$es/model").json
    (model.get("kpis").elements().asScala ++ model.get("inputMetrics").elements().asScala).foreach { m =>
      List("id", "title", "description", "type", "priority", "example").foreach(f => assert(m.has(f), s"$f missing in $m"))
    }
    val types = get(s"$es/schedules/validation-issue-types").json.get("issueTypes")
    assert(types.size() > 0)
    assertEquals(get(s"$es/schedules/validation-issue-types/EmptySchedule").json.get("severity").asText(), "ERROR")
    assertEquals(get(s"$es/schedules/validation-issue-types/Nope").status, 404)
  }

  test("logs are the owner's, and never quote the dataset") {
    val (id, _) = solvedPlan
    val logs    = get(s"$es/schedules/$id/logs").json.get("details").asText()
    assert(logs.contains("solving"), logs)
    assert(!logs.contains("shifts") && !logs.contains("employees"), logs)
    assertEquals(get(s"$es/schedules/$id/logs", key = Some(keyB)).status, 404)
  }
