package satisfactory.spi;

/**
 * A KPI or input metric, declared as a typed, documented field (API.md §2.3) rather than free-form JSON.
 *
 * @param type {@code integer}, {@code number} or {@code string}
 */
public record MetricInfo(String id, String title, String description, String type, int priority, String example) {
}
