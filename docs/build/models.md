---
title: Write a model
description: Add a planning problem type to the catalog by implementing the Java SolverModel interface — schemas, constraints, codecs, validation, metrics and demo data — and test it through ModelRuntime with nothing else running.
kind: guide
languages: [java, scala]
related: [concepts/models-and-datasets.md, reference/employee-scheduling.md, reference/vehicle-routing.md, reference/model-api.md]
---

# Write a model

A model is a planning problem type: compiled code that knows its domain classes, its constraints and
how a dataset on the wire becomes a Timefold planning problem. Every model implements one Java
interface, `SolverModel`, and sees nothing else: no ankka, no HTTP, no Scala. The API mounts a model's
routes from what the interface declares, and a worker solves through it. Models are Java because
Timefold reads annotations from bean-style classes, which is what Java produces without ceremony.

A model is registered explicitly in the catalog the solver builds at startup, `ModelCatalog.of(...)`.
There is no classpath scanning, so the catalog is the complete inventory and a key registered twice
fails the service at startup rather than answering for the wrong model.

## The interface

`SolverModel<S, Score_>` is parameterised by the `@PlanningSolution` class and its Timefold score type.
This is the whole of it:

<!-- include: modules/model-spi/src/main/java/satisfactory/spi/SolverModel.java -->
```java
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
```

Everything a request may influence is termination and constraint weights. Phases, move selectors and
the constraint provider belong to the model, because they are what makes a model good and they are not
safely tunable by someone who has not read its code.

## What a model supplies, and where it shows up

| The model supplies | It becomes |
|---|---|
| `key()`: registration key, version, entity name | the model's paths, `/api/models/employee-scheduling/v1/schedules` |
| `maturity()` and `info()` | the catalog entry: name, description, features, maturity level |
| `inputSchema()` and `outputSchema()` | the `400` a submit gets for a malformed input, the schemas in the descriptor, the tool parameter an ankka agent is given |
| `constraints()` | the `overridesSchema()`, one `<key>Weight` integer field per constraint; the names in a score analysis |
| `patchPaths()` | the collections a `from-patch` request may address by `[field=value]` |
| `kpiDescriptors()` and `inputMetricDescriptors()` | the typed, titled fields in `kpis` and `inputMetrics` |
| `validationIssueTypes()` | the issue catalog at `validation-issue-types` |
| `demoData()` | the datasets under `demo-data` |
| `baseConfig()` | the Timefold `SolverConfig`: solution class, entity classes, constraint provider |
| `scoreCodec()` | how a score is parsed, formatted, and turned into a weight at a level |
| `decodeProblem`, `decodeSolution`, `encodeSolution` | the dataset on the wire, the solution on the wire, and a solution as a warm start |
| `validate`, `inputMetrics`, `kpis` | the model-level validation after the schema, and the metrics computed on every recorded improvement |
| `setWeights` and `score` | how the request's weights reach Timefold, and how the solution's score is read |

## Identity and catalog information

Employee scheduling is one of the two models in the repository, and the rest of this page shows its
pieces. Its key names the registration key, the API version and what a request submits:

<!-- include: modules/models/employee-scheduling/src/main/java/satisfactory/models/employeescheduling/EmployeeScheduling.java#identity -->
```java
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
```

A `STABLE` model's API is backwards compatible within its version; a breaking change is a new version
with a new path. The maturity levels are `TEMPLATE`, `EXPERIMENTAL`, `PREVIEW`, `STABLE` and `DEPRECATED`.

## Constraints and weights

`constraints()` lists every constraint in the provider's order. The `name` is the one the constraint
provider gives with `asConstraint(name)`, because that is what Timefold's weight overrides are keyed
by; the `key` is the request's field name with `Weight` appended; the level is `HARD`, `MEDIUM` or
`SOFT`; and the default weight is what applies when a request sets nothing. A weight of zero disables a
constraint:

<!-- include: modules/models/employee-scheduling/src/main/java/satisfactory/models/employeescheduling/EmployeeScheduling.java#constraints -->
```java
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
```

Weights reach Timefold through a `ConstraintWeightOverrides` field on the solution class, which Timefold
finds by its type. `setWeights` stores the overrides the runtime builds from the request, and `score`
reads the solution's score back:

<!-- include: modules/models/employee-scheduling/src/main/java/satisfactory/models/employeescheduling/EmployeeScheduling.java#weights -->
```java
@Override
public void setWeights(EmployeeSchedule solution, ConstraintWeightOverrides<HardSoftBigDecimalScore> overrides) {
    solution.setConstraintWeightOverrides(overrides);
}

@Override
public HardSoftBigDecimalScore score(EmployeeSchedule solution) {
    return solution.getScore();
}
```

The `ScoreCodec` turns a level and a multiplier into a score at that level, so a weight of `5` on a
soft constraint becomes `0hard/5soft`. A model with a two-level score refuses `MEDIUM` in its codec.

## Solver configuration and codecs

`baseConfig()` names the solution class, the entity classes and the constraint provider. It sets no
termination, because termination comes from the request:

<!-- include: modules/models/employee-scheduling/src/main/java/satisfactory/models/employeescheduling/EmployeeScheduling.java#config -->
```java
@Override
public SolverConfig baseConfig() {
    return new SolverConfig()
            .withSolutionClass(EmployeeSchedule.class)
            .withEntityClasses(Shift.class)
            .withConstraintProviderClass(EmployeeSchedulingConstraintProvider.class);
}
```

