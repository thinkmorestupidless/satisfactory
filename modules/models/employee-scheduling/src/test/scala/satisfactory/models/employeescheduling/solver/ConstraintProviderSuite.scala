package satisfactory.models.employeescheduling.solver

import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier
import satisfactory.models.employeescheduling.domain.*

import java.time.{LocalDate, LocalDateTime}
import scala.jdk.CollectionConverters.*

/** The ported constraints behave as the quickstart's own tests say they do. */
class ConstraintProviderSuite extends munit.FunSuite:

  private val verifier = ConstraintVerifier.build(
    EmployeeSchedulingConstraintProvider(),
    classOf[EmployeeSchedule],
    classOf[Shift]
  )

  private def employee(name: String, skills: Set[String], unavailable: Set[LocalDate] = Set.empty) =
    Employee(name, skills.asJava, unavailable.asJava, Set.empty[LocalDate].asJava, Set.empty[LocalDate].asJava)

  private def shift(id: String, start: String, end: String, skill: String, who: Employee) =
    Shift(id, LocalDateTime.parse(start), LocalDateTime.parse(end), "A", skill, who)

  test("a shift staffed without its required skill is penalised once") {
    val ann = employee("Ann", Set("Nurse"))
    verifier
      .verifyThat((p, f) => p.requiredSkill(f))
      .`given`(ann, shift("1", "2026-10-05T06:00", "2026-10-05T14:00", "Doctor", ann))
      .penalizes(1)
  }

  test("overlapping shifts are penalised per overlapping minute") {
    val ann = employee("Ann", Set("Nurse"))
    verifier
      .verifyThat((p, f) => p.noOverlappingShifts(f))
      .`given`(
        ann,
        shift("1", "2026-10-05T06:00", "2026-10-05T14:00", "Nurse", ann),
        shift("2", "2026-10-05T13:00", "2026-10-05T21:00", "Nurse", ann)
      )
      .penalizesBy(60)
  }

  test("working an unavailable day is penalised per minute of it") {
    val ann = employee("Ann", Set("Nurse"), Set(LocalDate.parse("2026-10-05")))
    verifier
      .verifyThat((p, f) => p.unavailableEmployee(f))
      .`given`(ann, shift("1", "2026-10-05T06:00", "2026-10-05T14:00", "Nurse", ann))
      .penalizesBy(480)
  }
