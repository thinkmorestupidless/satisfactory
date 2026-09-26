# Contract: Model API (tenant-facing, `X-API-KEY`)

Base: `https://api-satisfactory.<base domain>/api/models/{model}` where `{model}` is the registration key (`employee-scheduling`, `vehicle-routing`) and `{entity}` is the model's entity name (`schedules`, `route-plans`). Bodies and field names are Timefold's, per [API.md §2](../../../API.md); this contract records what `api` actually serves and where ankka's `http` shapes it. Locally the base is `http://localhost:9000/api/models/{model}`.

## Authentication and errors

- `X-API-KEY: sk_…` on every route. Missing or unknown key → `401` with `WWW-Authenticate: ApiKey`. A `read-only` key on a mutating route → `403`.
- Every error body is `ErrorInfo { id, code, message, details }`; schema failures on submit are `ValidationErrorInfo { …, details: [string] }` with `400`.
- `429 ErrorInfo{code: "rate-limited" | "quota"}` when the tenant's submit bucket is empty.
- Another tenant's dataset id → `404`, never `403`.

## Routes

| Method | Path | Role | Response | Notes |
|---|---|---|---|---|
| GET | `/v1/demo-data` | read | `[DemoDataSummary]` | from the model |
| GET | `/v1/demo-data/{demoDataId}` | read | full `SubmitRequest` | |
| GET | `/v1/demo-data/{demoDataId}/input` | read | `modelInput` | |
| GET | `/v1/model` | read | `ModelDescriptor` | **addition**: id, version, maturity, entity, schemas (input, output, overrides, patch paths), constraints, KPI and input-metric descriptors, issue types. This is what tools are generated from |
| POST | `/v1/{entity}` | write | `202 Metadata` | query `name`, `operation=SOLVE\|NONE` (default SOLVE), `configurationId`, `priority` (0–10, default 5), `tags` (repeatable). Headers: `Idempotency-Key` (optional), `Content-Encoding: gzip` (optional). Body ≤ 100 MiB compressed / 2 GiB raw (R9 limit) |
| POST | `/v1/{entity}/{id}` | write | `202 Metadata` | solve a `DATASET_COMPUTED` dataset; query `priority` |
| GET | `/v1/{entity}` | read | `Page[Metadata]` | query `status` (repeatable), `tag` (repeatable), `page` (0-based), `size` (≤ 200, default 50) |
| GET | `/v1/{entity}/{id}` | read | `DatasetResponse { metadata, modelOutput, inputMetrics, kpis }` | `modelOutput` null until a solution exists; `410` when purged |
| GET | `/v1/{entity}/{id}/metadata` | read | `Metadata` | |
| PATCH | `/v1/{entity}/{id}/metadata` | write | `Metadata` | body `{ name?, tags? }`; `400` on limits or purged |
| GET | `/v1/{entity}/{id}/input` | read | `modelInput` | `410` when purged |
| GET | `/v1/{entity}/{id}/model-request` | read | `SubmitRequest` | |
| GET | `/v1/{entity}/{id}/config` | read | `ModelConfiguration` | the resolved configuration |
| GET | `/v1/{entity}/{id}/validation-result` | read | `ValidationResult { status, issues[] }` | `status` ∈ `VALIDATION_NOT_SUPPORTED \| OK \| WARNINGS \| ERRORS` |
| GET | `/v1/{entity}/{id}/score-analysis` | read | `ScoreAnalysis` | query `includeJustifications` accepted; Community shape (below) |
| GET | `/v1/{entity}/{id}/logs` | read | `{ details: string }` | last 200 worker lines |
| GET | `/v1/{entity}/{id}/events` | read | `text/event-stream` | query `after` (seq), `status` (repeatable), `follow=lineage`; `410` when final and `after` is absent or ≥ final seq |
| DELETE | `/v1/{entity}/{id}` | write | `200 DatasetResponse` | terminate; query `force`; final → returns unchanged |
| DELETE | `/v1/{entity}/{id}/purge` | write | `204` | `400` while solving |
| PUT | `/v1/{entity}/{id}` | write | `204` | restore; `404` after retention |
| POST | `/v1/{entity}/{id}/from-input` | write | `202 Metadata` | query as submit plus `select=UNSOLVED\|SOLVED` (default UNSOLVED); body `{ config? }` |
| POST | `/v1/{entity}/{id}/from-patch` | write | `202 Metadata` | query as submit plus `select=SOLVED\|UNSOLVED` (default SOLVED); body `{ patch: [{op, path, value?}], config? }` |
| POST | `/v1/{entity}/score-analysis` | read | `ScoreAnalysis` | body `{ modelInput, config? }`; stateless; runs on `api` |
| GET | `/v1/{entity}/validation-issue-types` | read | `{ issueTypes: [...] }` | |
| GET | `/v1/{entity}/validation-issue-types/{code}` | read | issue type | |
| GET | `/api/aboutme` | read | `WhoAmI { tenantId, tenantName, keyId, role, queuePaused }` | outside the model prefix |

## Metadata

As API.md §2.3, plus two additions: `supersededBy: string | null` and `seq: number` (the latest recorded sequence number, so a poller can dedupe against webhooks and streams). `validationResult.summary` is `OK | WARNINGS | ERRORS | PENDING`.

## Server-sent events

Because ankka's `sse` emits `data:` frames whose payload is a JSON-quoted string, each frame is:

```text
data: "{\"seq\":18,\"metadata\":{...Metadata...}}"
```

- One frame per recorded improvement and per status change, at most one per second per dataset (the source polls `updates-since` every 500 ms and coalesces).
- A keep-alive `data: "{\"heartbeat\":true}"` every 15 s while nothing else is sent.
- `?after=<seq>` resumes exactly; if `after+1` is older than the ring, the first frame is the current best with `"skipped": n`.
- With `follow=lineage`, a `Superseded` parent yields its final frame and the stream continues with `{"seq":…, "metadata": <child>}` frames whose `metadata.id` is the child's.
- The stream ends after the frame carrying a final status. `satisfactory-client` unwraps the double encoding and exposes `Source[StreamFrame]`.

## Score analysis (Community shape, research R2)

```jsonc
{ "score": "0hard/-412soft",
  "constraints": [ { "name": "Undesired day for employee", "weight": "0hard/-5soft", "score": "0hard/-120soft" } ],
  "justifications": "unsupported" }
```

`matchCount` and `matches` are absent. Per-constraint `score` is the ablation difference (research R2).

## Submit request

As API.md §2.1. `config.model.overrides` is an object with one `<constraintName>Weight: integer ≥ 0` per constraint, named by the model (`ModelDescriptor.schemas.overrides`). `config.resourcesConfiguration` and `mapsConfiguration` are accepted and ignored.

## Lineage rules on the wire

- `from-input select=SOLVED` and `from-patch select=SOLVED` on a parent without a solution → `400 ErrorInfo{code:"no-solution"}`.
- Any `from-*` on an active parent supersedes it (API.md §2.6); the response is the child's metadata; the parent's next metadata shows `supersededBy`.
- `from-patch` paths: JSON Pointer with two extensions, `/{array}/[field=value]` and `/{array}/-` (append), validated before the dataset is created; a path that resolves to nothing → `400` with `details[0]` naming the operation index.
