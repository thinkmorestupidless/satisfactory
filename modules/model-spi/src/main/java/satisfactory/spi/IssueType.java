package satisfactory.spi;

/** An entry in a model's validation issue catalog. */
public record IssueType(String code, Severity severity, String message) {

    public enum Severity {
        ERROR, WARNING
    }
}
