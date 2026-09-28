package satisfactory.solver

import com.thinkmorestupidless.ankka.core.EntityId
import satisfactory.api.ApiFixture
import satisfactory.api.application.*
import satisfactory.models.employeescheduling.EmployeeScheduling
import satisfactory.models.vehiclerouting.VehicleRouting
import satisfactory.protocol.*
import satisfactory.runner.{Worker, WorkerConfig}
import satisfactory.spi.ModelCatalog

import scala.concurrent.duration.*

/**
 * Story 4 and Story 10's drain: `api` with no runner of its own, and workers in this JVM speaking the
 * runner protocol over HTTP (quickstart tier 4). One is killed mid-solve, one is drained, a third
 * takes over; every dataset completes, no recorded score regresses, and the dead worker's reports are
 * refused.
 */
class PoolResilienceSuite extends ApiFixture:

  override protected def configOverrides: Map[String, Any] = Map(
    "satisfactory.local-runner" -> false,
    "satisfactory.lease-ttl"    -> "8s",
    "satisfactory.claim-wait"   -> "2s"
  )

  override protected def tenantALimits: Option[Limits] = Some(Limits(8, 600, 43200, 2592000))

  private def worker(id: String): Worker =
    Worker(
      WorkerConfig(id, slots = 1, heartbeat = 2.seconds, claimBackoff = 250.millis, registrationInterval = 1.second),
      ModelCatalog.of(EmployeeScheduling.V1, VehicleRouting.V1),
      HttpControlChannel(baseUrl, "local-runner-token"),
      line => println(s"[$id] $line")
    )

  private def state(id: String): Dataset =
    testKit.componentClient.forEventSourcedEntity(EntityId(id)).call(DatasetEntity.get).invoke()

  private def scoreOf(d: Dataset): Option[ScoreKey] = d.best.map(_.scoreKey)

  test("kill a worker mid-solve, drain another: every solve completes, monotonic, and stale reports are refused (SC-006, SC-007, SC-017)") {
    val w1 = worker("w1")
    val w2 = worker("w2")
    w1.start()
    w2.start()
    try
      val ids = (1 to 4).map(_ => submit(spentLimit = "PT25S").get("id").asText()).toList

      // Two solving, one on each worker, each with a solution recorded.
      val held = eventually(60.seconds)(ids.map(state))(_.count(d => d.lease.isDefined && d.best.isDefined) == 2)
      val onW1 = held.find(_.lease.exists(_.workerId == "w1")).get
      val onW2 = held.find(_.lease.exists(_.workerId == "w2")).get
      val w1Epoch = onW1.lease.get.epoch
      val w1Best  = scoreOf(onW1).get

      // w1 dies without a word. Its lease expires; the dataset is re-queued warm.
      val killedAt = System.nanoTime()
      w1.halt()
      val requeued = eventually(60.seconds)(state(onW1.id))(d => d.attempt == 1 && d.lease.exists(_.epoch > w1Epoch))
      val resumedIn = (System.nanoTime() - killedAt).nanos
      assert(resumedIn < 60.seconds, s"resumed after $resumedIn")
      assertEquals(requeued.warmStartRef, Some(onW1.best.get.solutionRef).orElse(requeued.warmStartRef))

      // The dead worker comes back and reports on its old epoch: refused, nothing changes.
      val before = state(onW1.id)
      val stale = send(
        "POST",
        s"/internal/datasets/${onW1.id}/solutions",
        Some(WireCodecs.encode(ReportRequest(w1Epoch, "0hard/999999soft", List(BigDecimal(0), BigDecimal(999999)), true, "ds_x/solution/e1-99", None))(using WireCodecs.reportRequest)),
        key = None,
        headers = Seq("X-Runner-Token" -> "local-runner-token")
      )
      assertEquals(stale.status, 409, stale.toString)
      val after = state(onW1.id)
      assertEquals(after.best, before.best)
      assertEquals(after.seq, before.seq)

      // An operator drains w2: it releases what it holds; w3 picks everything up.
      val _ = testKit.componentClient.forKeyValueEntity(EntityId("w2")).call(WorkerEntity.drain).invoke(true)
      val released = eventually(30.seconds)(state(onW2.id))(d => d.lease.forall(_.workerId != "w2"))
      assert(released.best.isDefined)
      val w3 = worker("w3")
      w3.start()
      try
        val finals = ids.map(id => awaitFinal(id, 3.minutes))
        assert(finals.forall(_.get("solverStatus").asText() == SolvingStatus.Completed), finals.toString)
        assert(scoreOf(state(onW1.id)).forall(s => !w1Best.isBetterThan(s)), "no regression past the kill")
        ids.map(state).foreach { d =>
          val scores = d.ring.flatMap(_.metadata.score).distinct
          assert(scores.nonEmpty)
        }
        val workerState = testKit.componentClient.forKeyValueEntity(EntityId("w2")).call(WorkerEntity.get).invoke()
        assert(workerState.draining)
        assert(workerState.busy.isEmpty, workerState.toString)
      finally w3.stop()
    finally
      w2.stop()
  }

  test("a second model solves on the pool, and a killed worker's vehicle routing resumes warm") {
    val vr    = "/api/models/vehicle-routing/v1"
    val input = get(s"$vr/demo-data/PHILADELPHIA/input").body
    val w6 = worker("w6")
    w6.start()
    val id = post(s"$vr/route-plans", s"""{"modelInput":$input,"config":{"run":{"termination":{"spentLimit":"PT12S"}}}}""").json.get("id").asText()
    val _  = eventually(60.seconds)(state(id))(d => d.lease.exists(_.workerId == "w6") && d.best.isDefined)
    w6.halt()
    val w7 = worker("w7")
    w7.start()
    try
      val resumed = eventually(60.seconds)(state(id))(_.lease.exists(_.workerId == "w7"))
      assert(resumed.warmStartRef.isDefined)
      val done = eventually(90.seconds)(state(id))(_.isFinal)
      assertEquals(done.status, SolvingStatus.Completed)
    finally w7.stop()
  }

  test("a graceful stop releases every held dataset before the worker deregisters, and it resumes elsewhere") {
    val w4 = worker("w4")
    w4.start()
    val id = submit(spentLimit = "PT30S").get("id").asText()
    val _  = eventually(60.seconds)(state(id))(d => d.lease.exists(_.workerId == "w4") && d.best.isDefined)
    val stopping = System.nanoTime()
    w4.stop()
    assert((System.nanoTime() - stopping).nanos < 20.seconds, "inside the grace period")
    val released = state(id)
    assert(released.lease.isEmpty && released.queued, released.toString.take(300))
    assertEquals(released.attempt, 0, "a graceful release is not a lost lease")
    val w5 = worker("w5")
    w5.start()
    try
      val resumed = eventually(30.seconds)(state(id))(_.lease.exists(_.workerId == "w5"))
      assert(resumed.warmStartRef.isDefined)
      val _ = send("DELETE", s"$es/schedules/$id?force=true")
    finally w5.stop()
  }

