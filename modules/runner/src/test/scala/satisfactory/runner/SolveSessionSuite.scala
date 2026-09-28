package satisfactory.runner

import satisfactory.models.employeescheduling.EmployeeScheduling
import satisfactory.protocol.*
import satisfactory.spi.{Json, ModelCatalog}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class SolveSessionSuite extends munit.FunSuite:

  override val munitTimeout = 2.minutes

  private val catalog = ModelCatalog.of(EmployeeScheduling.V1)
  private val runtime = catalog.byKey("employee-scheduling/v1").toScala.get
  private val small   = Json.bytes(EmployeeScheduling.V1.demoData().get(0).input().get())
  private val config  = WorkerConfig("w-test", minReportInterval = 250.millis, heartbeat = 500.millis)

  override def beforeAll(): Unit = runtime.startSolving(2)
  override def afterAll(): Unit  = runtime.close()

  private def session(channel: ScriptedChannel, claim: ClaimResponse, stopping: () => Option[String] = () => None) =
    channel.enqueue(claim, small)
    val _ = channel.claim(ClaimRequest("w-test", Nil))
    SolveSession(claim, runtime, channel, config, stopping)

  test("operation NONE validates, scores and stops without solving") {
    val channel = ScriptedChannel()
    val outcome = session(channel, ScriptedChannel.claim("ds_none", operation = Operation.None)).run()
    assertEquals(outcome, SolveSession.Outcome.Computed)
    assertEquals(channel.phases.asScala.map(_._2.phase).toList, List(Phase.Validated, Phase.Computed))
    val computed = channel.phases.asScala.last._2
    assert(computed.inputScore.isDefined)
    assert(computed.inputMetrics.exists(_.asString.contains("\"shifts\"")))
    assert(channel.blobs.containsKey(computed.analysisRef.get))
    assert(channel.reports.isEmpty)
  }

  test("a solve walks the phases, reports improving scores, and completes with the final best") {
    val channel = ScriptedChannel()
    val outcome = session(channel, ScriptedChannel.claim("ds_solve", spentLimit = "PT3S")).run()
    assert(outcome.isInstanceOf[SolveSession.Outcome.Completed], outcome.toString)
    assertEquals(
      channel.phases.asScala.map(_._2.phase).toList,
      List(Phase.Validated, Phase.Computed, Phase.Started, Phase.Active)
    )
    val reports = channel.reports.asScala.toList
    assert(reports.nonEmpty)
    assert(reports.size < 3 * 4 + 2, s"throttled: ${reports.size} reports in 3s")
    assert(reports.last.feasible, reports.last.score)
    reports.foreach(r => assert(channel.blobs.containsKey(r.solutionRef), r.solutionRef))
    assertEquals(channel.completes.asScala.map(_._2.reason).toList, List(CompleteReason.Termination))
  }

  test("a terminate in the heartbeat reply stops the solve early and completes as terminated") {
    val channel = ScriptedChannel()
    channel.heartbeatReply.set(HeartbeatResponse(terminate = true, force = false, 20_000, drain = false))
    val started = System.nanoTime()
    val outcome = session(channel, ScriptedChannel.claim("ds_term", spentLimit = "PT60S")).run()
    val took    = (System.nanoTime() - started).nanos
    assert(took < 20.seconds, s"took $took")
    assertEquals(outcome.asInstanceOf[SolveSession.Outcome.Completed].reason, CompleteReason.Terminated)
  }

  test("a stale answer stops the session without completing") {
    val channel = ScriptedChannel()
    channel.staleAfterReports.put("ds_stale", 1): Unit
    val outcome = session(channel, ScriptedChannel.claim("ds_stale", spentLimit = "PT30S")).run()
    assert(outcome.isInstanceOf[SolveSession.Outcome.Stale], outcome.toString)
    assert(channel.completes.isEmpty)
  }

  test("an operator's drain in the heartbeat reply releases the dataset as drained") {
    val channel = ScriptedChannel()
    channel.heartbeatReply.set(HeartbeatResponse(terminate = false, force = false, 20_000, drain = true))
    val outcome = session(channel, ScriptedChannel.claim("ds_opdrain", spentLimit = "PT60S")).run()
    assertEquals(outcome, SolveSession.Outcome.Released(ReleaseReason.Drained))
  }

  test("shutting down releases the dataset with its best reported") {
    val channel  = ScriptedChannel()
    @volatile var drain = false
    val thread = Thread.ofVirtual().start(() =>
      Thread.sleep(2000)
      drain = true
    )
    val outcome = session(
      channel,
      ScriptedChannel.claim("ds_drain", spentLimit = "PT60S"),
      () => Option.when(drain)(ReleaseReason.Shutdown)
    ).run()
    thread.join()
    assertEquals(outcome, SolveSession.Outcome.Released(ReleaseReason.Shutdown))
    assert(channel.reports.asScala.nonEmpty)
    assertEquals(channel.releases.asScala.map(_._2.reason).toList, List(ReleaseReason.Shutdown))
  }

  test("an unreadable dataset is reported invalid") {
    val channel = ScriptedChannel()
    val claim   = ScriptedChannel.claim("ds_bad")
    channel.enqueue(claim, """{"employees":"not-a-list","shifts":[]}""".getBytes)
    val outcome = SolveSession(claim, runtime, channel, config, () => None).run()
    assertEquals(outcome, SolveSession.Outcome.Invalid)
    assert(channel.phases.asScala.last._2.validation.exists(_.issues.asString.contains("UnreadableDataset")))
  }
