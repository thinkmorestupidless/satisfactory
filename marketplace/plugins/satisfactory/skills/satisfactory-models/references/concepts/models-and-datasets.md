# Models and datasets

> Why a request names a model and carries a dataset, what a model is made of, what a dataset is, and what a request may and may not influence.

Source: https://satisfactory.ankka.cloud/concepts/models-and-datasets/
A Timefold planning problem is not data; it is code plus data. Timefold solves an instance of a
`@PlanningSolution` class graph scored by a `ConstraintProvider`, both compiled Java, and there is no
generic wire format for a constraint satisfaction problem. So a request cannot carry the whole problem.
It carries a reference to a **model**, which is the code, and a **dataset**, which is the instance,
plus configuration: how long to solve and how to weigh the constraints.

## A model

A model is a planning problem type with its own API under `/api/models/{model}/{version}`, its own
input and output schemas, and its own constraints. The two in the catalog are
[employee scheduling](../reference/employee-scheduling.md), which staffs shifts, and
[vehicle routing](../reference/vehicle-routing.md), which plans deliveries. Each describes itself at
`GET /api/models/{model}/{version}/model`: name, maturity, features, schemas, constraints with default
weights, KPIs, validation issue types and demo datasets.

A model's version is in its path, and a `STABLE` model's API is backwards compatible within it; a
breaking change is a new version. Models are registered explicitly and compiled into the solver image.
[Write a model](../build/models.md) is the interface a model implements.

## A dataset

A dataset is one submitted planning problem and everything that happens to it: the input, its
validation, its solving, and its output. It has an id, a status, a score, five timestamps marking the
phase boundaries, a name and tags, and it is owned by the tenant whose key submitted it. Its input
never changes after submission. The best solution so far is readable at any time with `GET /{id}`, and
the final one is the same call after the status is final.

A dataset also has a place in a lineage: a `parentId` if it was derived from another, and an `originId`
naming the root of its chain. [Lineage instead of mutation](lineage.md) is why.

## What a request may influence

Two things: **termination** and **constraint weights**. Termination says how long to solve, by time
spent, by time without improvement, by step or move count, or by diminishing returns. Weights say how
much each constraint matters, one integer per constraint, where zero disables it and the model's default
is one.

What a request may not influence: solver phases, move selectors, acceptors, the constraint provider.
Those belong to the model, because they are what makes a model good and they are not safely tunable by
someone who has not read its code. Exposing them would also freeze the model's internals into a public
API.

A tenant can name a bundle of termination and weights as a
[configuration profile](../build/configuration-profiles.md) and refer to it by id.

## Validation is two-stage

A submit is checked against the model's input schema before a dataset is created; a shape mistake is a
`400` naming every violation. Model-level validation runs on a worker after that and reports what a
schema cannot see: a shift naming an employee that does not exist, a visit routed twice, a window that
ends before it starts. Each issue has a code from the model's catalog and a severity. An error stops the
dataset at `DATASET_INVALID`; a warning is recorded and solving continues.

## Scoring without solving

A dataset submitted with `operation=NONE` is validated and scored but not solved, and stops at
`DATASET_COMPUTED`. `POST /{id}` solves it later. "How good is the plan we already have?" is therefore a
first-class question, and the gain from solving is measurable. The stateless `POST /{entity}/score-analysis`
answers the same question for a plan the caller holds, creating nothing.

## Not the source of truth

Datasets have a retention period, thirty days by default, and the service outputs assignments only.
Keep your business data in your own system, submit what the model needs, and read the assignments back.
