/*
 * Ported from Timefold Quickstarts (use-cases/vehicle-routing), Apache License 2.0,
 * https://github.com/TimefoldAI/timefold-quickstarts. Quarkus, REST and Jackson annotations removed;
 * previous assignments and constraint weight overrides added for satisfactory.
 */
package satisfactory.models.vehiclerouting.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import ai.timefold.solver.core.api.domain.common.PlanningId;
import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.variable.InverseRelationShadowVariable;
import ai.timefold.solver.core.api.domain.variable.PreviousElementShadowVariable;
import ai.timefold.solver.core.api.domain.variable.ShadowSources;
import ai.timefold.solver.core.api.domain.variable.ShadowVariable;

@PlanningEntity
public class Visit {

    @PlanningId
    private String id;
    private String name;
    private Location location;
    private int demand;
    private LocalDateTime minStartTime;
    private LocalDateTime maxEndTime;
    private Duration serviceDuration;

    /** The vehicle this visit had in the plan it was derived from; what disruption is measured against. */
    private String previousVehicle;

    @InverseRelationShadowVariable(sourceVariableName = "visits")
    private Vehicle vehicle;

    @PreviousElementShadowVariable(sourceVariableName = "visits")
    private Visit previousVisit;

    @ShadowVariable(supplierName = "arrivalTimeSupplier")
    private LocalDateTime arrivalTime;

    public Visit() {
    }

    public Visit(String id, String name, Location location, int demand, LocalDateTime minStartTime,
            LocalDateTime maxEndTime, Duration serviceDuration) {
        this.id = id;
        this.name = name;
        this.location = location;
        this.demand = demand;
        this.minStartTime = minStartTime;
        this.maxEndTime = maxEndTime;
        this.serviceDuration = serviceDuration;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Location getLocation() {
        return location;
    }

    public int getDemand() {
        return demand;
    }

    public LocalDateTime getMinStartTime() {
        return minStartTime;
    }

    public LocalDateTime getMaxEndTime() {
        return maxEndTime;
    }

    public Duration getServiceDuration() {
        return serviceDuration;
    }

    public String getPreviousVehicle() {
        return previousVehicle;
    }

    public void setPreviousVehicle(String previousVehicle) {
        this.previousVehicle = previousVehicle;
    }

    public Vehicle getVehicle() {
        return vehicle;
    }

    public void setVehicle(Vehicle vehicle) {
        this.vehicle = vehicle;
    }

    public Visit getPreviousVisit() {
        return previousVisit;
    }

    public void setPreviousVisit(Visit previousVisit) {
        this.previousVisit = previousVisit;
    }

    public LocalDateTime getArrivalTime() {
        return arrivalTime;
    }

    public void setArrivalTime(LocalDateTime arrivalTime) {
        this.arrivalTime = arrivalTime;
    }

    @SuppressWarnings("unused")
    @ShadowSources({ "vehicle", "previousVisit.arrivalTime" })
    public LocalDateTime arrivalTimeSupplier() {
        if (previousVisit == null && vehicle == null) {
            return null;
        }
        LocalDateTime departure = previousVisit == null ? vehicle.getDepartureTime() : previousVisit.getDepartureTime();
        return departure != null ? departure.plusSeconds(getDrivingTimeSecondsFromPreviousStandstill()) : null;
    }

    public LocalDateTime getDepartureTime() {
        return arrivalTime == null ? null : getStartServiceTime().plus(serviceDuration);
    }

    public LocalDateTime getStartServiceTime() {
        if (arrivalTime == null) {
            return null;
        }
        return arrivalTime.isBefore(minStartTime) ? minStartTime : arrivalTime;
    }

    public boolean isServiceFinishedAfterMaxEndTime() {
        return arrivalTime != null && arrivalTime.plus(serviceDuration).isAfter(maxEndTime);
    }

    public long getServiceFinishedDelayInMinutes() {
        if (arrivalTime == null) {
            return 0;
        }
        Duration delay = Duration.between(maxEndTime, arrivalTime.plus(serviceDuration));
        Duration remainder = delay.minus(delay.truncatedTo(ChronoUnit.MINUTES));
        return remainder.isZero() ? delay.toMinutes() : delay.toMinutes() + 1;
    }

    public long getDrivingTimeSecondsFromPreviousStandstill() {
        if (vehicle == null) {
            throw new IllegalStateException("shadow variables are not initialised yet");
        }
        return previousVisit == null
                ? vehicle.getHomeLocation().getDrivingTimeTo(location)
                : previousVisit.getLocation().getDrivingTimeTo(location);
    }

    @Override
    public String toString() {
        return id;
    }
}
