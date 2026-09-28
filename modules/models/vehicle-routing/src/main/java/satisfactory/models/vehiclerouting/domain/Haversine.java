/*
 * Ported from Timefold Quickstarts (use-cases/vehicle-routing), Apache License 2.0,
 * https://github.com/TimefoldAI/timefold-quickstarts. Quarkus, REST and Jackson annotations removed;
 * previous assignments and constraint weight overrides added for satisfactory.
 */
package satisfactory.models.vehiclerouting.domain;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** Driving time as the crow flies at 50 km/h: the quickstart's stand-in for a road network. */
public final class Haversine {

    public static final int AVERAGE_SPEED_KMPH = 50;
    private static final int TWICE_EARTH_RADIUS_IN_M = 2 * 6371000;

    private Haversine() {
    }

    public static long drivingSeconds(Location from, Location to) {
        if (from == to) {
            return 0L;
        }
        double[] a = cartesian(from);
        double[] b = cartesian(to);
        double dX = a[0] - b[0];
        double dY = a[1] - b[1];
        double dZ = a[2] - b[2];
        long meters = Math.round(TWICE_EARTH_RADIUS_IN_M * Math.asin(Math.sqrt(dX * dX + dY * dY + dZ * dZ)));
        return Math.round((double) meters / AVERAGE_SPEED_KMPH * 3.6);
    }

    public static void initDrivingTimeMaps(Collection<Location> locations) {
        for (Location from : locations) {
            Map<Location, Long> times = new HashMap<>();
            for (Location to : locations) {
                times.put(to, drivingSeconds(from, to));
            }
            from.setDrivingTimeSeconds(times);
        }
    }

    private static double[] cartesian(Location location) {
        double lat = Math.toRadians(location.getLatitude());
        double lon = Math.toRadians(location.getLongitude());
        return new double[] { 0.5 * Math.cos(lat) * Math.sin(lon), 0.5 * Math.cos(lat) * Math.cos(lon), 0.5 * Math.sin(lat) };
    }
}
