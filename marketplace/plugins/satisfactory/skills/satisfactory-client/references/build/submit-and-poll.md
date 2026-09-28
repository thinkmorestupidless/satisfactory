# Submit and poll

> Submit a dataset with every option the request takes — termination, weights, a profile, priority, name and tags, idempotency, gzip — then poll its metadata and fetch the solution.

Source: https://satisfactory.ankka.cloud/build/submit-and-poll/
A submit is a `POST` to the model's entity path with the problem's input and, optionally, how to solve
it. It answers `202` with the dataset's metadata at once and solving happens on a worker. Polling the
metadata until the status is final is the simplest way to wait, and the right one when nothing else
fits; [Receiving results](../concepts/receiving-results.md) is when to prefer a webhook or the event
stream.

## The request

```bash
curl -s -X POST -H "X-API-KEY: $KEY" -H 'Content-Type: application/json' \
  "$API/schedules?name=week-40&tags=site:leeds&tags=weekly&priority=7&configurationId=fast" \
  -d '{
    "modelInput": { "employees": [ … ], "shifts": [ … ] },
    "config": {
      "run": {
        "maxThreadCount": 1,
        "termination": { "spentLimit": "PT5M", "unimprovedSpentLimit": "PT30S" }
      },
      "model": { "overrides": { "undesiredDayForEmployeeWeight": 5, "desiredDayForEmployeeWeight": 0 } }
    }
  }'
```

`modelInput` is the model's input, checked against its schema before anything is created. Everything
in `config` is optional, and so is `config` itself.

### Query parameters

| Parameter | Values | Meaning |
|---|---|---|
| `name` | up to 255 characters | a name for the dataset; also accepted in `config.run.name` |
| `tags` | repeatable, up to 100 unique | labels to list and filter by, and to route webhooks; also `config.run.tags` |
| `operation` | `SOLVE` (default) or `NONE` | `NONE` validates and scores without solving; `POST /{id}` solves later |
| `configurationId` | a profile's id or name, or `standard` | the profile whose configuration applies under the request's own |
| `priority` | 0 to 10, default 5 | ordering within the tenant's queue; higher first |

### Termination

| Field | Type | Meaning |
|---|---|---|
| `spentLimit` | ISO-8601 duration | the most time to spend |
| `unimprovedSpentLimit` | ISO-8601 duration | stop when no improvement has been found for this long |
| `slidingWindowDuration` and `minimumImprovementRatio` | duration and ratio | stop when the score improved by less than the ratio over the window: diminishing returns |
| `stepCountLimit` | integer | stop after this many steps; independent of hardware |
| `moveCountLimit` | integer | stop after this many moves; independent of hardware |

With nothing set, the solve stops on diminishing returns with a thirty second window and a ratio of
`0.0001`. The tenant's lifetime ceiling, twelve hours by default, applies above whatever is asked.

### Weights

`config.model.overrides` has one integer field per constraint, named `<key>Weight` and listed in the
model's descriptor. A missing field means the model's default, and `0` disables the constraint. The
fields are typed in the model's schema, so an unknown field or a negative value is a `400`.

### Threads

`maxThreadCount` is validated and capped at 1: the Community edition of Timefold Solver solves
single-threaded. A higher value is not an error, it is reduced.

## How configuration is resolved

Highest first: the request's own `config`; the profile named by `configurationId`; for a derived
dataset, the parent's configuration; the model's defaults; and last the caps the installation enforces.
`GET /{id}/config` returns the result of that resolution, which is what the worker used.

## Idempotency and compression

A retried submit should not solve twice. Send an `Idempotency-Key` header, and a repeat with the same
key within 24 hours returns the existing dataset's metadata instead of creating another.

A body may be sent with `Content-Encoding: gzip`. The limits are 100 MB compressed and 2 GB inflated;
over either is a `413`.

## What can go wrong

| Status | Code | Why |
|---|---|---|
| `400` | `validation` | the input violates the model's schema, or the configuration is invalid; `details` names each problem |
| `400` | `bad-request` | an unknown operation, a priority outside 0 to 10, an unknown profile |
| `401` | | no key, or a key the service does not know |
| `403` | `forbidden` | a read-only key |
| `413` | `payload-too-large` | over the compressed or inflated limit |
| `429` | `rate-limited` | over the tenant's submits per minute |

## Poll the metadata

```bash
curl -s -H "X-API-KEY: $KEY" $API/schedules/$ID/metadata
```

```json
{"id":"ds_01K6…","parentId":null,"originId":null,"name":"week-40","tags":["site:leeds","weekly"],
 "solverStatus":"SOLVING_ACTIVE","score":"0hard/-2085soft",
 "submitDateTime":"2026-09-28T10:15:02Z","startDateTime":"2026-09-28T10:15:03Z","activeDateTime":"2026-09-28T10:15:04Z",
 "completeDateTime":null,"shutdownDateTime":null,
 "validationResult":{"summary":"OK","errors":[],"warnings":[]},"failureMessage":null,"supersededBy":null,"seq":8}
```

Watch `solverStatus` for a final status: `SOLVING_COMPLETED`, `SOLVING_INCOMPLETE`, `SOLVING_FAILED`,
`DATASET_INVALID`, or `DATASET_COMPUTED` for `operation=NONE`. Start with a short interval and lengthen
it for a long solve. The five timestamps mark the phase boundaries, so queueing, initialisation and
solving time are readable without a metrics call. `seq` is the dataset's sequence number, which the
event stream and webhooks also carry.

## Fetch the result

`GET /{id}` returns the metadata, the best solution so far as `modelOutput`, the model's `inputMetrics`
and its `kpis`. It works while the dataset is still solving, returning the best solution found so far.
`GET /{id}/score-analysis` returns the score broken down by constraint, and `GET /{id}/validation-result`
the model's validation issues.

## Solve later

A dataset submitted with `operation=NONE` stops at `DATASET_COMPUTED` with its input's score and
metrics. `POST /{id}` queues it for solving, with an optional `priority`. It is a `400` for a dataset
that is not at that point.

## Terminate

`DELETE /{id}` stops a solve and returns the dataset with its best solution kept: the status becomes
`SOLVING_COMPLETED` if a solution exists and `SOLVING_INCOMPLETE` otherwise. Terminating a dataset that
is already final changes nothing. Terminate latency is a worker's heartbeat interval, five seconds.

## Rename, retag, list

`PATCH /{id}/metadata` with `{ "name": …, "tags": […] }` changes either. `GET /{entity}` lists the
tenant's datasets newest first, with `status` and `tag` filters, both repeatable, and `page` and `size`
up to 200.
