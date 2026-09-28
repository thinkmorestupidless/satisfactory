package satisfactory.protocol

import java.time.{Duration, Instant}

class SigningSuite extends munit.FunSuite:

  private val now  = Instant.parse("2026-09-28T10:00:00Z")
  private val body = """{"id":"evt_1"}""".getBytes

  test("a signature made with the secret verifies; a changed body or secret does not") {
    val sig = Signing.sign("s3cret", now.toString, body)
    assertEquals(Signing.verify("s3cret", Some(sig), Some(now.toString), body, now), Right(()))
    assertEquals(Signing.verify("s3cret", Some(sig), Some(now.toString), """{"id":"evt_2"}""".getBytes, now), Left(Signing.Refusal.Mismatch))
    assertEquals(Signing.verify("other", Some(sig), Some(now.toString), body, now), Left(Signing.Refusal.Mismatch))
  }

  test("the timestamp is signed: a replay with a fresh timestamp fails, and a stale one is refused") {
    val sig = Signing.sign("s3cret", now.toString, body)
    val later = now.plusSeconds(600)
    assertEquals(Signing.verify("s3cret", Some(sig), Some(later.toString), body, later), Left(Signing.Refusal.Mismatch))
    assertEquals(Signing.verify("s3cret", Some(sig), Some(now.toString), body, later), Left(Signing.Refusal.Stale))
    assertEquals(Signing.verify("s3cret", None, Some(now.toString), body, now), Left(Signing.Refusal.Missing))
    assertEquals(Signing.verify("s3cret", Some(sig), Some(now.toString), body, now.plusSeconds(60), Duration.ofMinutes(5)), Right(()))
  }

  test("custom headers take the signature and timestamp by placeholder") {
    assertEquals(Signing.substitute("t={hmac_timestamp},v1={hmac_signature}", "abc", "2026"), "t=2026,v1=abc")
  }

  test("event types: exact names and dataset.*") {
    assert(EventTypes.matches(List("dataset.*"), EventTypes.Completed))
    assert(!EventTypes.matches(List("dataset.*"), EventTypes.Failing))
    assert(EventTypes.matches(List(EventTypes.Failing), EventTypes.Failing))
    assertEquals(EventTypes.forStatus(SolvingStatus.Active), None)
  }
