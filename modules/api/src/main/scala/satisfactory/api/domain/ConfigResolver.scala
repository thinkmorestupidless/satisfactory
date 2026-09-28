package satisfactory.api.domain

import satisfactory.protocol.*
import satisfactory.spi.{Json, ModelRuntime}

import java.time.Duration
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** A named profile's contribution to a dataset's configuration. */
final case class ProfileLayer(id: String, run: Option[RunConfiguration], weights: Map[String, Long])

/**
 * The configuration a dataset runs with, resolved once at creation (API.md §2.1), highest first:
 * the request's own `config`, the profile named by `configurationId`, the parent's (for `from-*`),
 * the model's defaults — and then the plan's maximums, enforced last.
 */
object ConfigResolver:

  /** Timefold's platform default when nothing is set: diminished returns, 30 s window, 0.0001. */
  val DefaultTermination: TerminationConfig =
    TerminationConfig(slidingWindowDuration = Some("PT30S"), minimumImprovementRatio = Some(0.0001))

  def resolve(
      runtime: ModelRuntime[?, ?],
      parent: Option[ResolvedConfig],
      profile: Option[ProfileLayer],
      request: Option[ModelConfiguration],
      lifetimeCeiling: FiniteDuration
  ): Either[List[String], ResolvedConfig] =
    val requestRun      = request.flatMap(_.run)
    val requestWeights  = request.flatMap(_.model).flatMap(_.overrides).getOrElse(Map.empty)
    val weightProblems  =
      if requestWeights.isEmpty then Nil
      else runtime.validateOverrides(Json.tree(requestWeights.asJava)).asScala.toList.map(m => s"config.model.overrides: $m")
    val requestTerm     = requestRun.flatMap(_.termination)
    val durationProblems = durationErrors(requestTerm)
    val threadProblems  = requestRun.flatMap(_.maxThreadCount).filter(_ < 1).map(_ => "config.run.maxThreadCount must be at least 1").toList
    val problems        = weightProblems ++ durationProblems ++ threadProblems
    if problems.nonEmpty then Left(problems)
    else
      val layers = List(requestTerm, profile.flatMap(_.run).flatMap(_.termination), parent.map(_.termination))
      val merged = TerminationConfig(
        spentLimit = layers.collectFirst { case Some(t) if t.spentLimit.isDefined => t.spentLimit }.flatten,
        unimprovedSpentLimit = layers.collectFirst { case Some(t) if t.unimprovedSpentLimit.isDefined => t.unimprovedSpentLimit }.flatten,
        slidingWindowDuration = layers.collectFirst { case Some(t) if t.slidingWindowDuration.isDefined => t.slidingWindowDuration }.flatten,
        minimumImprovementRatio = layers.collectFirst { case Some(t) if t.minimumImprovementRatio.isDefined => t.minimumImprovementRatio }.flatten,
        stepCountLimit = layers.collectFirst { case Some(t) if t.stepCountLimit.isDefined => t.stepCountLimit }.flatten,
        moveCountLimit = layers.collectFirst { case Some(t) if t.moveCountLimit.isDefined => t.moveCountLimit }.flatten
      )
      val termination = clamp(if merged == TerminationConfig() then DefaultTermination else merged, lifetimeCeiling)
      val defaults = runtime.model().constraints().asScala.map(c => c.weightField() -> c.defaultWeight()).toMap
      val weights =
        defaults ++ parent.fold(Map.empty)(_.weights) ++ profile.fold(Map.empty)(_.weights) ++ requestWeights
      Right(ResolvedConfig(termination, weights.filter((k, _) => defaults.contains(k)), 1, profile.map(_.id)))

  /** No spent limit above the lifetime ceiling: the ceiling wins, and the resolved config says so. */
  private def clamp(t: TerminationConfig, ceiling: FiniteDuration): TerminationConfig =
    val max = Duration.ofMillis(ceiling.toMillis)
    t.copy(spentLimit = t.spentLimit.map(d => if Duration.parse(d).compareTo(max) > 0 then max.toString else d))

  private def durationErrors(t: Option[TerminationConfig]): List[String] =
    t.toList.flatMap { t =>
      List("spentLimit" -> t.spentLimit, "unimprovedSpentLimit" -> t.unimprovedSpentLimit, "slidingWindowDuration" -> t.slidingWindowDuration)
        .collect { case (field, Some(value)) if Try(Duration.parse(value)).filter(!_.isNegative).isFailure =>
          s"config.run.termination.$field: '$value' is not an ISO-8601 duration"
        }
    } ++ t.flatMap(_.minimumImprovementRatio).filter(r => r <= 0 || r > 1).map(_ => "config.run.termination.minimumImprovementRatio must be in (0, 1]").toList ++
      t.flatMap(_.stepCountLimit).filter(_ < 0).map(_ => "config.run.termination.stepCountLimit must be at least 0").toList ++
      t.flatMap(_.moveCountLimit).filter(_ < 0).map(_ => "config.run.termination.moveCountLimit must be at least 0").toList
