package satisfactory.protocol

/**
 * A score as its levels, most significant first (`0hard/-412soft` → `[0, -412]`), so "strictly
 * better" is decided without the model on the classpath. Replay compares these, never scores.
 */
final case class ScoreKey(levels: List[BigDecimal]):
  def isBetterThan(other: ScoreKey): Boolean = ScoreKey.ordering.compare(this, other) > 0

object ScoreKey:
  given ordering: Ordering[ScoreKey] = (a, b) =>
    val pairs = a.levels.zipAll(b.levels, BigDecimal(0), BigDecimal(0))
    pairs.iterator.map((x, y) => x.compare(y)).find(_ != 0).getOrElse(0)
