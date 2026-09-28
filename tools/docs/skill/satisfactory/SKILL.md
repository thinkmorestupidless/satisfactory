---
name: satisfactory
description: What satisfactory is and how it behaves — constraint solving as a service built on Timefold Solver and deployed as ankka services; models and datasets, the ten dataset statuses and which are final, lineage instead of mutation (from-input, from-patch, supersede), the three ways to receive a result, tenants and API keys, workers and leases, and every reference page. Use for any question about satisfactory that is not specifically writing client code, an ankka integration, or a model; load it first when unsure which skill applies.
pages:
  - index.md
  - concepts/architecture.md
  - concepts/models-and-datasets.md
  - concepts/lineage.md
  - concepts/receiving-results.md
  - concepts/workers-and-leases.md
  - concepts/tenancy.md
  - get-started/run-locally.md
  - get-started/first-solve.md
  - get-started/coding-agents.md
  - deploy/deploy-on-ankka.md
  - deploy/operate.md
  - reference/model-api.md
  - reference/platform-api.md
  - reference/dataset-lifecycle.md
  - reference/webhook-events.md
  - reference/configuration.md
  - reference/employee-scheduling.md
  - reference/vehicle-routing.md
  - reference/divergences.md
  - reference/limitations.md
  - reference/glossary.md
---

# satisfactory

satisfactory is constraint solving as a service: a request names a model (a planning problem type,
compiled Java) and carries a dataset (the instance plus how long to solve and how to weigh constraints).
The API is a reimplementation of the Timefold Platform's model API. It runs as two ankka services:
`api`, which is exposed and holds every dataset as an event sourced entity, and `solver`, a pool of
workers that pull datasets and solve them with Timefold Solver Community.

## Rules

1. **Datasets are immutable, with lineage.** Never look for a way to edit a dataset in place. A changed
   problem is `POST /{id}/from-input` or `POST /{id}/from-patch`, a new dataset with `parentId`.
   Deriving from a dataset that is still solving supersedes it: the parent completes with its best
   solution and names the child in `supersededBy`.
2. **A submit answers 202 at once; the solution arrives later.** Results arrive by webhook (production),
   server-sent events (development) or polling `/metadata`. None of the three carries a solution; the
   solution is `GET /{id}`, which returns the best so far at any time.
3. **Ten statuses, five final.** `SOLVING_COMPLETED`, `SOLVING_INCOMPLETE`, `SOLVING_FAILED`,
   `DATASET_INVALID`, and `DATASET_COMPUTED` when `operation=NONE`. A terminate (`DELETE /{id}`) is a
   success that keeps the best solution. Validation happens on a worker, so `SOLVING_SCHEDULED` comes
   before `DATASET_VALIDATED`.
4. **Two APIs, two credentials.** The model API takes `X-API-KEY`; the platform API takes a bearer token
   from the identity provider and is `503` until one is configured. A dataset of another tenant is a
   `404`. Keys and tokens do not cross.
5. **A request may set termination and weights, nothing else.** `config.run.termination` and
   `config.model.overrides` (`<key>Weight`, integer, 0 disables). `maxThreadCount` is capped at 1.
   A configuration profile names a bundle of these; `standard` is the model's defaults.
6. **Every event stream frame and webhook event carries `seq`.** `?after=<seq>` resumes a stream
   exactly; a webhook receiver dedupes on the event id. Delivery is at-least-once.
7. **The lease epoch is a fencing token.** A worker command from a superseded epoch is refused with
   `409`. Only a strictly better score is recorded. No event carries a solution body.
8. **Score analysis is by ablation.** `{ score, constraints: [{ name, weight, score }] }`; no match
   counts or justifications, because those are Enterprise-only in Timefold Solver.

## Before answering

- Which API is the question about: the model API (`/api/models/{model}/{version}`) or the platform
  API (`/api/platform/v1`)? The credential and the reference page differ.
- Which model? `employee-scheduling` submits `schedules`; `vehicle-routing` submits `route-plans`.
  Each has its own constraints, weight fields, KPIs and issue codes on its reference page.
- Is the question about running it (`get-started/run-locally.md`, `deploy/`) or calling it
  (`reference/model-api.md`)?
- Does a limitation apply? Check `reference/limitations.md` before promising a feature.

## Mistakes to check for

- Describing a mutable job, a continuous mode, or an in-place problem change. None exist.
- Expecting the solution in a webhook or a stream frame.
- Using `X-API-KEY` on the platform API, or a bearer token on the model API.
- Assuming multi-threaded solving, justifications, or an uploadable model jar.
- Forgetting `SAT_PUBLIC_URL` and `SAT_METRICS_TOKEN` in a deployment.
