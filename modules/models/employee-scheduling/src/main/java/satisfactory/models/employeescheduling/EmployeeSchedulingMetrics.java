package satisfactory.models.employeescheduling;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import satisfactory.models.employeescheduling.domain.Employee;
import satisfactory.models.employeescheduling.domain.EmployeeSchedule;
import satisfactory.models.employeescheduling.domain.Shift;
import satisfactory.spi.Json;
import satisfactory.spi.MetricInfo;

/** Input metrics and KPIs: typed, documented fields (API.md §2.3). */
final class EmployeeSchedulingMetrics {

    static final List<MetricInfo> INPUT = List.of(
            new MetricInfo("employees", "Employees", "Employees available to staff shifts.", "integer", 1, "15"),
            new MetricInfo("shifts", "Shifts", "Shifts to staff.", "integer", 2, "120"),
            new MetricInfo("pinnedShifts", "Pinned shifts", "Shifts whose employee the solver must keep.", "integer", 3, "4"),
            new MetricInfo("locations", "Locations", "Distinct shift locations.", "integer", 4, "3"));

    static final List<MetricInfo> KPIS = List.of(
            new MetricInfo("assignedShifts", "Assigned shifts", "Shifts with an employee.", "integer", 1, "118"),
            new MetricInfo("unassignedShifts", "Unassigned shifts", "Shifts without an employee.", "integer", 2, "2"),
            new MetricInfo("workingTimeFairnessPercentage", "Working time fairness",
                    "Jain's fairness index over assigned minutes per employee, as a percentage; 100 is perfectly even.",
                    "number", 3, "95.98"),
            new MetricInfo("disruptionPercentage", "Disruption",
                    "Of the shifts that had an employee in the plan this one was derived from, the percentage now assigned to someone else.",
                    "number", 4, "11.01"));

    private EmployeeSchedulingMetrics() {
    }

    static JsonNode input(EmployeeSchedule schedule) {
        ObjectNode node = Json.object();
        node.put("employees", schedule.getEmployees().size());
        node.put("shifts", schedule.getShifts().size());
        node.put("pinnedShifts", schedule.getShifts().stream().filter(Shift::isPinned).count());
        Set<String> locations = new HashSet<>();
        schedule.getShifts().forEach(s -> locations.add(s.getLocation()));
        node.put("locations", locations.size());
        return node;
    }

    static JsonNode kpis(EmployeeSchedule schedule) {
        ObjectNode node = Json.object();
        long assigned = schedule.getShifts().stream().filter(s -> s.getEmployee() != null).count();
        node.put("assignedShifts", assigned);
        node.put("unassignedShifts", schedule.getShifts().size() - assigned);
        node.put("workingTimeFairnessPercentage", round(fairness(schedule)));
        node.put("disruptionPercentage", round(disruption(schedule)));
        return node;
    }

    private static double fairness(EmployeeSchedule schedule) {
        Map<String, Long> minutes = new HashMap<>();
        for (Employee e : schedule.getEmployees()) {
            minutes.put(e.getName(), 0L);
        }
        for (Shift s : schedule.getShifts()) {
            if (s.getEmployee() != null) {
                minutes.merge(s.getEmployee().getName(), s.getDurationInMinutes(), Long::sum);
            }
        }
        double sum = 0;
        double sumOfSquares = 0;
        for (long m : minutes.values()) {
            sum += m;
            sumOfSquares += (double) m * m;
        }
        if (minutes.isEmpty() || sumOfSquares == 0) {
            return 100.0;
        }
        return 100.0 * (sum * sum) / (minutes.size() * sumOfSquares);
    }

    private static double disruption(EmployeeSchedule schedule) {
        long previouslyAssigned = 0;
        long changed = 0;
        for (Shift s : schedule.getShifts()) {
            if (s.getPreviousEmployee() != null) {
                previouslyAssigned++;
                if (s.getEmployee() == null || !s.getPreviousEmployee().equals(s.getEmployee().getName())) {
                    changed++;
                }
            }
        }
        return previouslyAssigned == 0 ? 0.0 : 100.0 * changed / previouslyAssigned;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
