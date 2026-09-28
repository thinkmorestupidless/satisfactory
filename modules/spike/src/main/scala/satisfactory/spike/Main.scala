package satisfactory.spike

import satisfactory.models.employeescheduling.EmployeeScheduling
import satisfactory.protocol.*
import satisfactory.runner.{ChannelError, ControlChannel, Worker, WorkerConfig}
import satisfactory.spi.{Json, ModelCatalog}

import java.util.concurrent.{ConcurrentHashMap, CountDownLatch, TimeUnit}
import scala.concurrent.duration.*

/**
 * Phase 0: one model, a real worker, the throttle, and a channel that prints instead of calling an
 * API. Proves the Scala↔Timefold interop, the SPI's shape, and serialising a planning clone.
 *
 *   sbt "spike/run 10"      # seconds to solve the SMALL demo dataset
 */
@main def spike(args: String*): Unit =
  val seconds = args.headOption.map(_.toInt).getOrElse(10)
  val input   = Json.bytes(EmployeeScheduling.V1.demoData().get(0).input().get())
  val done    = CountDownLatch(1)
  val theClaim = ClaimResponse(
    "ds_spike",
    1,
    DatasetSpec(
      "t_spike", "employee-scheduling", "v1", "schedules", Operation.Solve, 5, BlobRefs.input("ds_spike"),
      None, None, None, None,
      ResolvedConfig(TerminationConfig(spentLimit = Some(s"PT${seconds}S")), Map.empty, 1, None), None
    ),
    ClaimMode.Full,
    None,
    20_000,
    drain = false
  )

  val channel = new ControlChannel:
    private val blobs          = ConcurrentHashMap[String, Array[Byte]]()
    @volatile private var sent = false
    @volatile private var best: Option[ScoreKey] = None
    private val started        = System.nanoTime()
    blobs.put(theClaim.spec.inputRef, input): Unit

    private def at = f"${(System.nanoTime() - started) / 1e9}%6.2fs"

    def register(workerId: String, r: WorkerRegistration) = Right(WorkerAck(false))
    def deregister(workerId: String)                      = Right(())
    def claim(r: ClaimRequest) = synchronized {
      if sent then Right(None)
      else
        sent = true
        Right(Some(theClaim))
    }
    def phase(id: String, r: PhaseRequest) =
      println(s"$at phase ${r.phase}${r.inputScore.fold("")(s => s" (input scored $s)")}")
      Right(())
    def report(id: String, r: ReportRequest) =
      val key    = ScoreKey(r.scoreKey)
      val better = best.forall(key.isBetterThan)
      if better then best = Some(key)
      println(s"$at best ${r.score}${if r.feasible then " feasible" else ""}${if better then "" else " (not better)"}")
      Right(ReportResponse(better, 0, Nil))
    def heartbeat(id: String, r: HeartbeatRequest) =
      println(s"$at heartbeat: ${r.scoreCalculationSpeed} score calculations/s")
      Right(HeartbeatResponse(false, false, 20_000, false))
    def complete(id: String, r: CompleteRequest) =
      println(s"$at complete: ${r.reason}")
      done.countDown()
      Right(())
    def fail(id: String, r: FailRequest) =
      println(s"$at failed: ${r.message}")
      done.countDown()
      Right(())
    def release(id: String, r: ReleaseRequest) = Right(())
    def getBlob(ref: String) = Option(blobs.get(ref)).toRight(ChannelError.Rejected(404, ref))
    def putBlob(ref: String, contentType: String, bytes: Array[Byte]) =
      blobs.put(ref, bytes): Unit
      Right(())

  val worker = Worker(WorkerConfig("spike", slots = 1, heartbeat = 2.seconds), ModelCatalog.of(EmployeeScheduling.V1), channel)
  worker.start()
  done.await(seconds + 60L, TimeUnit.SECONDS): Unit
  worker.stop()
