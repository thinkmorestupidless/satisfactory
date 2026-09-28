# satisfactory — API definition

The public API is a reimplementation of the **Timefold Platform** model API, the same way ankka is a
reimplementation of Akka's component model: adopt the shape and vocabulary wholesale, diverge only
with a reason, and write the divergences down. Timefold have run this as a product; their choices
encode operational experience we do not have.

Written 2026-09-22 from the public docs and the two OpenAPI specs saved under
[`notes/timefold/`](notes/timefold/): the Employee Shift Scheduling model API (`v1`, OpenAPI 3.0.3,
532 schemas) and the Platform API (`1.12.3`). Field names below are theirs unless a divergence says
otherwise. Supersedes `DESIGN.md` §3.1, §3.2 and §4.

> **2026-09-28 — as built** (specs/001-constraint-solving-service). Where the implementation settled
> something this document left open or had to change it:
>
> - **Score analysis is Enterprise-only in Timefold Solver 2.7.** `score-analysis` answers the
>   Community shape: `{ score, constraints: [{ name, weight, score }] }`, each constraint's
>   contribution computed by ablation; no `matchCount` or `matches`, and `includeJustifications=true`
>   is answered with `"justifications": "unsupported"` (§2.3).
> - **Webhook signing** covers `<timestamp>.<payload>`, so a replayed request cannot carry a fresh
>   timestamp (§2.4, §2.7). A subscription hears only events that happen after it was made.
> - **SSE frames** are ankka's: `data:` lines whose payload is a JSON-quoted string holding
>   `{ seq, metadata, skipped? }` or `{ heartbeat: true }` (§2.4, §8). `satisfactory-client` unwraps it.
> - **Configuration profiles** are `/api/platform/v1/tenants/{t}/configurations[/{id}]` with the model
>   in the body or `?model=`; operator actions are under `/api/platform/v1/ops` (§3).
> - **Errors** from the API are `ErrorInfo`; refusals ankka makes itself (an ACL's 401/403, an unknown
>   route) are ankka's `{ status, error }`.

---

## 1. Vocabulary

| Term | Meaning | Was (DESIGN.md) |
|---|---|---|
| **Model** | A planning problem type with its own API, schema and constraints, versioned in the path (`/v1/`) | model |
| **Dataset** | One submitted planning problem and everything that happens to it: input, validation, solving, output. Timefold renamed "run" → "dataset" platform-wide; the deprecated `/run` endpoints are still in the spec | job |
| **Configuration profile** | A named, tenant-owned bundle of run configuration + model configuration (weights) + resources; up to 50 per model; a read-only *Standard* profile is the default; referenced by id **or name** | (profiles, vaguely) |
| **Tenant** | Isolated workspace: members, API keys, models, webhooks, datasets, audit log | tenant |
| **API key** | Tenant-scoped credential for the model APIs, header `X-API-KEY`, role-based | `X-Api-Key` |
| **Personal access token** | User-scoped `Authorization: Bearer` for the *Platform* API | — |

A dataset is **immutable input plus lineage**: changing the problem creates a *new* dataset with a
`parentId` (and an `originId`, the root of the chain). There is no live mutable job.

## 2. Model API

Base: `https://api-satisfactory.<base domain>/api/models/{model}` (Timefold's server path is
`/api/models/employee-scheduling`). Each model chooses its entity name — `schedules` for
scheduling, `route-plans` for routing. Every operation answers `401` (missing/invalid key), `403`
(key lacks the operation), `429` (rate limit / policy), `404`, `500` with an `ErrorInfo`
`{ id, code, message, details }`.

