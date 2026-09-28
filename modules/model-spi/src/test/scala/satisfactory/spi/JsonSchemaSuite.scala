package satisfactory.spi

import scala.jdk.CollectionConverters.*

class JsonSchemaSuite extends munit.FunSuite:

  private val schema = JsonSchema.of(
    Json.parse(
      """{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","required":["name"],
        |"properties":{"name":{"type":"string"},"day":{"type":"string","format":"date"}}}""".stripMargin
    )
  )

  test("a valid instance has no violations") {
    assertEquals(schema.validate(Json.parse("""{"name":"Ann","day":"2026-10-05"}""")).asScala.toList, Nil)
  }

  test("violations name what is wrong, and formats are asserted") {
    val errors = schema.validate(Json.parse("""{"day":"not-a-date"}""")).asScala.toList
    assertEquals(errors.size, 2, errors)
    assert(errors.exists(_.contains("name")), errors)
    assert(errors.exists(_.contains("date")), errors)
  }

  test("the overrides schema has one integer weight per constraint and nothing else") {
    val constraints = java.util.List.of(
      ConstraintInfo("Missing skill", "missingSkill", "d", ConstraintInfo.Level.HARD, 1),
      ConstraintInfo("Undesired day", "undesiredDay", "d", ConstraintInfo.Level.SOFT, 1)
    )
    val overrides = JsonSchema.overridesFor(constraints)
    assertEquals(overrides.validate(Json.parse("""{"missingSkillWeight":3,"undesiredDayWeight":0}""")).size, 0)
    assertEquals(overrides.validate(Json.parse("""{"missingSkillWeight":-1}""")).size, 1)
    assertEquals(overrides.validate(Json.parse("""{"somethingElseWeight":1}""")).size, 1)
    assertEquals(overrides.validate(Json.parse("""{"missingSkillWeight":1.5}""")).size, 1)
  }