/** V1: the solver as a real ankka service — no components, one extension — joins, solves, drains. */
class SolverServiceSuite extends ApiFixture:
  import com.thinkmorestupidless.ankka.runtime.{Ankka, AnkkaService}
  import com.typesafe.config.ConfigFactory

  override protected def configOverrides: Map[String, Any] = Map("satisfactory.local-runner" -> false)

  test("a service with no components reaches ready, registers its worker, solves, and releases on terminate") {
    val settings = SolverSettings(baseUrl, "api", "local-runner-token", "solver-svc", 1, 2.seconds, 1.second)
    val runtime  = SolverRuntime(settings, SolverRuntime.catalog, SolverRuntime.channel(settings))
    val service: AnkkaService = Ankka.service.withExtension(runtime).start("satisfactory-solver", ConfigFactory.load())
    try
      service.awaitReady(60.seconds)
      val id = submit(spentLimit = "PT3S").get("id").asText()
      assertEquals(awaitFinal(id, 90.seconds).get("solverStatus").asText(), SolvingStatus.Completed)
      val w = testKit.componentClient.forKeyValueEntity(EntityId("solver-svc")).call(WorkerEntity.get).invoke()
      assertEquals(w.models, List("employee-scheduling/v1", "vehicle-routing/v1"))
    finally service.terminate()
  }
