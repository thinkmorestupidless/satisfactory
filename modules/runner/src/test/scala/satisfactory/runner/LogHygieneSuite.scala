package satisfactory.runner

class LogHygieneSuite extends munit.FunSuite:
  test("plain lines pass; anything that may quote JSON or is body-sized is redacted") {
    assertEquals(LogHygiene.clean("solving"), "solving")
    assert(LogHygiene.clean("""Unrecognized field "name" at [Source: {"employees":...""").startsWith("[redacted"))
    assert(LogHygiene.clean("x" * 600).startsWith("[redacted"))
  }
