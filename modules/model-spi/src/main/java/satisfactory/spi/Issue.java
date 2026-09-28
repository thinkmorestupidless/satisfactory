package satisfactory.spi;

import java.util.Map;

/**
 * One validation finding: a catalog code, its severity and the fields that identify what it is about
 * ({@code shift}, {@code employee}, …).
 */
public record Issue(String code, IssueType.Severity severity, Map<String, String> fields) {
}
