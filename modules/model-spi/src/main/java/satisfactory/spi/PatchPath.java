package satisfactory.spi;

/**
 * An array in the model's input that a patch may address by a field value: {@code /employees} keyed by
 * {@code name} makes {@code /employees/[name=Ann]/skills} a valid path.
 */
public record PatchPath(String path, String keyField) {
}
