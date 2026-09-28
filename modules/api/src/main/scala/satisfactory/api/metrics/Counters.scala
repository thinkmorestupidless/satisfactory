package satisfactory.api.metrics

import java.util.concurrent.atomic.AtomicLong

/**
 * Counters this process has seen since it started (reset on restart, and per instance — sum them
 * across instances). Gauges come from the views instead, so they agree across instances.
 */
object Counters:
  val leaseLosses     = AtomicLong()
  val requeues        = AtomicLong()
  val solvesCompleted = AtomicLong()
  val solvesFailed    = AtomicLong()
  val webhookFailures = AtomicLong()
