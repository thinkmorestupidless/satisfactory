package satisfactory.runner

import scala.concurrent.duration.*

/**
 * @param slots             concurrent solves; cores − 1 on a worker instance, so cluster heartbeats keep a core
 * @param minReportInterval at most one solution report per interval per solve, latest wins (floor 250 ms)
 * @param heartbeat         how often each held dataset heartbeats; also the terminate latency
 * @param claimBackoff      how long an idle slot waits before asking again after an empty claim
 */
final case class WorkerConfig(
    workerId: String,
    slots: Int = 1,
    minReportInterval: FiniteDuration = 1.second,
    heartbeat: FiniteDuration = 5.seconds,
    claimBackoff: FiniteDuration = 1.second,
    registrationInterval: FiniteDuration = 5.seconds,
    drainTimeout: FiniteDuration = 20.seconds
):
  require(slots >= 1, "a worker needs at least one slot")
  val reportInterval: FiniteDuration = minReportInterval.max(250.millis)