```
GET    /v1/demo-data                               [{ id, shortDescription, longDescription, tags, config: [{key, value}] }]
GET    /v1/demo-data/{demoDataId}                  a full request (modelInput + config)
GET    /v1/demo-data/{demoDataId}/input            the modelInput alone

POST   /v1/{entity}                                submit                      → 202 Metadata
       ?name= &operation=SOLVE|NONE &configurationId= &priority=0..10 &tags=…
POST   /v1/{entity}/{id}?priority=                 solve an existing dataset (after operation=NONE)   → 202 Metadata
GET    /v1/{entity}/{id}                           { metadata, modelOutput, inputMetrics, kpis } — the best so far, solver may still be running
GET    /v1/{entity}/{id}/metadata                  Metadata — the thing to poll
PATCH  /v1/{entity}/{id}/metadata                  { name, tags }               400 if the state does not allow it
GET    /v1/{entity}/{id}/input                     the modelInput as submitted
GET    /v1/{entity}/{id}/model-request             the whole request: modelInput + config as submitted
GET    /v1/{entity}/{id}/config                    the *resolved* configuration used (profile + overrides)
GET    /v1/{entity}/{id}/validation-result         { status, issues[] }
GET    /v1/{entity}/{id}/score-analysis?includeJustifications=
GET    /v1/{entity}/{id}/logs                      { details }
GET    /v1/{entity}/{id}/events?status=…           SSE of Metadata until a final state; 410 if already final
DELETE /v1/{entity}/{id}?force=                    TERMINATE — stops solving and returns the dataset (best so far)
DELETE /v1/{entity}/{id}/purge                     delete from storage; 400 while solving
PUT    /v1/{entity}/{id}                           restore a purged dataset "if still available"  → 204
POST   /v1/{entity}/{id}/from-input                new dataset from this one's input; select=UNSOLVED (default) | SOLVED
       ?name= &operation= &select= &configurationId= &priority= &tags=       body: config (optional)
POST   /v1/{entity}/{id}/from-patch                new dataset from a patch; select=SOLVED (default) | UNSOLVED   (preview)
       same query;  body: { patch: [{ op, path, value? }], config? }

POST   /v1/{entity}/score-analysis                 score a supplied plan, no dataset created      (stateless)
GET    /v1/{entity}/validation-issue-types         the model's issue catalog: [{ code, severity, metadata[] }]
GET    /v1/{entity}/validation-issue-types/{code}
GET    /v1/{entity}?page=&size=                    list (deprecated in Timefold's spec; kept by us — §8)

GET    /api/aboutme                                who am I, which tenant, which roles
```

Model-specific extras Timefold ships on this model and which our SPI should allow a model to add:
`POST …/recommendations/recommend-employees{,-batch-global,-batch-per-shift}` (rank candidates for
a shift — a solve-free query over the constraints) and `GET …/{id}/assignability-analysis`
(preview: why shifts are unassigned). A model may contribute routes; the platform does not know
what they mean.

### 2.1 Submit

```jsonc
POST /v1/schedules?operation=SOLVE&configurationId=standard&priority=5&tags=site:leeds&tags=weekly&name=week-39
// X-API-KEY: …    Content-Encoding: gzip (optional; 100 MB compressed / 2 GB uncompressed)
{
  "modelInput": { /* the model's input schema — required */ },
  "config": {                                   // ModelConfiguration; everything optional
    "run": {                                    // RunConfiguration
      "name": "week-39",                        // also accepted here; ≤ 255
      "tags": ["site:leeds"],                   // also accepted here; ≤ 100, unique
      "maxThreadCount": 1,                      // ≥ 1, capped by the model / plan
      "termination": {                          // SolverTerminationConfig — flat
        "spentLimit":              "PT5M",      // ISO-8601; max solve duration
        "unimprovedSpentLimit":    "PT30S",     // stop if no improvement for this long
        "slidingWindowDuration":   "PT30S",     // "diminished returns" termination: window (default PT30S) …
        "minimumImprovementRatio": 0.0001,      // … and ratio (default 0.0001); the platform default when nothing is set
        "stepCountLimit":          null,        // int32 ≥ 0 — hardware-independent
        "moveCountLimit":          null         // int64 ≥ 0 — hardware-independent
      }
    },
    "model": {                                  // ModelConfig<Model>ConfigOverrides
      "overrides": {                            // one int64 ≥ 0 property PER CONSTRAINT, named by the model:
        "balanceShiftCountWeight": 1,           //   62 of them on employee scheduling; default 1; 0 disables
        "employeeWorksDuringUnpreferredTimeWeight": 5
      }
    },
    "resourcesConfiguration": { "memory": 1024, "labels": {} },   // readOnly on submit — comes from the profile
    "mapsConfiguration": { }                                      // readOnly; routing models only
  }
}
→ 202 Metadata   (§2.3)
   400 ErrorInfo | ValidationErrorInfo { id, code, message, details: [string] }   — JSON/schema problems
```

