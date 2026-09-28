package satisfactory.models.vehiclerouting;

import java.util.List;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.config.solver.SolverConfig;

import com.fasterxml.jackson.databind.JsonNode;

import satisfactory.models.vehiclerouting.domain.Vehicle;
import satisfactory.models.vehiclerouting.domain.VehicleRoutePlan;
import satisfactory.models.vehiclerouting.domain.Visit;
import satisfactory.models.vehiclerouting.solver.VehicleRoutingConstraintProvider;
import satisfactory.spi.ConstraintInfo;
import satisfactory.spi.ConstraintInfo.Level;
import satisfactory.spi.DemoDataset;
import satisfactory.spi.InvalidDataset;
import satisfactory.spi.IssueType;
import satisfactory.spi.JsonSchema;
import satisfactory.spi.Maturity;
import satisfactory.spi.MetricInfo;
import satisfactory.spi.ModelInfo;
import satisfactory.spi.ModelKey;
import satisfactory.spi.PatchPath;
import satisfactory.spi.ScoreCodec;
import satisfactory.spi.SolverModel;
import satisfactory.spi.ValidationResult;

/** Capacitated vehicle routing with time windows, {@code v1}: a list variable, hard/medium/soft. */
public final class VehicleRouting implements SolverModel<VehicleRoutePlan, HardMediumSoftScore> {

    public static final VehicleRouting V1 = new VehicleRouting();

    static final List<ConstraintInfo> CONSTRAINTS = List.of(
            new ConstraintInfo("vehicleCapacity", "vehicleCapacity",
                    "A vehicle carries more than its capacity; penalised per unit over.", Level.HARD, 1),
            new ConstraintInfo("serviceFinishedAfterMaxEndTime", "serviceFinishedAfterMaxEndTime",
                    "A visit's service ends after its time window; penalised per minute late.", Level.HARD, 1),
            new ConstraintInfo("maximizeVisitsAssigned", "maximizeVisitsAssigned",
                    "A visit is on no route; penalised per minute of its service.", Level.MEDIUM, 1),
            new ConstraintInfo("minimizeTravelTime", "minimizeTravelTime",
                    "Driving time across every route, in seconds.", Level.SOFT, 1));

    private static final ScoreCodec<HardMediumSoftScore> SCORE = new ScoreCodec<>() {
        @Override
        public HardMediumSoftScore parse(String text) {
            return HardMediumSoftScore.parseScore(text);
        }

        @Override
        public HardMediumSoftScore weight(Level level, long weight) {
            return switch (level) {
                case HARD -> HardMediumSoftScore.ofHard(weight);
                case MEDIUM -> HardMediumSoftScore.ofMedium(weight);
                case SOFT -> HardMediumSoftScore.ofSoft(weight);
            };
        }
    };

    private final JsonSchema input = JsonSchema.resource(VehicleRouting.class,
            "/satisfactory/models/vehiclerouting/input.schema.json");
    private final JsonSchema output = JsonSchema.resource(VehicleRouting.class,
            "/satisfactory/models/vehiclerouting/output.schema.json");

    private VehicleRouting() {
    }

    @Override
    public ModelKey key() {
        return new ModelKey("vehicle-routing", "v1", "route-plans");
    }

    @Override
    public Maturity maturity() {
        return Maturity.PREVIEW;
    }

    @Override
    public ModelInfo info() {
        return new ModelInfo("Vehicle Routing",
                "Route vehicles to visits: within each vehicle's capacity and each visit's time window, as many visits "
                        + "as possible, with as little driving as possible.",
                List.of("time windows", "capacities", "real-time planning", "weights"));
    }

    @Override
    public JsonSchema inputSchema() {
        return input;
    }

    @Override
    public JsonSchema outputSchema() {
        return output;
    }

    @Override
    public List<PatchPath> patchPaths() {
        return List.of(new PatchPath("/vehicles", "id"), new PatchPath("/visits", "id"));
    }

    @Override
    public List<ConstraintInfo> constraints() {
        return CONSTRAINTS;
    }

    @Override
    public List<MetricInfo> kpiDescriptors() {
        return VehicleRoutingMetrics.KPIS;
    }

    @Override
    public List<MetricInfo> inputMetricDescriptors() {
        return VehicleRoutingMetrics.INPUT;
    }

    @Override
    public List<IssueType> validationIssueTypes() {
        return VehicleRoutingValidation.CATALOG;
    }

    @Override
    public List<DemoDataset> demoData() {
        return DemoData.ALL;
    }

    @Override
    public SolverConfig baseConfig() {
        return new SolverConfig()
                .withSolutionClass(VehicleRoutePlan.class)
                .withEntityClasses(Vehicle.class, Visit.class)
                .withConstraintProviderClass(VehicleRoutingConstraintProvider.class);
    }

    @Override
    public ScoreCodec<HardMediumSoftScore> scoreCodec() {
        return SCORE;
    }

    @Override
    public VehicleRoutePlan decodeProblem(JsonNode modelInput) throws InvalidDataset {
        return VehicleRoutingCodecs.decode(modelInput, false);
    }

    @Override
    public VehicleRoutePlan decodeSolution(JsonNode modelOutput) throws InvalidDataset {
        return VehicleRoutingCodecs.decode(modelOutput, true);
    }

    @Override
    public JsonNode encodeSolution(VehicleRoutePlan solution) {
        return VehicleRoutingCodecs.encode(solution);
    }

    @Override
    public ValidationResult validate(VehicleRoutePlan problem) {
        return VehicleRoutingValidation.validate(problem);
    }

    @Override
    public JsonNode inputMetrics(VehicleRoutePlan problem) {
        return VehicleRoutingMetrics.input(problem);
    }

    @Override
    public JsonNode kpis(VehicleRoutePlan solution) {
        return VehicleRoutingMetrics.kpis(solution);
    }

    @Override
    public void setWeights(VehicleRoutePlan solution, ConstraintWeightOverrides<HardMediumSoftScore> overrides) {
        solution.setConstraintWeightOverrides(overrides);
    }

    @Override
    public HardMediumSoftScore score(VehicleRoutePlan solution) {
        return solution.getScore();
    }
}
