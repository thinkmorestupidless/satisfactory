package satisfactory.models.vehiclerouting;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import satisfactory.models.vehiclerouting.domain.Haversine;
import satisfactory.models.vehiclerouting.domain.Location;
import satisfactory.models.vehiclerouting.domain.Vehicle;
import satisfactory.models.vehiclerouting.domain.VehicleRoutePlan;
import satisfactory.models.vehiclerouting.domain.Visit;
import satisfactory.spi.InvalidDataset;
import satisfactory.spi.Json;

/** The wire shape (input.schema.json) to and from the planning domain. */
final class VehicleRoutingCodecs {

    record VehicleDto(String id, int capacity, double[] homeLocation, LocalDateTime departureTime, List<String> visits) {
    }

    record VisitDto(String id, String name, double[] location, int demand, LocalDateTime minStartTime,
            LocalDateTime maxEndTime, Duration serviceDuration, String vehicle, LocalDateTime arrivalTime) {
    }

    record PlanDto(List<VehicleDto> vehicles, List<VisitDto> visits) {
    }

    /** What decoding found that validation should report. */
    static final class Problems {
        final List<String[]> unknownVisits = new ArrayList<>();
        final List<String> visitsRoutedTwice = new ArrayList<>();
    }

    static final Map<VehicleRoutePlan, Problems> PROBLEMS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private VehicleRoutingCodecs() {
    }

    static VehicleRoutePlan decode(JsonNode node, boolean rememberAssignments) throws InvalidDataset {
        PlanDto dto = Json.convert(node, PlanDto.class);
        List<Location> locations = new ArrayList<>();
        Map<String, Visit> visitsById = new HashMap<>();
        List<Visit> visits = new ArrayList<>();
        for (VisitDto v : orEmpty(dto.visits())) {
            Location location = location(v.location());
            locations.add(location);
            Visit visit = new Visit(v.id(), v.name(), location, v.demand(), v.minStartTime(), v.maxEndTime(), v.serviceDuration());
            visits.add(visit);
            visitsById.putIfAbsent(v.id(), visit);
        }
        Problems problems = new Problems();
        Set<String> routed = new LinkedHashSet<>();
        List<Vehicle> vehicles = new ArrayList<>();
        for (VehicleDto v : orEmpty(dto.vehicles())) {
            Location home = location(v.homeLocation());
            locations.add(home);
            Vehicle vehicle = new Vehicle(v.id(), v.capacity(), home, v.departureTime());
            for (String visitId : orEmpty(v.visits())) {
                Visit visit = visitsById.get(visitId);
                if (visit == null) {
                    problems.unknownVisits.add(new String[] { v.id(), visitId });
                } else if (!routed.add(visitId)) {
                    problems.visitsRoutedTwice.add(visitId);
                } else {
                    vehicle.getVisits().add(visit);
                    if (rememberAssignments) {
                        visit.setPreviousVehicle(v.id());
                    }
                }
            }
            vehicles.add(vehicle);
        }
        Haversine.initDrivingTimeMaps(locations);
        VehicleRoutePlan plan = new VehicleRoutePlan(vehicles, visits);
        PROBLEMS.put(plan, problems);
        return plan;
    }

    static JsonNode encode(VehicleRoutePlan plan) {
        List<VehicleDto> vehicles = plan.getVehicles().stream()
                .map(v -> new VehicleDto(v.getId(), v.getCapacity(), coordinates(v.getHomeLocation()), v.getDepartureTime(),
                        v.getVisits().stream().map(Visit::getId).toList()))
                .toList();
        List<VisitDto> visits = plan.getVisits().stream()
                .map(v -> new VisitDto(v.getId(), v.getName(), coordinates(v.getLocation()), v.getDemand(), v.getMinStartTime(),
                        v.getMaxEndTime(), v.getServiceDuration(), v.getVehicle() == null ? null : v.getVehicle().getId(),
                        v.getArrivalTime()))
                .toList();
        return Json.tree(new PlanDto(vehicles, visits));
    }

    private static Location location(double[] coordinates) throws InvalidDataset {
        if (coordinates == null || coordinates.length != 2) {
            throw new InvalidDataset("a location is [latitude, longitude]");
        }
        return new Location(coordinates[0], coordinates[1]);
    }

    private static double[] coordinates(Location location) {
        return new double[] { location.getLatitude(), location.getLongitude() };
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
