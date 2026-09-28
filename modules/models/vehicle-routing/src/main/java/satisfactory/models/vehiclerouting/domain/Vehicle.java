/*
 * Ported from Timefold Quickstarts (use-cases/vehicle-routing), Apache License 2.0,
 * https://github.com/TimefoldAI/timefold-quickstarts. Quarkus, REST and Jackson annotations removed;
 * previous assignments and constraint weight overrides added for satisfactory.
 */
package satisfactory.models.vehiclerouting.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import ai.timefold.solver.core.api.domain.common.PlanningId;
import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.variable.PlanningListVariable;

@PlanningEntity
public class Vehicle {

    @PlanningId
    private String id;
    private int capacity;
    private Location homeLocation;
    private LocalDateTime departureTime;

    @PlanningListVariable(allowsUnassignedValues = true)
    private List<Visit> visits;

    public Vehicle() {
    }

    public Vehicle(String id, int capacity, Location homeLocation, LocalDateTime departureTime) {
        this.id = id;
        this.capacity = capacity;
        this.homeLocation = homeLocation;
        this.departureTime = departureTime;
        this.visits = new ArrayList<>();
    }

    public String getId() {
        return id;
    }

    public int getCapacity() {
        return capacity;
    }

    public Location getHomeLocation() {
        return homeLocation;
    }

    public LocalDateTime getDepartureTime() {
        return departureTime;
    }

    public List<Visit> getVisits() {
        return visits;
    }

    public void setVisits(List<Visit> visits) {
        this.visits = visits;
    }

    public int getTotalDemand() {
        int total = 0;
        for (Visit visit : visits) {
            total += visit.getDemand();
        }
        return total;
    }

    public long getTotalDrivingTimeSeconds() {
        if (visits.isEmpty()) {
            return 0;
        }
        long total = 0;
        Location previous = homeLocation;
        for (Visit visit : visits) {
            total += previous.getDrivingTimeTo(visit.getLocation());
            previous = visit.getLocation();
        }
        return total + previous.getDrivingTimeTo(homeLocation);
    }

    @Override
    public String toString() {
        return id;
    }
}
