package satisfactory.spi;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.Score;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverConfigOverride;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.api.solver.SolverJob;
import ai.timefold.solver.core.api.solver.SolverManager;
import ai.timefold.solver.core.config.solver.SolverManagerConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A model plus the Timefold machinery built from it once: the solver factory, the solution manager and,
 * on a worker, a solver manager. The typed core of everything the runner and the API do with a model;
 * callers outside Java hold solutions as {@code Object}.
 *
 * <p>Thread-safe: the factory and the solution manager are, and the solver manager is created once.
 */
public final class ModelRuntime<S, Score_ extends Score<Score_>> {

    /** A score as the platform carries it: formatted, as comparable levels, and whether it is feasible. */
    public record ScoreView(String score, List<BigDecimal> key, boolean feasible) {
    }

    /** One constraint's share of a score: its weight at its level, and what it contributed. */
    public record ConstraintContribution(String name, String weight, String score) {
    }

    public record AnalysisView(String score, List<ConstraintContribution> constraints) {
    }

    private final SolverModel<S, Score_> model;
    private final SolverFactory<S> solverFactory;
    private final SolutionManager<S, Score_> solutionManager;
    private volatile SolverManager<S> solverManager;

    private ModelRuntime(SolverModel<S, Score_> model) {
        this.model = model;
        this.solverFactory = SolverFactory.create(model.baseConfig());
        this.solutionManager = SolutionManager.create(solverFactory);
    }

    public static <S, Score_ extends Score<Score_>> ModelRuntime<S, Score_> of(SolverModel<S, Score_> model) {
        return new ModelRuntime<>(model);
    }

    public SolverModel<S, Score_> model() {
        return model;
    }

    public ModelKey key() {
        return model.key();
    }

    // ── Validation ─────────────────────────────────────────────────────────────────────────────

    /** Schema violations of a model input; empty when it is valid. */
    public List<String> validateInput(JsonNode modelInput) {
        return model.inputSchema().validate(modelInput);
    }

    /** Schema violations of a weights object ({@code <key>Weight} fields). */
    public List<String> validateOverrides(JsonNode overrides) {
        return model.overridesSchema().validate(overrides);
    }

    public ValidationResult validate(Object problem) {
        return model.validate(cast(problem));
    }

    // ── Decoding with weights ──────────────────────────────────────────────────────────────────

    /** An unsolved planning problem, carrying the weights it is to be scored and solved with. */
    public Object problem(JsonNode modelInput, Map<String, Long> weights) throws InvalidDataset {
        S problem = model.decodeProblem(modelInput);
        model.setWeights(problem, overrides(weights));
        return problem;
    }

    /** A solution to continue from, carrying the weights. */
    public Object solution(JsonNode modelOutput, Map<String, Long> weights) throws InvalidDataset {
        S solution = model.decodeSolution(modelOutput);
        model.setWeights(solution, overrides(weights));
        return solution;
    }

    /**
     * The weights as Timefold wants them: every constraint, its multiplier (the request's, or the model's
     * default) as a score at its level. Keys are the request's field names, {@code <key>Weight}.
     */
    public ConstraintWeightOverrides<Score_> overrides(Map<String, Long> weights) {
        Map<String, Score_> byName = new HashMap<>();
        for (ConstraintInfo c : model.constraints()) {
            long w = weights.getOrDefault(c.weightField(), c.defaultWeight());
            byName.put(c.name(), model.scoreCodec().weight(c.level(), w));
        }
        return ConstraintWeightOverrides.of(byName);
    }

    // ── Scoring ────────────────────────────────────────────────────────────────────────────────

    /** Scores a solution as it stands (Community: {@code SolutionManager.update}). */
    public ScoreView score(Object solution) {
        Score_ score = solutionManager.update(cast(solution));
        return view(score);
    }

    /** The score a solution already carries, as the solver left it; no recalculation. */
    public ScoreView currentScore(Object solution) {
        Score_ score = model.score(cast(solution));
        return score == null ? null : view(score);
    }

    public ScoreView view(Score_ score) {
        ScoreCodec<Score_> codec = model.scoreCodec();
        return new ScoreView(codec.format(score), codec.key(score), score.isFeasible());
    }

    /**
     * Per-constraint contributions by ablation (research R2): score once with the given weights, then once
     * per constraint with its weight at zero; the difference is what that constraint contributed. The
     * solution's weights are restored before returning. {@code constraints + 1} score calculations.
     */
    public AnalysisView analyze(Object solution, Map<String, Long> weights) {
        S s = cast(solution);
        ScoreCodec<Score_> codec = model.scoreCodec();
        model.setWeights(s, overrides(weights));
        Score_ total = solutionManager.update(s);
        List<ConstraintContribution> contributions = new ArrayList<>();
        try {
            for (ConstraintInfo c : model.constraints()) {
                long w = weights.getOrDefault(c.weightField(), c.defaultWeight());
                Map<String, Long> without = new HashMap<>(weights);
                without.put(c.weightField(), 0L);
                model.setWeights(s, overrides(without));
                Score_ rest = solutionManager.update(s);
                contributions.add(new ConstraintContribution(
                        c.name(),
                        codec.format(codec.weight(c.level(), w)),
                        codec.format(total.subtract(rest))));
            }
        } finally {
            model.setWeights(s, overrides(weights));
            solutionManager.update(s);
        }
        return new AnalysisView(codec.format(total), contributions);
    }

    // ── Encoding ───────────────────────────────────────────────────────────────────────────────

    public JsonNode encode(Object solution) {
        return model.encodeSolution(cast(solution));
    }

    public JsonNode kpis(Object solution) {
        return model.kpis(cast(solution));
    }

    public JsonNode inputMetrics(Object problem) {
        return model.inputMetrics(cast(problem));
    }

    // ── Solving (workers only) ─────────────────────────────────────────────────────────────────

    /** Creates this runtime's solver manager; one solve per slot. Idempotent. */
    public synchronized void startSolving(int parallelSolverCount) {
        if (solverManager == null) {
            solverManager = SolverManager.create(solverFactory,
                    new SolverManagerConfig().withParallelSolverCount(Integer.toString(parallelSolverCount)));
        }
    }

    /**
     * Starts solving {@code problem} under {@code problemId}. The best-solution consumer is handed a planning
     * clone on a solver-manager thread, so encoding it does not race the solver.
     */
    public SolverJob<S> solve(
            String problemId,
            Object problem,
            TerminationConfig termination,
            Consumer<Object> onBest,
            Consumer<Object> onFinal,
            BiConsumer<Object, Throwable> onError) {
        SolverManager<S> manager = solverManager;
        if (manager == null) {
            throw new IllegalStateException("startSolving was not called for " + model.key().key());
        }
        return manager.solveBuilder()
                .withProblemId(problemId)
                .withProblem(cast(problem))
                .withConfigOverride(new SolverConfigOverride().withTerminationConfig(termination))
                .withBestSolutionEventConsumer(event -> onBest.accept(event.solution()))
                .withFinalBestSolutionEventConsumer(event -> onFinal.accept(event.solution()))
                .withExceptionHandler(onError::accept)
                .run();
    }

    public void terminateEarly(String problemId) {
        SolverManager<S> manager = solverManager;
        if (manager != null) {
            manager.terminateEarly(problemId);
        }
    }

    public synchronized void close() {
        if (solverManager != null) {
            solverManager.close();
            solverManager = null;
        }
    }

    @SuppressWarnings("unchecked")
    private S cast(Object solution) {
        return (S) solution;
    }
}
