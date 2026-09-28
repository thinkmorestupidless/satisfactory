package satisfactory.spi;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import ai.timefold.solver.core.api.score.Score;

/**
 * What the platform needs to know about a model's score type: how to write and read it, how to compare
 * it without the model ({@link #key}), and how an integer weight becomes a score at a constraint's level.
 */
public interface ScoreCodec<Score_ extends Score<Score_>> {

    Score_ parse(String text);

    /** A weight of {@code weight} at {@code level}: {@code 5, SOFT} → {@code 0hard/5soft}. */
    Score_ weight(ConstraintInfo.Level level, long weight);

    default String format(Score_ score) {
        return score.toString();
    }

    /** The levels, most significant first; what the dataset entity compares. */
    default List<BigDecimal> key(Score_ score) {
        return Arrays.stream(score.toLevelNumbers()).map(n -> new BigDecimal(n.toString())).toList();
    }
}
