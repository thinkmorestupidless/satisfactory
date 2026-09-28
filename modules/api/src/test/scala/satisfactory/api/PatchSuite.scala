package satisfactory.api

import satisfactory.api.domain.Patch
import satisfactory.protocol.{PatchOp, RawJson}
import satisfactory.spi.Json

class PatchSuite extends munit.FunSuite:

  private val doc = Json.parse(
    """{"employees":[{"name":"Ann","skills":["Nurse"]},{"name":"Bob","skills":["Doctor"]}],"shifts":[{"id":"1"}]}"""
  )

  private def op(o: String, path: String, value: String = null) =
    PatchOp(o, path, Option(value).map(RawJson(_)).getOrElse(PatchOp.Absent))

  test("[field=value] selects an array element; - appends") {
    val out = Patch(doc, List(
      op("add", "/employees/[name=Ann]/skills/-", "\"Doctor\""),
      op("add", "/shifts/-", """{"id":"2"}"""),
      op("replace", "/employees/[name=Bob]/skills", """["Nurse"]""")
    )).toOption.get
    assertEquals(Json.string(out.at("/employees/0/skills")), """["Nurse","Doctor"]""")
    assertEquals(out.get("shifts").size(), 2)
    assertEquals(Json.string(out.at("/employees/1/skills")), """["Nurse"]""")
    assertEquals(Json.string(doc.at("/employees/0/skills")), """["Nurse"]""", "the original is untouched")
  }

  test("remove by match and by index") {
    val out = Patch(doc, List(op("remove", "/employees/[name=Ann]"), op("remove", "/shifts/0"))).toOption.get
    assertEquals(out.get("employees").size(), 1)
    assertEquals(out.at("/employees/0/name").asText(), "Bob")
    assertEquals(out.get("shifts").size(), 0)
  }

  test("a path that resolves to nothing is refused, naming the operation") {
    val error = Patch(doc, List(op("add", "/shifts/-", "{}"), op("replace", "/employees/[name=Zed]/skills", "[]"))).left.toOption.get
    assertEquals(error.index, 1)
    assert(error.describe.contains("does not resolve"), error.describe)
    assert(Patch(doc, List(op("move", "/shifts/0"))).isLeft)
    assert(Patch(doc, List(op("add", "/shifts/-"))).isLeft, "add needs a value")
    val nulled = Patch(doc, List(op("replace", "/shifts/0/id", "null"))).toOption.get
    assert(nulled.at("/shifts/0/id").isNull, "an explicit null is a value")
  }
