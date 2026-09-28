package satisfactory.spi;

/**
 * A model's identity: the registration key in the path ({@code /api/models/employee-scheduling}), its
 * API version ({@code v1}) and the name of the thing a request submits ({@code schedules}).
 */
public record ModelKey(String registrationKey, String version, String entity) {

    /** {@code employee-scheduling/v1}: how workers name the models they can solve. */
    public String key() {
        return registrationKey + "/" + version;
    }
}