Dataset JSON is Jackson 2. The SPI's `Json` mapper writes `java.time` values as ISO strings and
tolerates unknown fields, and `Json.convert(node, Dto.class)` wraps a mismatch as an `InvalidDataset`
whose message names what was wrong. A model decodes through private record DTOs and encodes with
`Json.tree`. `decodeProblem` keeps any assignments the input carries, which is how pinning works;
`decodeSolution` reads an output as a solution to continue from and remembers each entity's assignment
so the disruption KPI can be computed; `encodeSolution` writes the output schema's JSON, which
`decodeSolution` accepts unchanged:

<!-- include: modules/models/employee-scheduling/src/main/java/satisfactory/models/employeescheduling/EmployeeScheduling.java#codecs -->
```java
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
```

The input and output schemas are draft 2020-12 JSON Schema files on the model's own classpath, loaded
with `JsonSchema.resource(owner, path)`. A submit is checked against the input schema before a dataset
is created, so a shape mistake is a `400` and never reaches a worker.

## Validation, metrics and demo data

`validate` runs on a worker after the schema has passed and reports what a schema cannot see: a shift
naming an employee that does not exist, a duplicate id, a window that ends before it starts. It returns
a `ValidationResult` of typed `Issue` values, each with a code from the model's `IssueType` catalog, a
severity of `ERROR` or `WARNING`, and named fields. A result with an error stops the dataset at
`DATASET_INVALID`.

`inputMetrics` describes the problem (how many employees, shifts, locations) and `kpis` describes a
solution (how many shifts are unassigned, how fair the working time is). `kpis` runs for every recorded
improvement, so it must be cheap and must not include entity bodies. Each metric is declared as a
`MetricInfo` with an id, a title, a description, a type of `integer`, `number` or `string`, a priority
and an example, which is what a client renders without knowing the model.

`demoData` returns `DemoDataset` values, each with an id, descriptions, tags, a map of configuration
hints such as a recommended `spentLimit`, and a supplier of the input. Generate demo data
deterministically from a fixed seed and date, so a test that uses it is reproducible.

## Test a model through ModelRuntime

`ModelRuntime.of(model)` builds the Timefold solver factory and solution manager once and is what both
the API and a worker use. A model's own tests run it directly, with no service. This solves the small
demo dataset for five seconds and checks that the result is feasible:

<!-- include: modules/models/employee-scheduling/src/test/scala/satisfactory/models/employeescheduling/EmployeeSchedulingSuite.scala#solve -->
```scala
test("solving the small demo reaches a feasible schedule within seconds") {
  runtime.startSolving(1)
  try
    val problem = runtime.problem(small, weights())
    val done    = CompletableFuture[Object]()
    val _ = runtime.solve(
      "smoke",
      problem,
      TerminationConfig().withSpentLimit(Duration.ofSeconds(5)),
      _ => (),
      best => done.complete(best): Unit,
      (_, error) => done.completeExceptionally(error): Unit
    )
    val best = done.get(60, TimeUnit.SECONDS)
    val view = runtime.currentScore(best)
    assert(view.feasible(), view.score())
    assertEquals(model.kpis(best.asInstanceOf[EmployeeSchedule]).get("unassignedShifts").asInt(), 0)
  finally runtime.close()
}
```

`runtime.analyze(solution, weights)` is the per-constraint score breakdown. Timefold's own
`SolutionManager.analyze` is Enterprise-only, so the runtime computes each constraint's contribution by
ablation: it scores once with the given weights, then once per constraint with that constraint's weight
at zero, and the difference is what the constraint contributed. The contributions sum exactly to the
total, and the solution's weights are restored before it returns:

<!-- include: modules/models/employee-scheduling/src/test/scala/satisfactory/models/employeescheduling/EmployeeSchedulingSuite.scala#ablation -->
```scala
test("ablation: each constraint's contribution, and together they are the score") {
  val problem  = runtime.problem(small, weights())
  val shifts   = problem.asInstanceOf[EmployeeSchedule].getShifts.asScala
  val emps     = problem.asInstanceOf[EmployeeSchedule].getEmployees.asScala
  shifts.zipWithIndex.foreach((s, i) => s.setEmployee(emps(i % emps.size)))
  val analysis = runtime.analyze(problem, weights("undesiredDayForEmployeeWeight" -> 3L))
  val total    = HardSoftBigDecimalScore.parseScore(analysis.score())
  val sum = analysis.constraints().asScala
    .map(c => HardSoftBigDecimalScore.parseScore(c.score()))
    .foldLeft(HardSoftBigDecimalScore.ZERO)(_.add(_))
  assertEquals(sum, total)
  assertEquals(analysis.constraints().size(), model.constraints().size())
  val undesired = analysis.constraints().asScala.find(_.name() == "Undesired day for employee").get
  assertEquals(undesired.weight(), "0hard/3soft")
  assertEquals(runtime.score(problem).score(), analysis.score(), "the solution's own weights are restored")
}
```

Tests to write for a new model, in the order the repository's models have them: demo data is
deterministic and valid against the model's own schema; an input decodes and encodes back to itself; a
solution's assignments survive the round trip; validation reports each issue type; a weight scales its
constraint and zero disables it; ablation sums to the score; and solving the small demo reaches a
feasible solution within seconds. Run them with `sbt <model>/test`, in seconds, with nothing running.

## Register it

Add the model to the catalog in the solver service, and to the API's, which uses the same catalog to
mount the model's routes and to validate submits:

```scala
def catalog: ModelCatalog = ModelCatalog.of(EmployeeScheduling.V1, VehicleRouting.V1)
```

A model is compiled into the solver image. Uploading a model as a jar is not supported; see
[Limitations](../reference/limitations.md).
