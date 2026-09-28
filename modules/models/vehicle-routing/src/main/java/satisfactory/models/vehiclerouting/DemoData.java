/*
 * Ported from Timefold Quickstarts (use-cases/vehicle-routing VehicleRouteDemoResource), Apache License 2.0.
 * Routes start on a fixed day rather than tomorrow, so a demo dataset is the same every time.
 */
package satisfactory.models.vehiclerouting;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.PrimitiveIterator;
import java.util.Random;

import com.fasterxml.jackson.databind.JsonNode;

import satisfactory.models.vehiclerouting.domain.Haversine;
import satisfactory.models.vehiclerouting.domain.Location;
import satisfactory.models.vehiclerouting.domain.Vehicle;
import satisfactory.models.vehiclerouting.domain.VehicleRoutePlan;
import satisfactory.models.vehiclerouting.domain.Visit;
import satisfactory.spi.DemoDataset;

final class DemoData {

    record Parameters(long seed, int visitCount, int vehicleCount, LocalTime vehicleStartTime, int minDemand, int maxDemand,
            int minVehicleCapacity, int maxVehicleCapacity, double south, double west, double north, double east) {
    }

    static final LocalDate DAY = LocalDate.of(2026, 10, 6);

    static final Parameters PHILADELPHIA = new Parameters(2, 55, 6, LocalTime.of(7, 30), 1, 2, 15, 30,
            39.7656099067391, -76.83782328143754, 40.77636644354855, -74.9300739430771);
    static final Parameters FIRENZE = new Parameters(2, 77, 6, LocalTime.of(7, 30), 1, 2, 20, 40,
            43.751466, 11.177210, 43.809291, 11.290195);

    static final List<DemoDataset> ALL = List.of(
            new DemoDataset("PHILADELPHIA", "55 visits, 6 vehicles",
                    "Deliveries around Philadelphia with morning and afternoon time windows.",
                    List.of("small", "usa"), Map.of("spentLimit", "PT30S"), () -> encode(PHILADELPHIA)),
            new DemoDataset("FIRENZE", "77 visits, 6 vehicles",
                    "Deliveries across Florence with morning and afternoon time windows.",
                    List.of("medium", "italy"), Map.of("spentLimit", "PT1M"), () -> encode(FIRENZE)));

    private static final String[] FIRST_NAMES = { "Amy", "Beth", "Carl", "Dan", "Elsa", "Flo", "Gus", "Hugo", "Ivy", "Jay" };
    private static final String[] LAST_NAMES = { "Cole", "Fox", "Green", "Jones", "King", "Li", "Poe", "Rye", "Smith", "Watt" };
    private static final int[] SERVICE_MINUTES = { 10, 20, 30, 40 };

    private DemoData() {
    }

    static JsonNode encode(Parameters p) {
        return VehicleRoutingCodecs.encode(generate(p));
    }

    static VehicleRoutePlan generate(Parameters p) {
        Random random = new Random(p.seed());
        PrimitiveIterator.OfDouble latitudes = random.doubles(p.south(), p.north()).iterator();
        PrimitiveIterator.OfDouble longitudes = random.doubles(p.west(), p.east()).iterator();
        PrimitiveIterator.OfInt demand = random.ints(p.minDemand(), p.maxDemand() + 1).iterator();
        PrimitiveIterator.OfInt capacity = random.ints(p.minVehicleCapacity(), p.maxVehicleCapacity() + 1).iterator();
        List<Location> locations = new ArrayList<>();
        List<Vehicle> vehicles = new ArrayList<>();
        for (int i = 1; i <= p.vehicleCount(); i++) {
            Location home = new Location(latitudes.nextDouble(), longitudes.nextDouble());
            locations.add(home);
            vehicles.add(new Vehicle(Integer.toString(i), capacity.nextInt(), home, DAY.atTime(p.vehicleStartTime())));
        }
        List<Visit> visits = new ArrayList<>();
        for (int i = 1; i <= p.visitCount(); i++) {
            boolean morning = random.nextBoolean();
            LocalDateTime start = DAY.atTime(morning ? LocalTime.of(8, 0) : LocalTime.of(13, 0));
            LocalDateTime end = DAY.atTime(morning ? LocalTime.of(12, 0) : LocalTime.of(18, 0));
            int minutes = SERVICE_MINUTES[random.nextInt(SERVICE_MINUTES.length)];
            String name = FIRST_NAMES[random.nextInt(FIRST_NAMES.length)] + " " + LAST_NAMES[random.nextInt(LAST_NAMES.length)];
            Location location = new Location(latitudes.nextDouble(), longitudes.nextDouble());
            locations.add(location);
            visits.add(new Visit(Integer.toString(i), name, location, demand.nextInt(), start, end, Duration.ofMinutes(minutes)));
        }
        Haversine.initDrivingTimeMaps(locations);
        return new VehicleRoutePlan(vehicles, visits);
    }
}
