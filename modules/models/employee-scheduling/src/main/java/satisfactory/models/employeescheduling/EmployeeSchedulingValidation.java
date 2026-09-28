package satisfactory.models.employeescheduling;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import satisfactory.models.employeescheduling.domain.Employee;
import satisfactory.models.employeescheduling.domain.EmployeeSchedule;
import satisfactory.models.employeescheduling.domain.Shift;
import satisfactory.spi.Issue;
import satisfactory.spi.IssueType;
import satisfactory.spi.IssueType.Severity;
import satisfactory.spi.ValidationResult;

/** Model-level validation: what a schema cannot say. Issues are typed per code (API.md §2.3). */
final class EmployeeSchedulingValidation {

    static final List<IssueType> CATALOG = List.of(
            new IssueType("EmptySchedule", Severity.ERROR, "The schedule has no shifts to assign."),
            new IssueType("NoEmployees", Severity.ERROR, "The schedule has shifts but no employees to assign them to."),
            new IssueType("DuplicateEmployeeName", Severity.ERROR, "Two employees share a name; names identify employees."),
            new IssueType("DuplicateShiftId", Severity.ERROR, "Two shifts share an id."),
            new IssueType("ShiftUnknownEmployee", Severity.ERROR, "A shift is assigned to an employee the schedule does not contain."),
            new IssueType("ShiftEndsBeforeStart", Severity.ERROR, "A shift ends before, or when, it starts."),
            new IssueType("RequiredSkillUnknown", Severity.WARNING, "No employee has a skill a shift requires, so that shift cannot be staffed without breaking a hard constraint."));

    private EmployeeSchedulingValidation() {
    }

    static ValidationResult validate(EmployeeSchedule schedule) {
        List<Issue> issues = new ArrayList<>();
        if (schedule.getShifts().isEmpty()) {
            issues.add(new Issue("EmptySchedule", Severity.ERROR, Map.of()));
        } else if (schedule.getEmployees().isEmpty()) {
            issues.add(new Issue("NoEmployees", Severity.ERROR, Map.of()));
        }
        Set<String> names = new HashSet<>();
        Set<String> skills = new HashSet<>();
        for (Employee e : schedule.getEmployees()) {
            if (!names.add(e.getName())) {
                issues.add(new Issue("DuplicateEmployeeName", Severity.ERROR, Map.of("employee", e.getName())));
            }
            skills.addAll(e.getSkills());
        }
        Set<String> ids = new HashSet<>();
        Set<String> reportedSkills = new HashSet<>();
        for (Shift s : schedule.getShifts()) {
            if (!ids.add(s.getId())) {
                issues.add(new Issue("DuplicateShiftId", Severity.ERROR, Map.of("shift", s.getId())));
            }
            if (s.getUnknownEmployee() != null) {
                issues.add(new Issue("ShiftUnknownEmployee", Severity.ERROR,
                        Map.of("shift", s.getId(), "employee", s.getUnknownEmployee())));
            }
            if (!s.getEnd().isAfter(s.getStart())) {
                issues.add(new Issue("ShiftEndsBeforeStart", Severity.ERROR, Map.of("shift", s.getId())));
            }
            if (!skills.contains(s.getRequiredSkill()) && reportedSkills.add(s.getRequiredSkill())) {
                issues.add(new Issue("RequiredSkillUnknown", Severity.WARNING,
                        Map.of("shift", s.getId(), "skill", s.getRequiredSkill())));
            }
        }
        return ValidationResult.of(issues);
    }
}
