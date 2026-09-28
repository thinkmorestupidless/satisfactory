package satisfactory.spi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The models a service hosts, registered explicitly ({@code ModelCatalog.of(EmployeeScheduling.V1, …)}):
 * no classpath scanning, so this is the complete inventory, and a duplicate fails at startup.
 */
public final class ModelCatalog {

    private final Map<String, ModelRuntime<?, ?>> byKey;

    private ModelCatalog(Map<String, ModelRuntime<?, ?>> byKey) {
        this.byKey = byKey;
    }

    public static ModelCatalog of(SolverModel<?, ?>... models) {
        Map<String, ModelRuntime<?, ?>> byKey = new LinkedHashMap<>();
        for (SolverModel<?, ?> model : models) {
            String key = model.key().key();
            if (byKey.containsKey(key)) {
                throw new IllegalArgumentException("model " + key + " is registered twice");
            }
            byKey.put(key, runtimeOf(model));
        }
        return new ModelCatalog(byKey);
    }

    private static <S, Sc extends ai.timefold.solver.core.api.score.Score<Sc>> ModelRuntime<S, Sc> runtimeOf(
            SolverModel<S, Sc> model) {
        return ModelRuntime.of(model);
    }

    /** By {@code employee-scheduling/v1}. */
    public Optional<ModelRuntime<?, ?>> byKey(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    public Optional<ModelRuntime<?, ?>> find(String registrationKey, String version) {
        return byKey(registrationKey + "/" + version);
    }

    public List<ModelRuntime<?, ?>> all() {
        return List.copyOf(byKey.values());
    }

    public List<String> keys() {
        return List.copyOf(byKey.keySet());
    }
}
