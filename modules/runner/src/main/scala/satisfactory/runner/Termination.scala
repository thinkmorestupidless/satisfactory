package satisfactory.runner

import ai.timefold.solver.core.config.solver.termination.{
  DiminishedReturnsTerminationConfig,
  TerminationConfig as TimefoldTermination
}
import satisfactory.protocol.TerminationConfig

import java.time.Duration

/**
 * The request's flat termination block as Timefold's `TerminationConfig`
 * (`SolverConfigOverride.withTerminationConfig`). Limits combine as Timefold combines them: solving
 * stops at the first one reached. With nothing set, "diminished returns" with Timefold's platform
 * defaults (a 30 s window, 0.0001 ratio) — the resolved configuration always says so explicitly.
 */
object Termination:

  def toTimefold(config: TerminationConfig): TimefoldTermination =
    val t = TimefoldTermination()
    config.spentLimit.foreach(d => t.setSpentLimit(Duration.parse(d)))
    config.unimprovedSpentLimit.foreach(d => t.setUnimprovedSpentLimit(Duration.parse(d)))
    config.stepCountLimit.foreach(n => t.setStepCountLimit(Integer.valueOf(n)))
    config.moveCountLimit.foreach(n => t.setMoveCountLimit(java.lang.Long.valueOf(n)))
    if config.slidingWindowDuration.isDefined || config.minimumImprovementRatio.isDefined then
      val d = DiminishedReturnsTerminationConfig()
      config.slidingWindowDuration.foreach(w => d.setSlidingWindowDuration(Duration.parse(w)))
      config.minimumImprovementRatio.foreach(r => d.setMinimumImprovementRatio(java.lang.Double.valueOf(r)))
      t.setDiminishedReturnsConfig(d)
    t

  /** Whether the block sets any limit at all. */
  def isEmpty(config: TerminationConfig): Boolean =
    config == TerminationConfig()
