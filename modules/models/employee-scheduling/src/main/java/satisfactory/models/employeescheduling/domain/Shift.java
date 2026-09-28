/*
 * Ported from Timefold Quickstarts (use-cases/employee-scheduling), Apache License 2.0,
 * https://github.com/TimefoldAI/timefold-quickstarts. Quarkus and REST removed; pinning, previous
 * assignments and constraint weight overrides added for satisfactory.
 */
package satisfactory.models.employeescheduling.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import ai.timefold.solver.core.api.domain.common.PlanningId;
import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.entity.PlanningPin;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;

@PlanningEntity
public class Shift {

    @PlanningId
    private String id;
    private LocalDateTime start;
    private LocalDateTime end;
    private String location;
    private String requiredSkill;

    @PlanningVariable
    private Employee employee;

    /** Set by the caller: the solver must not change this shift's employee. */
    @PlanningPin
    private boolean pinned;

    /** The employee this shift had in the plan it was derived from; what disruption is measured against. */
    private String previousEmployee;

    /** An employee named by the input that is not in the schedule; reported by validation, never solved. */
    private String unknownEmployee;

    public Shift() {
    }

    public Shift(String id, LocalDateTime start, LocalDateTime end, String location, String requiredSkill,
            Employee employee) {
        this.id = id;
        this.start = start;
        this.end = end;
        this.location = location;
        this.requiredSkill = requiredSkill;
        this.employee = employee;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public LocalDateTime getStart() {
        return start;
    }

    public void setStart(LocalDateTime start) {
        this.start = start;
    }

    public LocalDateTime getEnd() {
        return end;
    }

    public void setEnd(LocalDateTime end) {
        this.end = end;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getRequiredSkill() {
        return requiredSkill;
    }

    public void setRequiredSkill(String requiredSkill) {
        this.requiredSkill = requiredSkill;
    }

    public Employee getEmployee() {
        return employee;
    }

    public void setEmployee(Employee employee) {
        this.employee = employee;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public String getPreviousEmployee() {
        return previousEmployee;
    }

    public void setPreviousEmployee(String previousEmployee) {
        this.previousEmployee = previousEmployee;
    }

    public String getUnknownEmployee() {
        return unknownEmployee;
    }

    public void setUnknownEmployee(String unknownEmployee) {
        this.unknownEmployee = unknownEmployee;
    }

    public boolean isOverlappingWithDate(LocalDate date) {
        return getStart().toLocalDate().equals(date) || getEnd().toLocalDate().equals(date);
    }

    public int getOverlappingDurationInMinutes(LocalDate date) {
        LocalDateTime startDateTime = LocalDateTime.of(date, LocalTime.MIN);
        LocalDateTime endDateTime = LocalDateTime.of(date, LocalTime.MAX);
        LocalDateTime maxStart = startDateTime.isAfter(start) ? startDateTime : start;
        LocalDateTime minEnd = endDateTime.isBefore(end) ? endDateTime : end;
        long minutes = maxStart.until(minEnd, ChronoUnit.MINUTES);
        return minutes > 0 ? (int) minutes : 0;
    }

    public long getDurationInMinutes() {
        return Math.max(0, start.until(end, ChronoUnit.MINUTES));
    }

    @Override
    public String toString() {
        return location + " " + start + "-" + end;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Shift shift && Objects.equals(id, shift.id));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
