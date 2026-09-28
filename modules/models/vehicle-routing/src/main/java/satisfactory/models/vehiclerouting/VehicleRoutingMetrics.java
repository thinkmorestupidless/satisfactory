package satisfactory.models.vehiclerouting;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import satisfactory.models.vehiclerouting.domain.Vehicle;
import satisfactory.models.vehiclerouting.domain.VehicleRoutePlan;
import satisfactory.models.vehiclerouting.domain.Visit;
import satisfactory.spi.Json;
import satisfactory.spi.MetricInfo;

final class VehicleRoutingMetrics {

    static final List<MetricInfo> INPUT = List.of(
            new MetricInfo("vehicles", "Vehicles", "Vehicles available.", "integer", 1, "6"),
            new MetricInfo("visits", "Visits", "Visits to route.", "integer", 2, "55"),
            new MetricInfo("totalDemand", "Total demand", "The sum of every visit's demand.", "integer", 3, "80"),
            new MetricInfo("totalCapacity", "Total capacity", "The sum of every vehicle's capacity.", "integer", 4, "140"));

    static final List<MetricInfo> KPIS = List.of(
            new MetricInfo("assignedVisits", "Assigned visits", "Visits on a route.", "integer", 1, "55"),
            new MetricInfo("unassignedVisits", "Unassigned visits", "Visits on no route.", "integer", 2, "0"),
            new MetricInfo("totalDrivingTimeSeconds", "Total driving time", "Seconds driven across every route.", "integer", 3, "40213"),
            new MetricInfo("lateVisits", "Late visits", "Visits whose service ends after their time window.", "integer", 4, "0"),
            new MetricInfo("disruptionPercentage", "Disruption",
                    "Of the visits that had a vehicle in the plan this one was derived from, the percentage now on another.",
                    "number", 5, "4.5"));

    private VehicleRoutingMetrics() {
    }

    static JsonNode input(VehicleRoutePlan plan) {
        ObjectNode node = Json.object();
        node.put("vehicles", plan.getVehicles().size());
        node.put("visits", plan.getVisits().size());
        node.put("totalDemand", plan.getVisits().stream().mapToInt(Visit::getDemand).sum());
        node.put("totalCapacity", plan.getVehicles().stream().mapToInt(Vehicle::getCapacity).sum());
        return node;
    }

    static JsonNode kpis(VehicleRoutePlan plan) {
        ObjectNode node = Json.object();
        long assigned = plan.getVisits().stream().filter(v -> v.getVehicle() != null).count();
        node.put("assignedVisits", assigned);
        node.put("unassignedVisits", plan.getVisits().size() - assigned);
        node.put("totalDrivingTimeSeconds", plan.getTotalDrivingTimeSeconds());
        node.put("lateVisits", plan.getVisits().stream().filter(Visit::isServiceFinishedAfterMaxEndTime).count());
        long before = 0;
        long moved = 0;
        for (Visit v : plan.getVisits()) {
            if (v.getPreviousVehicle() != null) {
                before++;
                if (v.getVehicle() == null || !v.getPreviousVehicle().equals(v.getVehicle().getId())) {
                    moved++;
                }
            }
        }
        node.put("disruptionPercentage", before == 0 ? 0.0 : Math.round(10000.0 * moved / before) / 100.0);
        return node;
    }
}
