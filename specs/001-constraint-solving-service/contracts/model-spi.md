# Contract: Model SPI (`model-spi`, Java 21)

> **As built (2026-09-28)** — `SolverModel<S, Score_ extends Score<Score_>>` in
> `modules/model-spi/src/main/java/satisfactory/spi/SolverModel.java`. Differences from the sketch below:
> weights are applied through `setWeights(S, ConstraintWeightOverrides<Score_>)` (a field on the
> solution), and `ModelRuntime` does the rest once per model — `problem`/`solution` (decode and apply
> weights), `score` (`SolutionManager.update`), `analyze` (ablation), `solve`/`terminateEarly` on a
> worker. There is no `decodeChange`: supersede always tears down and warm-starts (V7 passed for both
> models). Dataset JSON is Jackson 2 (`com.fasterxml.jackson.databind.JsonNode`).

The only thing a model sees. Depends on `timefold-solver-core` 2.7.0 and Jackson; no ankka, no HTTP, no Scala. Registration is explicit: `ModelCatalog.of(EmployeeScheduling.V1, VehicleRouting.V1)` in both `api` and `solver`.

```java
package satisfactory.spi;

public interface SolverModel<S> {
  ModelKey key();                                   // registrationKey "employee-scheduling", version "v1", entity "schedules"
  Maturity maturity();                              // TEMPLATE | EXPERIMENTAL | PREVIEW | STABLE | DEPRECATED
  ModelInfo info();                                 // name, description, features, maxThreadCount (1), build info

  JsonSchema inputSchema();                         // draft 2020-12, from a classpath resource
  JsonSchema outputSchema();
  JsonSchema overridesSchema();                     // derived: one "<constraintName>Weight": integer ≥ 0 per constraint
  List<PatchPath> patchPaths();                     // arrays that accept [field=value] selection and "-" append

  List<ConstraintInfo> constraints();               // name, description, level HARD|MEDIUM|SOFT, defaultWeight
  List<MetricInfo> kpiDescriptors();                // id, title, description, type, priority, example
  List<MetricInfo> inputMetricDescriptors();
  List<IssueType> validationIssueTypes();           // code, severity, message template, field schema
  List<DemoDataset> demoData();                     // id, short/long description, tags, config entries, supplier of input

  SolverConfig baseConfig();                        // solution + entity classes, constraint provider, phases; termination left to overrides

  S        decodeProblem(JsonNode modelInput, WeightOverrides w) throws InvalidDataset;   // schema-valid input → planning problem
  ValidationResult validate(S problem);             // model-level issues (typed)
  JsonNode inputMetrics(S problem);
  JsonNode encodeSolution(S solution);              // modelOutput; also the warm-start and select=SOLVED format
  S        decodeSolution(JsonNode modelOutput, WeightOverrides w) throws InvalidDataset;  // pinned assignments preserved
  JsonNode summarize(S solution);                   // the KPIs (must include disruptionPercentage when derived)
  ProblemChange<S> decodeChange(JsonNode patchedInput, S current) throws InvalidChange;    // optional; default: unsupported → teardown path

  Score<?> score(S problem);                        // SolutionManager.update — Community
  default ScoreAnalysisView analyze(S problem, WeightOverrides w) { return Ablation.analyze(this, problem, w); }  // research R2
  ScoreCodec scoreCodec();                          // parse/format the model's score type; comparator; ScoreKey
}
```

Supporting types: `WeightOverrides` (map constraint name → `long`; `toConstraintWeightOverrides(ScoreCodec)` maps each to a `Score` at the constraint's level), `ValidationResult { status, issues: List<Issue { code, severity, fields }> }`, `ScoreAnalysisView { score, constraints: List<{ name, weight, score }> }`, `ScoreKey` (a `List<BigDecimal>` for level-wise comparison), `JsonSchema` (wraps a `JsonNode`; `validate(JsonNode): List<String>` via `com.networknt:json-schema-validator`).

Rules for a model:

- Everything a request can influence is termination and weights; phases, move selectors and the constraint provider are the model's alone.
- `encodeSolution` output must be accepted by `decodeSolution` unchanged, with assignments pinned, so `from-input select=SOLVED` warm-starts and `from-patch select=SOLVED` minimises disruption.
- `summarize` must be cheap (it runs per recorded improvement) and must not include entity bodies.
- Demo datasets are generated deterministically from a seed so tests are reproducible.
- The `models/*` modules are Java; tests are JUnit 5 with `timefold-solver-test`'s `ConstraintVerifier`.

Initial catalog: `employee-scheduling/v1` (`HardSoftBigDecimalScore`, basic variable, 62 constraints ported from the quickstart's provider, issue types `ShiftUnknownEmployee`, `ShiftOverlapsUnavailability`, `EmptySchedule`, …) and `vehicle-routing/v1` (`HardSoftLongScore`, list variable, constraints `vehicleCapacity`, `serviceFinishedAfterMaxEndTime`, `minimizeTravelTime`; KPIs `totalDrivingTimeSeconds`, `unassignedVisits`, `disruptionPercentage`).
