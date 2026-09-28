package satisfactory.runner

import satisfactory.models.employeescheduling.EmployeeScheduling
import satisfactory.protocol.*
import satisfactory.spi.{Json, ModelCatalog}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class WorkerSuite extends munit.FunSuite:

  override val munitTimeout = 2.minutes

  private val small = Json.bytes(EmployeeScheduling.V1.demoData().get(0).input().get())

  private def eventually(timeout: FiniteDuration)(condition: => Boolean): Unit =
    val deadline = System.nanoTime() + timeout.toNanos
    while !condition && System.nanoTime() < deadline do Thread.sleep(100)
    assert(condition, s"not within $timeout")

  // docs:start queue
  test("one slot works through a queue one dataset at a time") {
    val channel = ScriptedChannel()
    channel.enqueue(ScriptedChannel.claim("ds_a", spentLimit = "PT1S"), small)
    channel.enqueue(ScriptedChannel.claim("ds_b", spentLimit = "PT1S"), small)
    val worker = Worker(
      WorkerConfig("w1", slots = 1, claimBackoff = 100.millis, heartbeat = 500.millis),
      ModelCatalog.of(EmployeeScheduling.V1),
      channel
    )
    worker.start()
    try eventually(60.seconds)(channel.completes.size == 2)
    finally worker.stop()
    assertEquals(channel.completes.asScala.map(_._1).toSet, Set("ds_a", "ds_b"))
    assert(channel.calls.asScala.contains("register w1"))
    assert(channel.calls.asScala.contains("deregister w1"))
  }
  // docs:end queue

  test("stopping a worker releases what it holds before it deregisters") {
    val channel = ScriptedChannel()
    channel.enqueue(ScriptedChannel.claim("ds_long", spentLimit = "PT120S"), small)
    val worker = Worker(
      WorkerConfig("w2", slots = 1, claimBackoff = 100.millis, heartbeat = 500.millis),
      ModelCatalog.of(EmployeeScheduling.V1),
      channel
    )
    worker.start()
    eventually(30.seconds)(channel.reports.size > 0)
    worker.stop()
    assertEquals(channel.releases.asScala.map(_._1).toList, List("ds_long"))
    val calls = channel.calls.asScala.toList
    assert(calls.indexOf("release ds_long shutdown") < calls.indexOf("deregister w2"), calls.toString)
  }
