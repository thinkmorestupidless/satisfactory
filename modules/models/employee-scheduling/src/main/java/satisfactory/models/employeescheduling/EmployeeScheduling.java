package satisfactory.models.employeescheduling;

import java.math.BigDecimal;
import java.util.List;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.HardSoftBigDecimalScore;
import ai.timefold.solver.core.config.solver.SolverConfig;

import com.fasterxml.jackson.databind.JsonNode;

import satisfactory.models.employeescheduling.domain.EmployeeSchedule;
import satisfactory.models.employeescheduling.domain.Shift;
import satisfactory.models.employeescheduling.solver.EmployeeSchedulingConstraintProvider;
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

/** Employee shift scheduling, {@code v1}: shifts to employees with the right skill, fairly and around availability. */
public final class EmployeeScheduling implements SolverModel<EmployeeSchedule, HardSoftBigDecimalScore> {

    public static final EmployeeScheduling V1 = new EmployeeScheduling();

    /** In the constraint provider's order. Names are the provider's; keys are the request's weight fields. */
    static final List<ConstraintInfo> CONSTRAINTS = List.of(
            new ConstraintInfo("Missing required skill", "missingRequiredSkill",
                    "A shift is staffed by an employee without the skill it requires.", Level.HARD, 1),
            new ConstraintInfo("Overlapping shift", "overlappingShift",
                    "An employee works two shifts that overlap; penalised per overlapping minute.", Level.HARD, 1),
            new ConstraintInfo("At least 10 hours between 2 shifts", "atLeast10HoursBetweenTwoShifts",
                    "An employee has less than ten hours' rest between shifts; penalised per missing minute.",
                    Level.HARD, 1),
            new ConstraintInfo("Max one shift per day", "maxOneShiftPerDay",
                    "An employee works more than one shift starting on the same day.", Level.HARD, 1),
            new ConstraintInfo("Unavailable employee", "unavailableEmployee",
                    "An employee works on a day they are unavailable; penalised per minute.", Level.HARD, 1),
            new ConstraintInfo("Undesired day for employee", "undesiredDayForEmployee",
                    "An employee works on a day they would rather not; penalised per minute.", Level.SOFT, 1),
            new ConstraintInfo("Desired day for employee", "desiredDayForEmployee",
                    "An employee works on a day they want to; rewarded per minute.", Level.SOFT, 1),
            new ConstraintInfo("Balance employee shift assignments", "balanceEmployeeShiftAssignments",
                    "Shift counts are uneven across employees; penalised by their unfairness.", Level.SOFT, 1));

    private static final ScoreCodec<HardSoftBigDecimalScore> SCORE = new ScoreCodec<>() {
        @Override
        public HardSoftBigDecimalScore parse(String text) {
            return HardSoftBigDecimalScore.parseScore(text);
        }

        @Override
        public HardSoftBigDecimalScore weight(Level level, long weight) {
            BigDecimal w = BigDecimal.valueOf(weight);
            return switch (level) {
                case HARD -> HardSoftBigDecimalScore.ofHard(w);
                case SOFT -> HardSoftBigDecimalScore.ofSoft(w);
                case MEDIUM -> throw new IllegalArgumentException("employee scheduling has no medium level");
            };
        }
    };

    private final JsonSchema input = JsonSchema.resource(EmployeeScheduling.class,
            "/satisfactory/models/employeescheduling/input.schema.json");
    private final JsonSchema output = JsonSchema.resource(EmployeeScheduling.class,
            "/satisfactory/models/employeescheduling/output.schema.json");

    private EmployeeScheduling() {
    }

    @Override
    public ModelKey key() {
        return new ModelKey("employee-scheduling", "v1", "schedules");
    }

    @Override
    public Maturity maturity() {
        return Maturity.STABLE;
    }

    @Override
    public ModelInfo info() {
        return new ModelInfo("Employee Shift Scheduling",
                "Assign employees to shifts: every shift gets an employee with the skill it needs, nobody works "
                        + "overlapping shifts, on unavailable days or without ten hours' rest, and the rest is "
                        + "balanced against preferences.",
                List.of("pinning", "real-time planning", "weights"));
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
        return List.of(new PatchPath("/employees", "name"), new PatchPath("/shifts", "id"));
    }

    @Override
    public List<ConstraintInfo> constraints() {
        return CONSTRAINTS;
    }

    @Override
    public List<MetricInfo> kpiDescriptors() {
        return EmployeeSchedulingMetrics.KPIS;
    }

    @Override
    public List<MetricInfo> inputMetricDescriptors() {
        return EmployeeSchedulingMetrics.INPUT;
    }

    @Override
    public List<IssueType> validationIssueTypes() {
        return EmployeeSchedulingValidation.CATALOG;
    }

    @Override
    public List<DemoDataset> demoData() {
        return DemoData.ALL;
    }

    @Override
    public SolverConfig baseConfig() {
        return new SolverConfig()
                .withSolutionClass(EmployeeSchedule.class)
                .withEntityClasses(Shift.class)
                .withConstraintProviderClass(EmployeeSchedulingConstraintProvider.class);
    }

    @Override
    public ScoreCodec<HardSoftBigDecimalScore> scoreCodec() {
        return SCORE;
    }

    @Override
    public EmployeeSchedule decodeProblem(JsonNode modelInput) throws InvalidDataset {
        return EmployeeSchedulingCodecs.decode(modelInput, false);
    }

    @Override
    public EmployeeSchedule decodeSolution(JsonNode modelOutput) throws InvalidDataset {
        return EmployeeSchedulingCodecs.decode(modelOutput, true);
    }

    @Override
    public JsonNode encodeSolution(EmployeeSchedule solution) {
        return EmployeeSchedulingCodecs.encode(solution);
    }

    @Override
    public ValidationResult validate(EmployeeSchedule problem) {
        return EmployeeSchedulingValidation.validate(problem);
    }

    @Override
    public JsonNode inputMetrics(EmployeeSchedule problem) {
        return EmployeeSchedulingMetrics.input(problem);
    }

    @Override
    public JsonNode kpis(EmployeeSchedule solution) {
        return EmployeeSchedulingMetrics.kpis(solution);
    }

    @Override
    public void setWeights(EmployeeSchedule solution, ConstraintWeightOverrides<HardSoftBigDecimalScore> overrides) {
        solution.setConstraintWeightOverrides(overrides);
    }

    @Override
    public HardSoftBigDecimalScore score(EmployeeSchedule solution) {
        return solution.getScore();
    }
}
