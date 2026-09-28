package satisfactory.models.employeescheduling

import ai.timefold.solver.core.api.score.HardSoftBigDecimalScore
import ai.timefold.solver.core.config.solver.termination.TerminationConfig
import satisfactory.models.employeescheduling.domain.*
import satisfactory.spi.*

import java.time.Duration
import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

class EmployeeSchedulingSuite extends munit.FunSuite:

  override val munitTimeout = 2.minutes

  private val model   = EmployeeScheduling.V1
  private val runtime = ModelRuntime.of(model)
  private def small   = model.demoData().get(0).input().get()

  private def weights(pairs: (String, Long)*): java.util.Map[String, java.lang.Long] =
    pairs.map((k, v) => k -> java.lang.Long.valueOf(v)).toMap.asJava

  test("demo data is deterministic and valid against the model's own schema") {
    assertEquals(Json.string(small), Json.string(model.demoData().get(0).input().get()))
    assertEquals(runtime.validateInput(small).asScala.toList, Nil)
    val problem = runtime.problem(small, weights())
    assertEquals(runtime.validate(problem).status(), "OK")
  }

  test("an input decodes and encodes back to itself") {
    val problem = model.decodeProblem(small)
    assertEquals(Json.string(model.encodeSolution(problem)), Json.string(small))
  }

  test("a solution's assignments survive the round trip and become the baseline for disruption") {
    val solved = small.deepCopy().asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode]
    val first  = solved.get("employees").get(0).get("name").asText()
    val shifts = solved.withArray("shifts")
    shifts.elements().asScala.foreach(s =>
      s.asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode].put("employee", first)
    )
    val solution = model.decodeSolution(solved)
    assert(solution.getShifts.asScala.forall(_.getEmployee.getName == first))
    assertEquals(model.kpis(solution).get("disruptionPercentage").asDouble(), 0.0)
    solution.getShifts.get(0).setEmployee(solution.getEmployees.asScala.find(_.getName != first).get)
    assert(model.kpis(solution).get("disruptionPercentage").asDouble() > 0.0)
  }

  test("validation reports typed issues a schema cannot see") {
    val input = Json.parse(
      """{"employees":[{"name":"Ann","skills":["Nurse"]}],
        | "shifts":[{"id":"1","start":"2026-10-05T06:00","end":"2026-10-05T14:00","location":"A","requiredSkill":"Doctor","employee":"Bob"},
        |           {"id":"1","start":"2026-10-05T14:00","end":"2026-10-05T12:00","location":"A","requiredSkill":"Nurse"}]}""".stripMargin
    )
    val result = runtime.validate(runtime.problem(input, weights()))
    val codes  = result.issues().asScala.map(_.code()).toSet
    assertEquals(result.status(), "ERRORS")
    assertEquals(
      codes,
      Set("ShiftUnknownEmployee", "DuplicateShiftId", "ShiftEndsBeforeStart", "RequiredSkillUnknown")
    )
    val unknown = result.issues().asScala.find(_.code() == "ShiftUnknownEmployee").get
    assertEquals(unknown.fields().asScala.toMap, Map("shift" -> "1", "employee" -> "Bob"))
  }

  test("an empty schedule is invalid") {
    val result = runtime.validate(runtime.problem(Json.parse("""{"employees":[],"shifts":[]}"""), weights()))
    assertEquals(result.issues().asScala.map(_.code()).toList, List("EmptySchedule"))
  }

  private val oneBadShift = Json.parse(
    """{"employees":[{"name":"Ann","skills":["Nurse"]}],
      | "shifts":[{"id":"1","start":"2026-10-05T06:00","end":"2026-10-05T14:00","location":"A","requiredSkill":"Doctor","employee":"Ann"}]}""".stripMargin
  )

  test("weights scale a constraint, and zero disables it") {
    def scoreWith(w: java.util.Map[String, java.lang.Long]) =
      runtime.score(runtime.problem(oneBadShift, w)).score()
    assertEquals(scoreWith(weights()), "-1hard/0soft")
    assertEquals(scoreWith(weights("missingRequiredSkillWeight" -> 5L)), "-5hard/0soft")
    assertEquals(scoreWith(weights("missingRequiredSkillWeight" -> 0L)), "0hard/0soft")
  }

  test("ablation: each constraint's contribution, and together they are the score") {
    val problem  = runtime.problem(small, weights())
    val shifts   = problem.asInstanceOf[EmployeeSchedule].getShifts.asScala
    val emps     = problem.asInstanceOf[EmployeeSchedule].getEmployees.asScala
    shifts.zipWithIndex.foreach((s, i) => s.setEmployee(emps(i % emps.size)))
    val analysis = runtime.analyze(problem, weights("undesiredDayForEmployeeWeight" -> 3L))
    val total    = HardSoftBigDecimalScore.parseScore(analysis.score())
    val sum = analysis.constraints().asScala
      .map(c => HardSoftBigDecimalScore.parseScore(c.score()))
      .foldLeft(HardSoftBigDecimalScore.ZERO)(_.add(_))
    assertEquals(sum, total)
    assertEquals(analysis.constraints().size(), model.constraints().size())
    val undesired = analysis.constraints().asScala.find(_.name() == "Undesired day for employee").get
    assertEquals(undesired.weight(), "0hard/3soft")
    assertEquals(runtime.score(problem).score(), analysis.score(), "the solution's own weights are restored")
  }

  test("solving the small demo reaches a feasible schedule within seconds") {
    runtime.startSolving(1)
    try
      val problem = runtime.problem(small, weights())
      val done    = CompletableFuture[Object]()
      val _ = runtime.solve(
        "smoke",
        problem,
        TerminationConfig().withSpentLimit(Duration.ofSeconds(5)),
        _ => (),
        best => done.complete(best): Unit,
        (_, error) => done.completeExceptionally(error): Unit
      )
      val best = done.get(60, TimeUnit.SECONDS)
      val view = runtime.currentScore(best)
      assert(view.feasible(), view.score())
      assertEquals(model.kpis(best.asInstanceOf[EmployeeSchedule]).get("unassignedShifts").asInt(), 0)
    finally runtime.close()
  }
