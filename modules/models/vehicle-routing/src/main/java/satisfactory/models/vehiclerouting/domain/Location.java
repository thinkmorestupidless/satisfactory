/*
 * Ported from Timefold Quickstarts (use-cases/vehicle-routing), Apache License 2.0,
 * https://github.com/TimefoldAI/timefold-quickstarts. Quarkus, REST and Jackson annotations removed;
 * previous assignments and constraint weight overrides added for satisfactory.
 */
package satisfactory.models.vehiclerouting.domain;

import java.util.Map;

/** A point, and the driving time from it to every other point in the plan (filled in when the plan is built). */
public class Location {

    private final double latitude;
    private final double longitude;
    private Map<Location, Long> drivingTimeSeconds;

    public Location(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public Map<Location, Long> getDrivingTimeSeconds() {
        return drivingTimeSeconds;
    }

    public void setDrivingTimeSeconds(Map<Location, Long> drivingTimeSeconds) {
        this.drivingTimeSeconds = drivingTimeSeconds;
    }

    public long getDrivingTimeTo(Location location) {
        return drivingTimeSeconds.get(location);
    }

    @Override
    public String toString() {
        return latitude + "," + longitude;
    }
}
