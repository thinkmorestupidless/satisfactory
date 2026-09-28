---
title: Model API
description: Every route of a model's API — datasets, demo data, the catalog, lineage, analysis and the event stream — with its query parameters, bodies, statuses and error codes.
kind: reference
related: [build/submit-and-poll.md, build/streaming.md, concepts/lineage.md, reference/dataset-lifecycle.md, reference/platform-api.md]
---

# Model API

Every model's API lives under `/api/models/{model}/{version}`, and every route takes a tenant's API key
in the `X-API-KEY` header. `{entity}` is the name of what a request submits, which the model's descriptor
gives: `schedules` for `employee-scheduling`, `route-plans` for `vehicle-routing`. A dataset owned by
another tenant, or by another model, is a `404`.

## The catalog and demo data

| Method | Path | Answer |
|---|---|---|
| `GET` | `/model` | the model's descriptor |
| `GET` | `/demo-data` | the demo datasets: `[{ id, shortDescription, longDescription, tags, config: [{ key, value }] }]` |
| `GET` | `/demo-data/{demoDataId}` | a whole request, `{ modelInput, config }`, with the recommended configuration |
| `GET` | `/demo-data/{demoDataId}/input` | the input alone |
| `GET` | `/{entity}/validation-issue-types` | `{ issueTypes: [{ code, severity, message }] }` |
| `GET` | `/{entity}/validation-issue-types/{code}` | one issue type, or `404` |

The descriptor is `{ id, version, entity, name, description, maturity, features, schemas: { input,
output, overrides }, patchPaths, constraints: [{ name, key, description, level, defaultWeight }], kpis,
inputMetrics, issueTypes }`, where each metric is `{ id, title, description, type, priority, example }`.

## Datasets

| Method | Path | Body | Answer |
|---|---|---|---|
| `POST` | `/{entity}` | `{ modelInput, config? }` | `202` metadata |
| `POST` | `/{entity}/{id}` | | `202` metadata: solves a dataset submitted with `operation=NONE`; `400` otherwise |
| `GET` | `/{entity}` | | a page of metadata, newest first |
| `GET` | `/{entity}/{id}` | | `{ metadata, modelOutput, inputMetrics, kpis }`, the best solution so far; `410` if purged |
| `GET` | `/{entity}/{id}/metadata` | | metadata |
| `PATCH` | `/{entity}/{id}/metadata` | `{ name?, tags? }` | metadata |
| `GET` | `/{entity}/{id}/input` | | the input as submitted; `410` if purged |
| `GET` | `/{entity}/{id}/model-request` | | `{ modelInput, config }` as submitted; `410` if purged |
| `GET` | `/{entity}/{id}/config` | | the resolved configuration the worker used |
| `GET` | `/{entity}/{id}/validation-result` | | `{ status, issues }`; `status` is `PENDING`, `OK`, `WARNINGS`, `ERRORS` or `VALIDATION_NOT_SUPPORTED` |
| `GET` | `/{entity}/{id}/score-analysis` | | `{ score, constraints: [{ name, weight, score }], justifications? }` |
| `GET` | `/{entity}/{id}/logs` | | `{ details }` |
| `GET` | `/{entity}/{id}/events` | | server-sent events |
| `DELETE` | `/{entity}/{id}` | | `200` `{ metadata, modelOutput, inputMetrics, kpis }`: terminates, keeping the best solution |
| `DELETE` | `/{entity}/{id}/purge` | | `204`: removes the bodies from storage; `400` while solving |
| `PUT` | `/{entity}/{id}` | | `204`: restores a purged dataset; `404` once retention has expired |
| `POST` | `/{entity}/{id}/from-input` | `{ config? }` | `202` metadata of a child dataset |
| `POST` | `/{entity}/{id}/from-patch` | `{ patch: [{ op, path, value? }], config? }` | `202` metadata of a child dataset |
| `POST` | `/{entity}/score-analysis` | `{ modelInput, config? }` | the score analysis of the supplied plan; nothing is created |

### Query parameters

| Route | Parameter | Meaning |
|---|---|---|
| `POST /{entity}`, `from-input`, `from-patch` | `name`, `tags`, `operation`, `configurationId`, `priority` | see [Submit and poll](../build/submit-and-poll.md) |
| `from-input` | `select` | `UNSOLVED` (default) for the parent's input, `SOLVED` for its output |
| `from-patch` | `select` | `SOLVED` (default) to patch the output, `UNSOLVED` to patch the input |
| `POST /{entity}/{id}` | `priority` | the queue priority |
| `GET /{entity}` | `status`, `tag` (both repeatable), `page`, `size` (1 to 200, default 50), `includeExpired` | filters and paging |
| `GET /{entity}/{id}/events` | `after`, `status` (repeatable), `follow=lineage` | see [Stream progress](../build/streaming.md) |
| `GET /{entity}/{id}/score-analysis` | `includeJustifications` | answered with `"justifications": "unsupported"` |
| `DELETE /{entity}/{id}` | `force` | |
| `POST /{entity}/score-analysis` | `configurationId` | the profile to score against |

### Headers

| Header | On | Meaning |
|---|---|---|
| `X-API-KEY` | every route | the tenant's key |
| `Idempotency-Key` | `POST /{entity}` | a repeat within 24 hours returns the existing dataset |
| `Content-Encoding: gzip` | any body | the body is compressed; 100 MB compressed, 2 GB inflated |

## Metadata

```json
{ "id": "ds_01K6…", "parentId": null, "originId": null, "name": "week-40", "tags": ["weekly"],
  "solverStatus": "SOLVING_ACTIVE", "score": "0hard/-2085soft",
  "submitDateTime": "…", "startDateTime": "…", "activeDateTime": "…", "completeDateTime": null, "shutdownDateTime": null,
  "validationResult": { "summary": "OK", "errors": [], "warnings": [] },
  "failureMessage": null, "supersededBy": null, "queuePaused": null, "seq": 8 }
```

| Field | Meaning |
|---|---|
| `id`, `parentId`, `originId` | the dataset, the one it was derived from, and the root of its chain |
| `name`, `tags` | as submitted or patched |
| `solverStatus` | one of the ten statuses in [Dataset lifecycle](dataset-lifecycle.md) |
| `score` | the best score so far, as the model's score type formats it; `null` until scored |
| `submitDateTime`, `startDateTime`, `activeDateTime`, `completeDateTime`, `shutdownDateTime` | when it was submitted, when a worker began, when solving began, when solving ended, when post-processing ended |
| `validationResult` | the summary; `/validation-result` has the issues |
| `failureMessage` | set with `SOLVING_FAILED` |
| `supersededBy` | the child that superseded this dataset while it was solving |
| `queuePaused` | `true` while the dataset waits on a paused queue |
| `seq` | the dataset's sequence number; event frames and webhook events carry it |

## Errors

An error is `{ id, code, message, details }`:

| Status | Code |
|---|---|
| `400` | `bad-request`, `validation` |
| `401` | `unauthorized`: no key, or an unknown or revoked key |
| `403` | `forbidden`: a read-only key on a write, or a runner route without its token |
| `404` | `not-found` |
| `409` | `conflict` |
| `410` | `gone`: a purged dataset's bodies, or the event stream of a final dataset |
| `413` | `payload-too-large` |
| `429` | `rate-limited` |
| `500`, `503`, `504` | `internal`, `unavailable`, `timeout` |

A refusal ankka's runtime makes before the request reaches satisfactory, such as an access control
list's `401` or an unknown route, is ankka's own `{ status, error }`.

## Who am I

`GET /api/aboutme` with a key answers `{ tenantId, tenantName, keyId, role, queuePaused }`.
