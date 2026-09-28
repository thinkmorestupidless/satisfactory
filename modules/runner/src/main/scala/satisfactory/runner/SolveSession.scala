package satisfactory.runner

import satisfactory.protocol.*
import satisfactory.spi.{InvalidDataset, Json, ModelRuntime}

import java.util.concurrent.{CompletableFuture, ConcurrentLinkedQueue, TimeUnit}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * One claimed dataset, from lease to completion, on the claiming slot's own thread.
 *
 * The solver runs on its manager's threads and only offers solutions to a [[Throttle]]; this thread
 * reports at most one per interval, heartbeats, and obeys what the heartbeat's reply says. Every call
 * carries the lease epoch; a `Stale` answer means another worker owns the dataset now, and this one
 * stops, discards, and frees the slot.
 *
 * `stopping` says whether the worker wants its work back, and why: `shutdown` (SIGTERM) or
 * `drained` (an operator's drain).
 */
final class SolveSession(
    claim: ClaimResponse,
    runtime: ModelRuntime[?, ?],
    channel: ControlChannel,
    config: WorkerConfig,
    stopping: () => Option[String],
    log: String => Unit = _ => ()
):
  import SolveSession.*

  private val id        = claim.datasetId
  private val epoch     = claim.epoch
  private val spec      = claim.spec
  private val problemId = s"$id#$epoch"
  private val weights: java.util.Map[String, java.lang.Long] =
    spec.config.weights.map((k, v) => k -> java.lang.Long.valueOf(v)).asJava

  private val throttle       = Throttle[Pending](config.reportInterval)
  private val logLines       = ConcurrentLinkedQueue[String]()
  private var reported       = 0L
  private var scoreSpeed     = 0L
  private var moves          = 0L

  def run(): Outcome =
    try
      note(s"claimed with epoch $epoch, mode ${claim.mode}")
      claim.mode match
        case ClaimMode.Full  => full()
        case ClaimMode.Solve => solve(decode(spec.inputRef, asSolution = spec.select.contains(Select.Solved)))
        case ClaimMode.Warm =>
          solve(decode(claim.warmStartRef.getOrElse(spec.inputRef), asSolution = true))
        case other => throw IllegalStateException(s"unknown claim mode '$other'")
    catch
      case Stop(outcome) => outcome
      case invalid: InvalidDataset =>
        val result = ValidationResult(
          ValidationStatus.Errors,
          RawJson(Json.string(java.util.List.of(issueJson("UnreadableDataset", invalid.getMessage))))
        )
        attempt(channel.phase(id, PhaseRequest(epoch, Phase.Invalid, validation = Some(result))))
          .fold(identity, _ => Outcome.Invalid)
      case NonFatal(error) =>
        val message = LogHygiene.clean(Option(error.getMessage).getOrElse(error.getClass.getSimpleName))
        attempt(channel.fail(id, FailRequest(epoch, message))).fold(identity, _ => Outcome.Failed(message))

  // ── Before solving: validate and score the input ─────────────────────────────────────────

  private def full(): Outcome =
    val problem = decode(spec.inputRef, asSolution = spec.select.contains(Select.Solved))
    val result  = runtime.validate(problem)
    val wire    = ValidationResult(result.status(), RawJson(Json.bytes(result.issues().asScala.map(issueJson).asJava)))
    if result.hasErrors then
      call(channel.phase(id, PhaseRequest(epoch, Phase.Invalid, validation = Some(wire))))
      note("the dataset is invalid")
      Outcome.Invalid
    else
      call(channel.phase(id, PhaseRequest(epoch, Phase.Validated, validation = Some(wire))))
      val scored   = runtime.score(problem)
      val analysis = runtime.analyze(problem, weights)
      val ref      = BlobRefs.analysis(id, "input")
      call(channel.putBlob(ref, "application/json", Json.bytes(analysisJson(analysis))))
      call(
        channel.phase(
          id,
          PhaseRequest(
            epoch,
            Phase.Computed,
            inputScore = Some(scored.score()),
            inputMetrics = Some(RawJson(Json.bytes(runtime.inputMetrics(problem)))),
            analysisRef = Some(ref)
          )
        )
      )
      note(s"input scored ${scored.score()}")
      if spec.operation == Operation.None then Outcome.Computed
      else solve(problem)

  // ── Solving ──────────────────────────────────────────────────────────────────────────────

  private def solve(problem: Object): Outcome =
    call(channel.phase(id, PhaseRequest(epoch, Phase.Started)))
    val finished = CompletableFuture[Either[Throwable, Object]]()
    val job = runtime.solve(
      problemId,
      problem,
      Termination.toTimefold(spec.config.termination),
      best => pending(best).foreach(throttle.offer),
      last =>
        pending(last).foreach(throttle.offer)
        finished.complete(Right(last)): Unit
      ,
      (_, error) => finished.complete(Left(error)): Unit
    )
    var solverDone = false
    try
      call(channel.phase(id, PhaseRequest(epoch, Phase.Active)))
      note("solving")
      var nextBeat          = System.nanoTime() + config.heartbeat.toNanos
      var terminateSent     = false
      var released: Option[Outcome] = None
      while !finished.isDone && released.isEmpty do
        try finished.get(math.min(config.reportInterval.toMillis, 250L), TimeUnit.MILLISECONDS): Unit
        catch case _: java.util.concurrent.TimeoutException => ()
        throttle.takeIfDue().foreach(send)
        if !finished.isDone && System.nanoTime() >= nextBeat then
          scoreSpeed = job.getScoreCalculationSpeed
          moves = job.getMoveEvaluationCount
          val reply = call(channel.heartbeat(id, HeartbeatRequest(epoch, config.workerId, scoreSpeed, moves, drainLogs())))
          nextBeat = System.nanoTime() + config.heartbeat.toNanos
          if (reply.terminate || reply.force) && !terminateSent then
            terminateSent = true
            note("terminating early on request")
            runtime.terminateEarly(problemId)
          if reply.drain then released = Some(release(ReleaseReason.Drained, finished))
        if released.isEmpty then stopping().foreach(reason => released = Some(release(reason, finished)))
      released match
        case Some(outcome) =>
          solverDone = true
          outcome
        case None =>
          solverDone = true
          finished.get() match
            case Left(error) =>
              throttle.takeFinal().foreach(send)
              val message = LogHygiene.clean(Option(error.getMessage).getOrElse(error.getClass.getSimpleName))
              call(channel.fail(id, FailRequest(epoch, message)))
              Outcome.Failed(message)
            case Right(last) =>
              throttle.takeFinal().foreach(send)
              val analysis = runtime.analyze(last, weights)
              val ref      = BlobRefs.analysis(id, "final")
              call(channel.putBlob(ref, "application/json", Json.bytes(analysisJson(analysis))))
              val reason = if terminateSent then CompleteReason.Terminated else CompleteReason.Termination
              call(channel.heartbeat(id, HeartbeatRequest(epoch, config.workerId, scoreSpeed, moves, drainLogs() :+ s"completed: $reason")))
              call(channel.complete(id, CompleteRequest(epoch, reason, Some(ref))))
              Outcome.Completed(reason, reported)
    finally if !solverDone then runtime.terminateEarly(problemId)

  /** Stops the solver, reports its best, and hands the dataset back to the queue. */
  private def release(reason: String, finished: CompletableFuture[Either[Throwable, Object]]): Outcome =
    note(s"releasing: $reason")
    runtime.terminateEarly(problemId)
    try finished.get(10, TimeUnit.SECONDS): Unit
    catch case NonFatal(_) => ()
    throttle.takeFinal().foreach(send)
    call(channel.release(id, ReleaseRequest(epoch, reason)))
    Outcome.Released(reason)

  // ── Reporting ────────────────────────────────────────────────────────────────────────────

  /** Encoded on the solver's thread: the solution it hands over is a planning clone. */
  private def pending(solution: Object): Option[Pending] =
    Option(runtime.currentScore(solution)).map(view =>
      Pending(
        Json.bytes(runtime.encode(solution)),
        view.score(),
        view.key().asScala.map(BigDecimal(_)).toList,
        view.feasible(),
        Json.bytes(runtime.kpis(solution))
      )
    )

  private def send(p: Pending): Unit =
    reported += 1
    val ref = BlobRefs.solution(id, epoch, reported)
    call(channel.putBlob(ref, "application/json", p.body))
    val reply = call(channel.report(id, ReportRequest(epoch, p.score, p.key, p.feasible, ref, Some(RawJson(p.kpis)))))
    if reply.recorded then log(s"$id: recorded ${p.score} as seq ${reply.seq}")

  // ── Plumbing ─────────────────────────────────────────────────────────────────────────────

  private def decode(ref: String, asSolution: Boolean): Object =
    val input = Json.parse(call(channel.getBlob(ref)))
    if asSolution then runtime.solution(input, weights) else runtime.problem(input, weights)

  private def note(line: String): Unit =
    val safe = LogHygiene.clean(line)
    logLines.add(s"${java.time.Instant.now()} $safe"): Unit
    log(s"$id: $safe")

  private def drainLogs(): List[String] =
    Iterator.continually(logLines.poll()).takeWhile(_ != null).toList

  /** A channel call that must succeed: retried while the API is unreachable, stopped when stale. */
  private def call[A](result: => Either[ChannelError, A]): A =
    var attempts = 0
    var outcome  = result
    while outcome.left.exists(_.isInstanceOf[ChannelError.Unavailable]) && attempts < 20 do
      attempts += 1
      Thread.sleep(500)
      outcome = result
    outcome match
      case Right(value)                         => value
      case Left(ChannelError.Stale(message))    => throw Stop(Outcome.Stale(message))
      case Left(ChannelError.Unavailable(m))    => throw Stop(Outcome.Lost(m))
      case Left(ChannelError.Rejected(s, m))    => throw Stop(Outcome.Lost(s"rejected ($s): $m"))

  private def attempt[A](result: => Either[ChannelError, A]): Either[Outcome, A] =
    try Right(call(result))
    catch case Stop(outcome) => Left(outcome)

object SolveSession:

  /** How a session ended, for the worker's log and its tests. */
  enum Outcome:
    case Invalid
    case Computed
    case Completed(reason: String, reports: Long)
    case Failed(message: String)
    case Released(reason: String)
    case Stale(message: String)
    case Lost(message: String)

  private final case class Pending(
      body: Array[Byte],
      score: String,
      key: List[BigDecimal],
      feasible: Boolean,
      kpis: Array[Byte]
  )

  private final case class Stop(outcome: Outcome) extends RuntimeException(outcome.toString, null, false, false)

  private def issueJson(issue: satisfactory.spi.Issue): java.util.Map[String, String] =
    val m = java.util.LinkedHashMap[String, String]()
    m.put("code", issue.code())
    m.put("severity", issue.severity().name())
    m.putAll(issue.fields())
    m

  private def issueJson(code: String, message: String): java.util.Map[String, String] =
    val m = java.util.LinkedHashMap[String, String]()
    m.put("code", code)
    m.put("severity", "ERROR")
    m.put("message", message)
    m

  /** The Community score analysis shape (research R2), as the API serves it. */
  def analysisJson(view: ModelRuntime.AnalysisView): java.util.Map[String, Object] =
    val m = java.util.LinkedHashMap[String, Object]()
    m.put("score", view.score())
    m.put(
      "constraints",
      view.constraints().asScala.map { c =>
        val cm = java.util.LinkedHashMap[String, String]()
        cm.put("name", c.name())
        cm.put("weight", c.weight())
        cm.put("score", c.score())
        cm
      }.asJava
    )
    m
