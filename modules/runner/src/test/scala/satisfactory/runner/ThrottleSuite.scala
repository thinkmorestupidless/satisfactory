package satisfactory.runner

import scala.concurrent.duration.*

class ThrottleSuite extends munit.FunSuite:

  private class Clock:
    var now = 0L
    def advance(d: FiniteDuration): Unit = now += d.toNanos

  test("the first value goes at once; later ones wait for the interval") {
    val clock    = Clock()
    val throttle = Throttle[Int](1.second, () => clock.now)
    throttle.offer(1)
    assertEquals(throttle.takeIfDue(), Some(1))
    throttle.offer(2)
    assertEquals(throttle.takeIfDue(), None)
    clock.advance(1.second)
    assertEquals(throttle.takeIfDue(), Some(2))
  }

  test("latest wins: of many offers within an interval, only the last is taken") {
    val clock    = Clock()
    val throttle = Throttle[Int](1.second, () => clock.now)
    throttle.offer(0)
    val _ = throttle.takeIfDue()
    (1 to 100).foreach(throttle.offer)
    clock.advance(1.second)
    assertEquals(throttle.takeIfDue(), Some(100))
    assertEquals(throttle.takeIfDue(), None)
  }

  test("the final value is always taken, interval or not") {
    val clock    = Clock()
    val throttle = Throttle[Int](1.second, () => clock.now)
    throttle.offer(1)
    val _ = throttle.takeIfDue()
    throttle.offer(2)
    assertEquals(throttle.takeFinal(), Some(2))
    assertEquals(throttle.takeFinal(), None)
  }

  test("the worker never reports more often than every 250 ms") {
    assertEquals(WorkerConfig("w", minReportInterval = 10.millis).reportInterval, 250.millis)
  }