- **Weights are typed, not a map.** `config.model.overrides` is a model-specific object whose
  properties are `<constraintName>Weight: int64 ≥ 0`. That means the constraint list *is part of the
  model's OpenAPI schema* — a client generated from the spec gets a field per constraint. Our SPI
  therefore emits the overrides schema from the model's constraint list; it is not free-form.
- `operation=NONE` creates, validates and **scores** the dataset without solving
  (`DATASET_COMPUTED`); `POST /{id}` solves it later. "How good is the plan we already have" is a
  first-class call, and optimisation gain is measurable.
- `priority` 0–10, default 5 — a queue-ordering hint within the tenant.
- `name` and `tags` travel as query parameters *and* in `config.run`; `PATCH /metadata` edits them
  afterwards.
- **Configuration priority**, highest first: the submission's own `config`; the profile named by
  `configurationId`; the parent dataset's config (`from-*`); model defaults; plan/model maximums,
  enforced last. `GET /{id}/config` returns the resolved result.
- **Validation is two-stage**: JSON Schema at the `POST` → `400`; model-level validation on the
  worker → `DATASET_VALIDATED` or `DATASET_INVALID`, detail at `/validation-result`.

### 2.2 Status

Ten statuses, adopted exactly (`SolvingStatus`):

| `solverStatus` | Meaning (Timefold's words) |
|---|---|
| `DATASET_CREATED` | A new dataset has been created from a new input dataset or a patch to an existing dataset |
| `DATASET_VALIDATED` | The dataset has been validated |
| `DATASET_INVALID` | The submitted dataset was invalid |
| `DATASET_COMPUTED` | The score analysis and KPIs for the dataset have been computed before solving |
| `SOLVING_SCHEDULED` | The data is in the queue to be solved |
| `SOLVING_STARTED` | The input dataset is being converted into the planning problem and augmented with additional information |
| `SOLVING_ACTIVE` | The planning problem is currently being solved |
| `SOLVING_INCOMPLETE` | A solution was not found before the run was terminated |
| `SOLVING_COMPLETED` | Solving has completed and no further solution will be generated |
| `SOLVING_FAILED` | An error has occurred and solving was unsuccessful |

```
operation=SOLVE:  CREATED → SOLVING_SCHEDULED → VALIDATED → COMPUTED → SOLVING_STARTED → SOLVING_ACTIVE → SOLVING_COMPLETED
operation=NONE:   CREATED → VALIDATED → COMPUTED                 (then POST /{id} to solve)
terminate:        any active state → SOLVING_COMPLETED (a solution exists) | SOLVING_INCOMPLETE (none)
failure:          any stage → SOLVING_FAILED, with metadata.failureMessage; the best solution so far stays readable
```

Final states — the ones `/events` closes on and webhooks fire on: `DATASET_COMPUTED` (for
`operation=NONE`), `DATASET_INVALID`, `SOLVING_COMPLETED`, `SOLVING_INCOMPLETE`, `SOLVING_FAILED`.

### 2.3 Metadata

```jsonc
// Metadata — returned by POST (202), /metadata, every /events frame, and inside GET /{id}
{ "id": "…", "parentId": null, "originId": null, "name": "week-39", "tags": ["site:leeds"],
  "solverStatus": "SOLVING_ACTIVE",
  "score": "0hard/0medium/-3603soft",             // string; null until scored
  "submitDateTime":   "…",                         // submitted
  "startDateTime":    "…",                         // "the run begins initializing"   (SOLVING_STARTED)
  "activeDateTime":   "…",                         // "the solving phase begins"      (SOLVING_ACTIVE)
  "completeDateTime": null,                        // "the solving phase concludes"
  "shutdownDateTime": null,                        // "the post-processing phase finishes"
  "validationResult": { "summary": "OK", "errors": [], "warnings": [] },   // legacy summary; detail at /validation-result
  "failureMessage": null }                         // set when SOLVING_FAILED
```

Five timestamps, one per phase boundary, which is how "how long did it queue / initialise / solve /
post-process" is answered without a separate metrics endpoint.

```jsonc
// GET /{id}
{ "metadata": Metadata,
  "modelOutput": { /* the model's output schema — the best solution so far */ },
  "inputMetrics": { "employees": 10, "shifts": 120, "pinnedShifts": 4, … },     // computed from the input
  "kpis":         { "assignedShifts": 118, "unassignedShifts": 2, "workingTimeFairnessPercentage": 95.98, "disruptionPercentage": 11.01, … } }
```

**KPIs are schema properties with extensions.** Each `kpis`/`inputMetrics` field carries `title`,
`description`, `x-tf-priority` and `x-tf-example` in the OpenAPI schema. That is where the Platform
API's `outputMetrics` descriptors come from: the model declares KPIs *as typed schema fields*, and
the platform renders them from the schema. Our SPI does the same — a KPI is a declared, typed,
documented field, not a free-form JSON summary.

```jsonc
// GET /{id}/validation-result
{ "status": "ERRORS",                              // VALIDATION_NOT_SUPPORTED | OK | WARNINGS | ERRORS
  "issues": [ { "code": "ShiftUnknownEmployee", "severity": "ERROR", "shift": "s-17", "employee": "e-9" } ] }
// GET /validation-issue-types
{ "issueTypes": [ { "code": { "value": "ShiftUnknownEmployee" }, "severity": "ERROR", "metadata": [ { "type": "message", "message": "…" } ] } ] }
```

Issues are **typed per code** — the spec has one schema per issue type (`ShiftUnknownEmployeeIssue`
…), each with its own entity fields, and the model publishes its catalog of codes. The SPI supplies
both: the issue types and their schemas.

```jsonc
// GET /{id}/score-analysis?includeJustifications=true      (and POST /score-analysis for a supplied plan)
{ "score": "0hard/0medium/-3603soft",
  "constraints": [ { "name": "Employee works during unpreferred time", "weight": "0hard/0medium/-5soft",
                     "score": "0hard/0medium/-120soft", "matchCount": 24,
                     "matches": [ { "score": "0hard/0medium/-5soft", "justification": { "description": "…" } } ] } ] }
```

That is `SolutionManager.analyze` on the wire, justifications included when asked. The stateless
`POST /score-analysis` scores a plan the caller already has, against a profile, creating nothing.

**As built (2026-09-28):** `SolutionManager.analyze` is Enterprise-only in Timefold 2.7, so the
Community build answers `{ score, constraints: [{ name, weight, score }] }` — each constraint's
contribution by ablation (score with its weight at zero; the difference), which sums exactly to the
total — and no matches or justifications (research R2).

### 2.4 Receiving results

Adopted verbatim, including the recommendations:

| | Webhooks | SSE | Polling |
|---|---|---|---|
| Recommended for production | **yes** | no | no |
| Recommended for development | no | **yes** | no |
| Needs a public endpoint on your side | yes | no | no |
| Holds an open connection | no | yes | no |
| Real-time progress updates | no | **yes** | no |
| Works self-hosted / air-gapped | yes (internal network) | yes | yes |

**SSE** — `GET /{id}/events?status=SOLVING_ACTIVE&status=…`: `text/event-stream` whose frames are
**`Metadata` objects** — status and score, not solutions — "for every solution event from the
solver", throttled to at most one per second, optionally filtered to the statuses named, until a
final state; `410 Gone` if the dataset is already final. The solution body is fetched with
`GET /{id}` when the score says it is worth fetching. Not recommended for production because it
holds a connection for the whole solve.

**Webhooks** — configured per tenant, with filters on status, dataset-name regex and tags.

```jsonc
POST <your url>                                   // X-Satisfactory-Signature, X-Satisfactory-Timestamp
{ "id": "…", "name": "week-39", "model": "employee-scheduling", "modelVersion": "v1",
  "tags": [], "status": "SOLVING_COMPLETED",
  "runLink": "https://…/v1/schedules/{id}", "outputLink": "https://…/v1/schedules/{id}" }
```

- Payload is **metadata plus links**, never the solution — the receiver fetches with its own key.
- Signing: HMAC-SHA256, base64, over the body *or* the request path (configurable), with an ISO-8601
  timestamp header the receiver checks for staleness. Custom headers may inject `{hmac_signature}`
  and `{hmac_timestamp}`.
- Delivery: expects `200`; 5 s overall timeout; up to 10 retries, 10 s apart; a **read timeout is
  not retried**, "to avoid delivering the same webhook event more than once"; tenant admins are
  emailed on persistent failure, at most once per two hours; activity log kept 30 days with a
  per-entry retry.
- Their advice to receivers: "receive the event, put it on an internal queue, return HTTP 200
  immediately, and process the result asynchronously".

**Polling** — `/metadata`, watch `solverStatus` for a final state; "start with a short interval and
increase it for longer-running solves".

### 2.5 Changing the problem: lineage, not mutation

Timefold's guidance, adopted: *"Don't use Timefold as the source of truth for your business data."*
Datasets have a limited retention period; the platform outputs assignments only.

Three ways to derive a new dataset, all with `parentId` → the source and `originId` → the root:

- **`from-input`** — the parent's *input* (`select=UNSOLVED`, default) or its *output* re-submitted
  as input (`select=SOLVED`), with an optional new `config`. Re-solve with different weights, or
  continue from where a previous solve got to.
- **`from-patch`** (Timefold: preview, feature-flagged) — `[{ op: add|remove|replace, path, value? }]`
  where `path` is a dataset path that may select array items by `[field=value]` or index:
  `/employees/[id=Lee]/tags`, `/shifts/-`. `select=SOLVED` (default) patches the *solved* dataset,
  keeping assignments so the solver minimises disruption (`disruptionPercentage` is a KPI for
  exactly this); `select=UNSOLVED` patches the input for a fresh plan. Validation runs twice: schema
  before creation, model after.
- **Resubmit** — terminate (`DELETE /{id}`) and `POST` afresh. For short horizons, infrequent or
  large changes, and when previous assignments need not survive.

### 2.6 Changes while solving

Timefold's spec does not say whether `from-input`/`from-patch` accept a parent that is still
`SOLVING_ACTIVE` — `select=SOLVED` reads "the best so far" in `GET /{id}`'s own words, so it may
well work — and their full-resubmit guidance says "after cancelling". The engine can change a
running solve (`SolverManager.addProblemChange`); the platform does not expose that.

We keep their contract and are explicit underneath:

- **`from-*` on an active parent means *supersede*.** The parent ends `SOLVING_COMPLETED` with its
  best so far and `metadata.supersededBy = <childId>`; the child warm-starts from that best (with
  the patch applied). Lineage is unchanged; the caller need not terminate first.
- **In-place `ProblemChange` is an optimisation, not a feature.** A runner still holding the parent
  may apply the patch to the live solver and continue as the child rather than tearing down and
  warm-starting. Same statuses, same API.
- **`/events?follow=lineage`**: on supersession the stream emits one `Metadata` frame for the
  parent (final) and continues with the child's frames instead of closing with `410`.

### 2.7 Webhook event model (decided 2026-09-22)

Webhooks are load-bearing, not optional: Timefold names them the production delivery method, and
the ankka agent integration (DESIGN.md §12) resumes a workflow from one. Timefold's are deliberately
narrow — dataset final statuses, UI-configured (the Platform API spec has no webhook endpoints),
metadata and links only. We keep that scope for v1 and widen the *shape*:

- **Event-typed subscriptions**: `{ url, events: ["dataset.*"], filters: { status, nameRegex,
  tags, model }, signing: { method: "hmac-sha256", over: "body" | "path" }, headers }`.
  v1 emits `dataset.computed | invalid | completed | incomplete | failed`. `model.*` and `tenant.*`
  are reserved namespaces, added when a *system* consumer exists.
- **Envelope**: `{ id, type, occurredAt, tenant, data: { …Timefold's payload…, seq } }`. The event
  id and per-dataset `seq` let a receiver dedupe — which is why we *do* retry the ambiguous
  read-timeout case Timefold declines to, and remain at-least-once end to end.
- **API-managed**, not UI-only: `POST /api/platform/v1/webhooks`, with a delivery log (30 days,
  per-entry retry) beside it.
- **Payload rule unchanged**: never a solution.
- Candidate, not included: `dataset.solution.feasible` on the first feasible solution.

On ankka: a `Consumer` over `SolveJobEntity` events → outbound HTTP with Timefold's timing;
subscriptions in `TenantEntity`; the delivery log a `View`.

## 3. Platform API

Separate from the model APIs, authenticated with a **personal access token** (`Authorization:
Bearer`); `X-TF-TENANT-ID` selects the tenant when the caller belongs to several. Spec:
[`notes/timefold/platform-api-1.12.3.json`](notes/timefold/platform-api-1.12.3.json) — "intended for
customers to interact with the platform (not models)". Smaller than the docs' prose suggests:
members, API keys, webhooks, the queue and the audit log are UI-only. Three areas under
`/api/platform/v1/`:

| Area | Endpoints | Notes |
|---|---|---|
| **Models** | `POST/GET /models`, `GET/PUT/PATCH/DELETE /models/{registrationKey}`, `…/restrictedTo`, `…/model-images/{name}` | **A model is registered by uploading a jar** (`application/octet-stream`, "model descriptor (jar package generated for the model)"). `409` if the id/key exists, `422` at the maximum number of registered versions. Visibility `Private` or public; `restrictedTo` a set of tenant ids |
| **Configuration profiles** | `GET/POST /models/{modelId}/configurations`, `GET/PUT/DELETE …/{configurationId}` | below |
| **Maps** (routing models) | OSRM map deployments; per-tenant external providers and location sets | not ours |

```jsonc
// ConfigurationProfileDTO
{ "id": "…", "name": "fast", "description": "…",                 // name ≤ 60, description ≤ 1000
  "defaultConfigProfileId": "…",                                 // required: the profile this one derives from
  "runConfiguration":       { "maxThreadCount": 1, "termination": { /* SolverTerminationConfigDTO = §2.1's */ } },
  "resourcesConfiguration": { "memory": 1024, "labels": { } },   // Mi; labels = scheduling policy (arch)
  "modelConfiguration":     { /* the model's overrides object: <constraint>Weight … */ },
  "mapsConfiguration":      { }, "updatedAt": "…" }
```

```jsonc
// RegisteredCatalogEntryDTO — what a model's jar declares
{ "registrationKey": "employee-scheduling", "model": "…", "name": "…", "version": "v1",
  "maturityLevel": "…", "type": "Private", "restrictedTo": ["<tenant uuid>"],
  "description": "…", "overview": "…", "logoUrl": "…", "images": [], "visualizationPages": [],
  "features": ["…"],
  "outputMetrics": [ { "id": "unassignedShifts", "name": "…", "description": "…", "type": "…", "priority": 2, "example": "10", "exampleFormatted": "…" } ],
  "resources": { "cpuRequest": "…", "cpuLimit": "…", "memoryRequest": "…", "memoryLimit": "…" },
  "environment": { }, "maxThreadCount": 1, "trialConfig": { },
  "buildInfo": { "version": "…", "solverVersion": "…", "sdkVersion": "…", "buildCommit": "…", "buildTime": "…", "branch": "…" },
  "releaseOffset": 0,        // 0 = latest, 1..3 = releases behind; "mirrors the maximum organization version lag"
  "status": "…", "lastUpdateDate": "…" }
```

`outputMetrics` here are the `x-tf-*` KPI fields of §2.3, lifted out of the schema. `releaseOffset`
is a version-lag rule — a tenant may be at most three releases behind — which is ankka's "one minor
behind" rule for services, applied to models.

For us the Platform API is the seam to ankka's identity: a satisfactory tenant is administered by
the same Keycloak principal ankka's control plane already verifies, so a PAT is a Keycloak token and
there is no second user directory. Model API keys stay satisfactory's own, hashed in `TenantEntity`.

## 4. Queueing and capacity

"When a tenant exceeds its concurrent capacity, additional requests are queued and started when
capacity is available." `priority` 0–10 orders a tenant's own queue; `SOLVING_SCHEDULED` *is* the
queue. `429` exists on every operation, so rate limits are part of the contract from day one.

## 5. Models: maturity, versioning, packaging

Maturity levels: `Template`, `Experimental`, `Preview`, `Stable`, `Deprecated`. A **stable** model's
API is backwards compatible unless explicitly marked otherwise; the model version is aligned with the
REST API version in the path (`/v1/`); a breaking change is a new path version. Each model publishes
its own OpenAPI spec, and the docs recommend `openapi-generator` for clients (with
`--openapi-normalizer REPLACE_ONE_OF_BY_DISCRIMINATOR_MAPPING=true` for Java — the per-issue-type
`oneOf`s are why).

**A model is a jar** with a descriptor, uploaded to `POST /api/platform/v1/models`, declaring
version, maturity, features, KPI descriptors, resource requests/limits, environment,
`maxThreadCount` and build info. Our `SolverModel` SPI (DESIGN.md §6) produces that artefact —
built into the solver image for v1, uploadable later, the same jar either way.

## 6. What a model must supply — the SPI, re-derived from the spec

| For | The model supplies |
|---|---|
| the model's OpenAPI spec | input schema, output schema, **overrides schema** (one `<constraint>Weight` per constraint), patch paths |
| `inputMetrics`, `kpis`, `outputMetrics` | typed, titled, described metric fields with priority and example (`x-tf-*`) |
| `/validation-result`, `/validation-issue-types` | the issue catalog (codes, severities, messages) and one schema per issue type |
| `DATASET_COMPUTED`, `/score-analysis` | scoring an unsolved/supplied plan; constraint names; justification descriptions |
| solving | `SolverConfig`, input ↔ solution codecs, `ConstraintWeightOverrides` mapping |
| `from-input select=SOLVED`, `from-patch select=SOLVED` | output-as-input encoding that preserves assignments (pinning); disruption KPI |
| `/demo-data` | named demo datasets with descriptions, tags and config entries |
| the catalog | registration key, entity name, version, maturity, features, resources, `maxThreadCount`, build info |
| optionally | extra routes (recommendations, assignability) the platform mounts without understanding |

## 7. What this changes in DESIGN.md

1. **No `mode: continuous`, no daemon mode, no `Idle`, no `/changes`.** Real-time planning is
   lineage: `from-input` / `from-patch` → a new dataset warm-started from the parent's solved state.
   "Continuously improving solutions" is a long `spentLimit` plus `/events`.
2. **Statuses are theirs**, and `SOLVING_SCHEDULED` precedes validation. The entity's fold rules
   (lease epoch, monotonic `seq`, blobs by ref) are unchanged underneath.
3. **Weights are typed integer fields per constraint**, generated into the model's schema.
4. **`operation=NONE`, `POST /{id}`, and stateless `POST /score-analysis`** are in from the start.
5. **`/events` carries `Metadata`, not solutions.** Score and status stream; the body is a `GET`.
   The earlier `?payload=` idea is withdrawn — they already made this choice.
6. **Terminate is `DELETE /{id}`** and returns the dataset; storage deletion is `/purge`, and a purged
   dataset can be restored while retention lasts (soft delete).
7. **Webhooks carry metadata and links, not solutions**, with their exact signing and retry rules.
8. **`PUT /v1/problems` is gone**; gzip with the same limits.
9. **Configuration profiles** are a tenant feature, referenced by id or name.
10. **KPIs and validation issues are typed schema**, which is what the SPI must produce.

## 8. Deliberate divergences

| Timefold Platform | satisfactory | Why |
|---|---|---|
| Enterprise Solver: multi-threading, nearby selection, throttling consumer | Community Solver: `maxThreadCount` validated and capped at 1; own throttle | Licence. Raised only when Enterprise is a deliberate purchase |
| Four commercial models | Two Apache-2.0 quickstart models behind the SPI; BYO later | We are the model author, not the model vendor |
| Managed cloud, or self-hosted on their Kubernetes install | Two ankka services on any ankka installation | It is what ankka is for |
| SSE resumption undocumented; `410` once final | `?after=<seq>` and `id:` frames so `Last-Event-ID` works; `?follow=lineage` | Every event carries a journaled `seq`, so **the receiver can always dedupe and the platform never has to drop** — the same principle makes webhooks at-least-once (§2.7) |
| `POST /v1/demo-data` (docs); `GET` (spec) | `GET` | The spec agrees |
| `GET /v1/{entity}` list deprecated | Kept, with `?status=&tag=&page=&size=` | An integrator needs to find their datasets by API; Timefold moved this to the UI, we have no UI |
| No idempotency on submit | `Idempotency-Key` honoured on `POST` | A retried submit should not solve twice |
| `from-patch` is preview, feature-flagged | Built in from the first version | It is the real-time planning story |
| No documented behaviour for `from-*` on an active parent | Supersede (§2.6) | Lineage preserved; a client should not have to terminate to change |
| Webhooks UI-only; `X-Timefold-*` headers | Platform API endpoints; `X-Satisfactory-*`, same scheme | The ankka integration registers programmatically; not their trademark |
| Personal access tokens issued by the platform | Keycloak tokens via ankka | One identity, already verified by the control plane |

## 9. Open items

None against the spec. What the spec cannot tell us and only running it would: whether `from-*`
on a `SOLVING_ACTIVE` parent is accepted (§2.6 decides our behaviour either way), and what the
SSE frames look like on the wire (`event:` names or bare `data:`; the spec says only that the
schema is `Metadata`). Neither blocks the design.

## Sources

- Specs: [`notes/timefold/employee-scheduling-v1.json`](notes/timefold/employee-scheduling-v1.json) ·
  [`notes/timefold/platform-api-1.12.3.json`](notes/timefold/platform-api-1.12.3.json)
- [Introduction](https://docs.timefold.ai/timefold-platform/latest/introduction) ·
  [Platform concepts](https://docs.timefold.ai/timefold-platform/latest/concepts) ·
  [Getting started](https://docs.timefold.ai/timefold-platform/latest/getting-started-with-the-timefold-platform)
- [API integration](https://docs.timefold.ai/timefold-platform/latest/api/api-integration) ·
  [Model API usage](https://docs.timefold.ai/timefold-platform/latest/api/model-api-usage) ·
  [Receiving results](https://docs.timefold.ai/timefold-platform/latest/api/receiving-model-api-results/receiving-model-api-results) ·
  [Webhooks](https://docs.timefold.ai/timefold-platform/latest/api/receiving-model-api-results/webhooks) ·
  [SSE](https://docs.timefold.ai/timefold-platform/latest/api/receiving-model-api-results/server-sent-events) ·
  [Polling](https://docs.timefold.ai/timefold-platform/latest/api/receiving-model-api-results/polling) ·
  [Handling changes](https://docs.timefold.ai/timefold-platform/latest/api/handling-changes-to-your-planning-data) ·
  [Platform API](https://docs.timefold.ai/timefold-platform/latest/api/platform-api)
- [Dataset lifecycle](https://docs.timefold.ai/timefold-platform/latest/how-tos/run-lifecycle) ·
  [Configuration profiles](https://docs.timefold.ai/timefold-platform/latest/how-tos/configuration-parameters-and-profiles) ·
  [Real-time planning with /from-patch](https://docs.timefold.ai/timefold-platform/latest/how-tos/from-patch-endpoint) ·
  [Interpreting results](https://docs.timefold.ai/timefold-platform/latest/how-tos/interpreting-model-run-results) ·
  [Tenant webhooks](https://docs.timefold.ai/timefold-platform/latest/how-tos/tenant-webhooks)
- [Model catalog](https://docs.timefold.ai/timefold-platform/latest/models/catalog) ·
  [Bring your own model](https://docs.timefold.ai/timefold-platform/latest/models/bring-your-own-model)
- [Employee Shift Scheduling: input validation](https://docs.timefold.ai/employee-shift-scheduling/latest/user-guide/input-validation) ·
  [API tooling](https://docs.timefold.ai/employee-shift-scheduling/latest/user-guide/using-the-api/api-tooling)
- [Kestra Timefold Solve task](https://kestra.io/plugins/plugin-timefold/io.kestra.plugin.timefold.solve)
