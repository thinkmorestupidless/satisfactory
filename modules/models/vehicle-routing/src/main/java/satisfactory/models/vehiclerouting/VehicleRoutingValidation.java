package satisfactory.models.vehiclerouting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import satisfactory.models.vehiclerouting.domain.Vehicle;
import satisfactory.models.vehiclerouting.domain.VehicleRoutePlan;
import satisfactory.models.vehiclerouting.domain.Visit;
import satisfactory.spi.Issue;
import satisfactory.spi.IssueType;
import satisfactory.spi.IssueType.Severity;
import satisfactory.spi.ValidationResult;

final class VehicleRoutingValidation {

    static final List<IssueType> CATALOG = List.of(
            new IssueType("EmptyPlan", Severity.ERROR, "The plan has no visits to route."),
            new IssueType("NoVehicles", Severity.ERROR, "The plan has visits but no vehicles."),
            new IssueType("DuplicateVehicleId", Severity.ERROR, "Two vehicles share an id."),
            new IssueType("DuplicateVisitId", Severity.ERROR, "Two visits share an id."),
            new IssueType("VisitUnknownInRoute", Severity.ERROR, "A vehicle's route names a visit the plan does not contain."),
            new IssueType("VisitRoutedTwice", Severity.ERROR, "A visit appears in more than one route, or twice in one."),
            new IssueType("TimeWindowInverted", Severity.ERROR, "A visit's time window ends before it starts."),
            new IssueType("DemandOverAnyCapacity", Severity.WARNING, "A visit needs more than any vehicle can carry, so it cannot be served without breaking a hard constraint."));

    private VehicleRoutingValidation() {
    }

    static ValidationResult validate(VehicleRoutePlan plan) {
        List<Issue> issues = new ArrayList<>();
        if (plan.getVisits().isEmpty()) {
            issues.add(new Issue("EmptyPlan", Severity.ERROR, Map.of()));
        } else if (plan.getVehicles().isEmpty()) {
            issues.add(new Issue("NoVehicles", Severity.ERROR, Map.of()));
        }
        Set<String> vehicleIds = new HashSet<>();
        int maxCapacity = 0;
        for (Vehicle v : plan.getVehicles()) {
            if (!vehicleIds.add(v.getId())) {
                issues.add(new Issue("DuplicateVehicleId", Severity.ERROR, Map.of("vehicle", v.getId())));
            }
            maxCapacity = Math.max(maxCapacity, v.getCapacity());
        }
        Set<String> visitIds = new HashSet<>();
        for (Visit v : plan.getVisits()) {
            if (!visitIds.add(v.getId())) {
                issues.add(new Issue("DuplicateVisitId", Severity.ERROR, Map.of("visit", v.getId())));
            }
            if (!v.getMaxEndTime().isAfter(v.getMinStartTime())) {
                issues.add(new Issue("TimeWindowInverted", Severity.ERROR, Map.of("visit", v.getId())));
            }
            if (!plan.getVehicles().isEmpty() && v.getDemand() > maxCapacity) {
                issues.add(new Issue("DemandOverAnyCapacity", Severity.WARNING, Map.of("visit", v.getId())));
            }
        }
        VehicleRoutingCodecs.Problems problems = VehicleRoutingCodecs.PROBLEMS.get(plan);
        if (problems != null) {
            for (String[] unknown : problems.unknownVisits) {
                issues.add(new Issue("VisitUnknownInRoute", Severity.ERROR, Map.of("vehicle", unknown[0], "visit", unknown[1])));
            }
            for (String twice : problems.visitsRoutedTwice) {
                issues.add(new Issue("VisitRoutedTwice", Severity.ERROR, Map.of("visit", twice)));
            }
        }
        return ValidationResult.of(issues);
    }
}
