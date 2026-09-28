---
name: satisfactory-models
description: Write or change a satisfactory model in Java against the SolverModel interface — the model key and catalog information, input and output JSON schemas, ConstraintInfo with weight fields and levels, the ScoreCodec, Timefold SolverConfig, Jackson codecs for problem and solution, validation issue types, KPI and input metric descriptors, demo data, ConstraintWeightOverrides, and testing through ModelRuntime including ablation analysis. Use when the task adds a model, a constraint, a KPI, a validation rule, or changes a dataset's shape.
---

# satisfactory models

A model is a Java class implementing `satisfactory.spi.SolverModel<S, Score_>`, where `S` is the
`@PlanningSolution` class and `Score_` its Timefold score type. It sees the interface and nothing else:
no ankka, no HTTP, no Scala. Registration is explicit in `ModelCatalog.of(...)`; there is no scanning.

## Rules

1. **The key is the API.** `new ModelKey("employee-scheduling", "v1", "schedules")` is the path
   `/api/models/employee-scheduling/v1` and the entity `schedules`. A breaking change to the input or
   output is a new version, never a change to `v1`.
2. **Constraints are declared once, in the provider's order.** Each `ConstraintInfo(name, key,
   description, level, defaultWeight)`: `name` must equal the provider's `asConstraint(name)`, because
   weight overrides are keyed by it; `key + "Weight"` is the request's field. `overridesSchema()` is
   derived from the list, so a constraint missing from it cannot be weighted.
3. **Weights reach Timefold through a `ConstraintWeightOverrides` field on the solution class**, which
   Timefold finds by type. `setWeights` stores what the runtime builds; `score` reads the score back.
   The `ScoreCodec` turns `(level, weight)` into a score at that level and must refuse a level the
   score type lacks.
4. **Schemas are draft 2020-12 files on the model's classpath**, loaded with
   `JsonSchema.resource(Owner.class, path)`. The output must be accepted unchanged as input by
   `decodeSolution`, because that is how a solved dataset is continued or patched.
5. **Codecs are Jackson through the SPI's `Json`.** Decode with `Json.convert(node, Dto.class)` into
   private records, encode with `Json.tree(dto)`. `decodeProblem` keeps assignments in the input
   (pinning); `decodeSolution` also remembers each entity's assignment so `kpis` can report
   `disruptionPercentage`.
6. **`validate` reports what the schema cannot.** Typed `Issue`s with codes from
   `validationIssueTypes()`, severity `ERROR` or `WARNING`. An error stops the dataset at
   `DATASET_INVALID`.
7. **`kpis` is cheap and body-free.** It runs on every recorded improvement. Declare every KPI and
   input metric as a `MetricInfo` with a type of `integer`, `number` or `string`.
8. **`baseConfig()` sets no termination.** Solution class, entity classes, constraint provider only.
   Termination comes from the request.
9. **Demo data is deterministic.** A fixed seed and date, with a recommended `spentLimit` in the
   config map, so tests are reproducible.
10. **Test through `ModelRuntime.of(model)`** with nothing running: demo data valid against the schema,
    codec round trips, assignments surviving the round trip, each validation issue, a weight scaling
    and zero disabling, ablation summing to the score, and the small demo solving feasibly in seconds.
    `runtime.analyze` is the ablation; `SolutionManager.analyze` is Enterprise-only and not used.

## Before writing

- What is the planning entity, the planning variable, and the value range? Timefold's quickstarts are
  the pattern; the two models in the repository are ports of them.
- Which fields of the input are facts, which are assignments to keep, and which is the pin?
- Which constraints are hard, and is a medium level needed (vehicle routing uses one for unassigned
  visits)?
- What does a user need to see about a solution? Those are the KPIs.

## Mistakes to check for

- A constraint name in `constraints()` that differs from the provider's `asConstraint` name.
- A `ScoreCodec.weight` that accepts a level the score type does not have.
- An output the model's own `decodeSolution` refuses.
- `kpis` that walks entity bodies into the JSON.
- Termination in `baseConfig()`, or a solution class without the `ConstraintWeightOverrides` field.

## Reference files

Open the one a task needs; each is one topic and stands alone.

### Concepts

- `references/concepts/models-and-datasets.md` — Why a request names a model and carries a dataset, what a model is made of, what a dataset is, and what a request may and may not influence.

### Build

- `references/build/models.md` — Add a planning problem type to the catalog by implementing the Java SolverModel interface — schemas, constraints, codecs, validation, metrics and demo data — and test it through ModelRuntime with nothing else running.

### Reference

- `references/reference/dataset-lifecycle.md` — The ten statuses a dataset moves through, in which order, which are final, and what a terminate, a failure, a lost lease and a supersession do to them.
- `references/reference/employee-scheduling.md` — The employee-scheduling model, version v1 — its input and output, constraints and weight fields, KPIs, validation issues, patch paths and demo datasets.
- `references/reference/vehicle-routing.md` — The vehicle-routing model, version v1 — its input and output, constraints and weight fields, KPIs, validation issues, patch paths and demo datasets.
- `references/reference/limitations.md` — What satisfactory does not do yet, stated plainly, so a reader who hits one of them knows it is the system and not their code.
