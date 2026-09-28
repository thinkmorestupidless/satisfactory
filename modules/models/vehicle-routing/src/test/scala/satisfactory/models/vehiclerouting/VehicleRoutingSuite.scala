package satisfactory.models.vehiclerouting

import ai.timefold.solver.core.api.score.HardMediumSoftScore
import ai.timefold.solver.core.config.solver.termination.TerminationConfig
import satisfactory.models.vehiclerouting.domain.VehicleRoutePlan
import satisfactory.spi.*

import java.time.Duration
import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

class VehicleRoutingSuite extends munit.FunSuite:

  override val munitTimeout = 2.minutes

  private val model   = VehicleRouting.V1
  private val runtime = ModelRuntime.of(model)
  private def demo    = model.demoData().get(0).input().get()
  private def none    = java.util.Map.of[String, java.lang.Long]()

  test("demo data is deterministic, valid against its schema, and valid for the model") {
    assertEquals(Json.string(demo), Json.string(model.demoData().get(0).input().get()))
    assertEquals(runtime.validateInput(demo).asScala.toList, Nil)
    assertEquals(runtime.validate(runtime.problem(demo, none)).status(), "OK")
  }

  test("an input decodes and encodes back to itself") {
    assertEquals(Json.string(model.encodeSolution(model.decodeProblem(demo))), Json.string(demo))
  }

  test("validation reports unknown and doubly-routed visits, and inverted windows") {
    val input = Json.parse(
      """{"vehicles":[{"id":"a","capacity":10,"homeLocation":[40,-75],"departureTime":"2026-10-06T07:30","visits":["1","1","x"]}],
        | "visits":[{"id":"1","location":[40.1,-75.1],"demand":1,"minStartTime":"2026-10-06T12:00","maxEndTime":"2026-10-06T08:00","serviceDuration":"PT10M"}]}""".stripMargin
    )
    val codes = runtime.validate(runtime.problem(input, none)).issues().asScala.map(_.code()).toSet
    assertEquals(codes, Set("VisitRoutedTwice", "VisitUnknownInRoute", "TimeWindowInverted"))
  }

  test("weights: the medium level for unassigned visits scales and disables") {
    val problem = runtime.problem(demo, none)
    assert(runtime.score(problem).score().contains("medium"))
    val unassigned = HardMediumSoftScore.parseScore(runtime.score(problem).score()).mediumScore()
    assert(unassigned < 0, "nothing is routed yet")
    val doubled = runtime.problem(demo, java.util.Map.of("maximizeVisitsAssignedWeight", java.lang.Long.valueOf(2)))
    assertEquals(HardMediumSoftScore.parseScore(runtime.score(doubled).score()).mediumScore(), unassigned * 2)
    val off = runtime.problem(demo, java.util.Map.of("maximizeVisitsAssignedWeight", java.lang.Long.valueOf(0)))
    assertEquals(HardMediumSoftScore.parseScore(runtime.score(off).score()).mediumScore(), 0L)
  }

  test("solving routes every visit feasibly; the solution warm-starts and reports disruption; ablation sums to the score") {
    runtime.startSolving(1)
    try
      val done = CompletableFuture[Object]()
      val _ = runtime.solve("vrp", runtime.problem(demo, none), TerminationConfig().withSpentLimit(Duration.ofSeconds(5)),
        _ => (), best => done.complete(best): Unit, (_, e) => done.completeExceptionally(e): Unit)
      val best = done.get(60, TimeUnit.SECONDS)
      val view = runtime.currentScore(best)
      assert(view.feasible(), view.score())
      assertEquals(model.kpis(best.asInstanceOf[VehicleRoutePlan]).get("unassignedVisits").asInt(), 0)
      val output  = runtime.encode(best)
      val resumed = runtime.solution(output, none).asInstanceOf[VehicleRoutePlan]
      assertEquals(runtime.score(resumed).score(), view.score(), "the output decodes to the same plan")
      assertEquals(model.kpis(resumed).get("disruptionPercentage").asDouble(), 0.0)
      val analysis = runtime.analyze(resumed, none)
      val sum = analysis.constraints().asScala.map(c => HardMediumSoftScore.parseScore(c.score())).foldLeft(HardMediumSoftScore.ZERO)(_.add(_))
      assertEquals(sum, HardMediumSoftScore.parseScore(analysis.score()))
    finally runtime.close()
  }
