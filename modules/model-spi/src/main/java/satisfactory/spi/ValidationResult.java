package satisfactory.spi;

import java.util.List;

/** The outcome of model-level validation: {@code OK}, {@code WARNINGS} or {@code ERRORS}, and the issues. */
public record ValidationResult(String status, List<Issue> issues) {

    public static ValidationResult of(List<Issue> issues) {
        boolean errors = issues.stream().anyMatch(i -> i.severity() == IssueType.Severity.ERROR);
        String status = errors ? "ERRORS" : issues.isEmpty() ? "OK" : "WARNINGS";
        return new ValidationResult(status, List.copyOf(issues));
    }

    public boolean hasErrors() {
        return "ERRORS".equals(status);
    }
}
