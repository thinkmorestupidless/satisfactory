package satisfactory.protocol

import satisfactory.protocol.WireCodecs.given

import java.time.Instant

class ProtocolSuite extends munit.FunSuite:

  test("raw JSON passes through a wire type byte for byte") {
    val request = WireCodecs.decode[SubmitRequest](
      """{"modelInput":{"employees":[{"name":"Ann"}],"shifts":[]},"config":{"run":{"termination":{"spentLimit":"PT5S"}}}}"""
    )
    assertEquals(request.modelInput.asString, """{"employees":[{"name":"Ann"}],"shifts":[]}""")
    assertEquals(request.config.flatMap(_.run).flatMap(_.termination).flatMap(_.spentLimit), Some("PT5S"))
    val again = WireCodecs.decode[SubmitRequest](WireCodecs.encode(request))
    assertEquals(again, request)
  }

  test("metadata writes absent values as null, as Timefold does") {
    val m = Metadata(
      "ds_1", None, None, Some("week-39"), List("a"), SolvingStatus.Scheduled, None,
      Some(Instant.parse("2026-09-28T10:00:00Z")), None, None, None, None, None, None, None, None, 3L
    )
    val json = WireCodecs.encodeString(m)
    assert(json.contains("\"parentId\":null"), json)
    assert(json.contains("\"solverStatus\":\"SOLVING_SCHEDULED\""), json)
    assertEquals(WireCodecs.decode[Metadata](json), m)
  }

  test("score keys order level by level, most significant first") {
    val a = ScoreKey(List(BigDecimal(0), BigDecimal(-10)))
    val b = ScoreKey(List(BigDecimal(-1), BigDecimal(0)))
    val c = ScoreKey(List(BigDecimal(0), BigDecimal(-9)))
    assert(a.isBetterThan(b))
    assert(c.isBetterThan(a))
    assert(!a.isBetterThan(a))
  }

  test("ids carry their prefix and sort by creation time") {
    val early = Ids.ulid(1_000_000L)
    val late  = Ids.ulid(2_000_000L)
    assertEquals(early.length, 26)
    assert(early < late)
    assert(Ids.dataset().startsWith("ds_"))
    assertEquals(BlobRefs.datasetOf(BlobRefs.solution("ds_X", 2, 7)), "ds_X")
  }
