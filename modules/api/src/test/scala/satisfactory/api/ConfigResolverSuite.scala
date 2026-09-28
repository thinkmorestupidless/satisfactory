package satisfactory.api

import satisfactory.api.domain.{ConfigResolver, ProfileLayer}
import satisfactory.models.employeescheduling.EmployeeScheduling
import satisfactory.protocol.*
import satisfactory.spi.ModelRuntime

import scala.concurrent.duration.*

class ConfigResolverSuite extends munit.FunSuite:

  private val runtime = ModelRuntime.of(EmployeeScheduling.V1)

  private def request(spent: Option[String] = None, weights: Map[String, Long] = Map.empty, threads: Option[Int] = None) =
    Some(ModelConfiguration(
      run = Some(RunConfiguration(maxThreadCount = threads, termination = Some(TerminationConfig(spentLimit = spent)))),
      model = Some(ModelOverrides(Some(weights)))
    ))

  private def resolve(
      parent: Option[ResolvedConfig] = None,
      profile: Option[ProfileLayer] = None,
      req: Option[ModelConfiguration] = None,
      ceiling: FiniteDuration = 12.hours
  ) = ConfigResolver.resolve(runtime, parent, profile, req, ceiling)

  test("nothing set: the model's default weights and diminished-returns termination") {
    val c = resolve().toOption.get
    assertEquals(c.termination, ConfigResolver.DefaultTermination)
    assertEquals(c.weights.size, EmployeeScheduling.V1.constraints().size())
    assert(c.weights.values.forall(_ == 1L))
    assertEquals(c.maxThreadCount, 1)
  }

  test("priority: request over profile over parent over defaults") {
    val parent  = ResolvedConfig(TerminationConfig(spentLimit = Some("PT1M"), unimprovedSpentLimit = Some("PT10S")), Map("undesiredDayForEmployeeWeight" -> 2L, "desiredDayForEmployeeWeight" -> 2L), 1, None)
    val profile = ProfileLayer("cp_1", Some(RunConfiguration(termination = Some(TerminationConfig(spentLimit = Some("PT2M"))))), Map("undesiredDayForEmployeeWeight" -> 3L))
    val c = resolve(Some(parent), Some(profile), request(weights = Map("desiredDayForEmployeeWeight" -> 9L))).toOption.get
    assertEquals(c.termination.spentLimit, Some("PT2M"))
    assertEquals(c.termination.unimprovedSpentLimit, Some("PT10S"))
    assertEquals(c.weights("undesiredDayForEmployeeWeight"), 3L)
    assertEquals(c.weights("desiredDayForEmployeeWeight"), 9L)
    assertEquals(c.weights("missingRequiredSkillWeight"), 1L)
    assertEquals(c.configurationId, Some("cp_1"))
  }

  test("maximums are enforced last: threads capped at 1, spent limit at the lifetime ceiling") {
    val c = resolve(req = request(spent = Some("PT48H"), threads = Some(16)), ceiling = 12.hours).toOption.get
    assertEquals(c.maxThreadCount, 1)
    assertEquals(c.termination.spentLimit, Some("PT12H"))
  }

  test("unknown weights, negative weights and bad durations are refused with reasons") {
    val problems = resolve(req = Some(ModelConfiguration(
      run = Some(RunConfiguration(termination = Some(TerminationConfig(spentLimit = Some("five minutes"), minimumImprovementRatio = Some(2.0))))),
      model = Some(ModelOverrides(Some(Map("noSuchWeight" -> 1L, "missingRequiredSkillWeight" -> -1L))))
    ))).left.toOption.get
    assert(problems.exists(_.contains("spentLimit")), problems)
    assert(problems.exists(_.contains("minimumImprovementRatio")), problems)
    assert(problems.count(_.startsWith("config.model.overrides")) >= 2, problems)
  }
