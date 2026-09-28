package satisfactory.spi;

/**
 * One constraint as the public API sees it.
 *
 * @param name          the name the constraint provider gives it ({@code asConstraint(name)}); the id
 *                      {@code ConstraintWeightOverrides} is keyed by
 * @param key           the camelCase key; the request's weight field is {@code <key>Weight}
 * @param level         the score level its weight applies to
 * @param defaultWeight the multiplier used when a request does not say; 0 disables the constraint
 */
public record ConstraintInfo(String name, String key, String description, Level level, long defaultWeight) {

    public enum Level {
        HARD, MEDIUM, SOFT
    }

    /** The request field this constraint's weight travels in. */
    public String weightField() {
        return key + "Weight";
    }
}
