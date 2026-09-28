package satisfactory.models.employeescheduling;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;

import satisfactory.models.employeescheduling.domain.Employee;
import satisfactory.models.employeescheduling.domain.EmployeeSchedule;
import satisfactory.models.employeescheduling.domain.Shift;
import satisfactory.spi.InvalidDataset;
import satisfactory.spi.Json;

/** The wire shape (input.schema.json, output.schema.json) to and from the planning domain. */
final class EmployeeSchedulingCodecs {

    record EmployeeDto(String name, List<String> skills, List<LocalDate> unavailableDates,
            List<LocalDate> undesiredDates, List<LocalDate> desiredDates) {
    }

    record ShiftDto(String id, LocalDateTime start, LocalDateTime end, String location, String requiredSkill,
            String employee, Boolean pinned) {
    }

    record ScheduleDto(List<EmployeeDto> employees, List<ShiftDto> shifts) {
    }

    private EmployeeSchedulingCodecs() {
    }

    /**
     * Decodes a schedule. Assignments in the input are kept as the starting point; when
     * {@code rememberAssignments}, each shift also records its employee as the one disruption is measured
     * against. An employee name the schedule does not contain is kept aside for validation to report.
     */
    static EmployeeSchedule decode(JsonNode node, boolean rememberAssignments) throws InvalidDataset {
        ScheduleDto dto = Json.convert(node, ScheduleDto.class);
        List<Employee> employees = new ArrayList<>();
        for (EmployeeDto e : orEmpty(dto.employees())) {
            employees.add(new Employee(e.name(), new LinkedHashSet<>(orEmpty(e.skills())),
                    new LinkedHashSet<>(orEmpty(e.unavailableDates())),
                    new LinkedHashSet<>(orEmpty(e.undesiredDates())),
                    new LinkedHashSet<>(orEmpty(e.desiredDates()))));
        }
        Map<String, Employee> byName = employees.stream()
                .collect(Collectors.toMap(Employee::getName, Function.identity(), (a, b) -> a));
        List<Shift> shifts = new ArrayList<>();
        for (ShiftDto s : orEmpty(dto.shifts())) {
            Shift shift = new Shift(s.id(), s.start(), s.end(), s.location(), s.requiredSkill(), null);
            if (s.employee() != null) {
                Employee assigned = byName.get(s.employee());
                if (assigned == null) {
                    shift.setUnknownEmployee(s.employee());
                } else {
                    shift.setEmployee(assigned);
                    if (rememberAssignments) {
                        shift.setPreviousEmployee(assigned.getName());
                    }
                }
            }
            shift.setPinned(Boolean.TRUE.equals(s.pinned()) && shift.getEmployee() != null);
            shifts.add(shift);
        }
        return new EmployeeSchedule(employees, shifts);
    }

    static JsonNode encode(EmployeeSchedule schedule) {
        List<EmployeeDto> employees = schedule.getEmployees().stream()
                .map(e -> new EmployeeDto(e.getName(), List.copyOf(e.getSkills()),
                        List.copyOf(e.getUnavailableDates()), List.copyOf(e.getUndesiredDates()),
                        List.copyOf(e.getDesiredDates())))
                .toList();
        List<ShiftDto> shifts = schedule.getShifts().stream()
                .map(s -> new ShiftDto(s.getId(), s.getStart(), s.getEnd(), s.getLocation(), s.getRequiredSkill(),
                        s.getEmployee() == null ? null : s.getEmployee().getName(), s.isPinned()))
                .toList();
        return Json.tree(new ScheduleDto(employees, shifts));
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
