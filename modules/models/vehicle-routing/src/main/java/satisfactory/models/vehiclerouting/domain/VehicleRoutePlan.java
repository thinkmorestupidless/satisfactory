/*
 * Ported from Timefold Quickstarts (use-cases/vehicle-routing), Apache License 2.0,
 * https://github.com/TimefoldAI/timefold-quickstarts. Quarkus, REST and Jackson annotations removed;
 * previous assignments and constraint weight overrides added for satisfactory.
 */
package satisfactory.models.vehiclerouting.domain;

import java.util.List;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.HardMediumSoftScore;

@PlanningSolution
public class VehicleRoutePlan {

    @PlanningEntityCollectionProperty
    private List<Vehicle> vehicles;

    @PlanningEntityCollectionProperty
    @ValueRangeProvider
    private List<Visit> visits;

    @PlanningScore
    private HardMediumSoftScore score;

    private ConstraintWeightOverrides<HardMediumSoftScore> constraintWeightOverrides;

    public VehicleRoutePlan() {
    }

    public VehicleRoutePlan(List<Vehicle> vehicles, List<Visit> visits) {
        this.vehicles = vehicles;
        this.visits = visits;
    }

    public List<Vehicle> getVehicles() {
        return vehicles;
    }

    public List<Visit> getVisits() {
        return visits;
    }

    public HardMediumSoftScore getScore() {
        return score;
    }

    public void setScore(HardMediumSoftScore score) {
        this.score = score;
    }

    public ConstraintWeightOverrides<HardMediumSoftScore> getConstraintWeightOverrides() {
        return constraintWeightOverrides;
    }

    public void setConstraintWeightOverrides(ConstraintWeightOverrides<HardMediumSoftScore> constraintWeightOverrides) {
        this.constraintWeightOverrides = constraintWeightOverrides;
    }

    public long getTotalDrivingTimeSeconds() {
        return vehicles == null ? 0 : vehicles.stream().mapToLong(Vehicle::getTotalDrivingTimeSeconds).sum();
    }
}
