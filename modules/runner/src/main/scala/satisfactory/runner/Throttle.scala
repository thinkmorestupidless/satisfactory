package satisfactory.runner

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.FiniteDuration

/**
 * Latest wins, at most one emission per interval, and the last one is never lost.
 *
 * Timefold finds hundreds of new bests a second at the start of a solve; the solver's thread offers
 * each one here and the session's own loop takes at most one per interval. Timefold's
 * `ThrottlingBestSolutionEventConsumer` is Enterprise-only; this is the Community equivalent.
 */
final class Throttle[A](interval: FiniteDuration, nanoTime: () => Long = () => System.nanoTime()):

  private val pending              = AtomicReference[Option[A]](None)
  @volatile private var lastEmitted = Long.MinValue

  /** From any thread: replaces whatever was waiting. */
  def offer(value: A): Unit = pending.set(Some(value))

  def hasPending: Boolean = pending.get().isDefined

  /** The waiting value, if there is one and the interval since the last emission has passed. */
  def takeIfDue(): Option[A] =
    val now = nanoTime()
    if lastEmitted != Long.MinValue && now - lastEmitted < interval.toNanos then None
    else
      val taken = pending.getAndSet(None)
      if taken.isDefined then lastEmitted = now
      taken

  /** The waiting value regardless of the interval: the final best is always sent. */
  def takeFinal(): Option[A] =
    val taken = pending.getAndSet(None)
    if taken.isDefined then lastEmitted = nanoTime()
    taken
