package satisfactory.spi;

import java.util.List;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.Score;
import ai.timefold.solver.core.config.solver.SolverConfig;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A planning problem type: the only thing a model implements, and the only thing it sees. No ankka, no
 * HTTP, no Scala (contracts/model-spi.md).
 *
 * <p>Everything a request may influence is termination and constraint weights; phases, move selectors and
 * the constraint provider belong to the model.
 *
 * @param <S>      the {@code @PlanningSolution} class
 * @param <Score_> its score type
 */
public interface SolverModel<S, Score_ extends Score<Score_>> {

    ModelKey key();

    Maturity maturity();

    ModelInfo info();

    JsonSchema inputSchema();

    JsonSchema outputSchema();

    default JsonSchema overridesSchema() {
        return JsonSchema.overridesFor(constraints());
    }

    List<PatchPath> patchPaths();

    List<ConstraintInfo> constraints();

    List<MetricInfo> kpiDescriptors();

    List<MetricInfo> inputMetricDescriptors();

    List<IssueType> validationIssueTypes();

    List<DemoDataset> demoData();

    /** Solution and entity classes, the constraint provider, phases. Termination is left to overrides. */
    SolverConfig baseConfig();

    ScoreCodec<Score_> scoreCodec();

    /** A schema-valid input as an unsolved planning problem; assignments in the input are kept. */
    S decodeProblem(JsonNode modelInput) throws InvalidDataset;

    /**
     * A model output (or an input carrying assignments) as a solution to continue from: assignments set, so
     * construction skips them and local search starts from them; each entity also remembers its assignment
     * so {@link #kpis} can report disruption.
     */
    S decodeSolution(JsonNode modelOutput) throws InvalidDataset;

    /** The output schema's JSON: the input plus every assignment. Accepted unchanged by {@link #decodeSolution}. */
    JsonNode encodeSolution(S solution);

    ValidationResult validate(S problem);

    JsonNode inputMetrics(S problem);

    /** Cheap: runs for every recorded improvement. Must not include entity bodies. */
    JsonNode kpis(S solution);

    void setWeights(S solution, ConstraintWeightOverrides<Score_> overrides);

    Score_ score(S solution);
}
