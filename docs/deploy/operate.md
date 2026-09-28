---
title: Operate the service
description: What an operator can see and do — every tenant's datasets, the workers and their slots, draining a worker, pausing a tenant's queue, force-terminating a dataset, the audit trail, and the Prometheus metrics.
kind: guide
related: [deploy/deploy-on-ankka.md, concepts/workers-and-leases.md, concepts/tenancy.md, reference/platform-api.md]
---

# Operate the service

An operator is a person whose platform token carries the operator role, `satisfactory-operator` by
default. Operators create tenants, see every tenant, and use the operator routes under
`/api/platform/v1/ops`, which answer `403` to anyone else. Every action an operator takes is recorded
in an audit trail.

## See what is running

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$SAT/api/platform/v1/ops/datasets?status=SOLVING_ACTIVE"
curl -s -H "Authorization: Bearer $TOKEN" $SAT/api/platform/v1/ops/workers
```

The datasets listing crosses tenants and can be filtered by `tenantId`, `model` and `status`. Each row
carries the dataset's id, tenant, model, status, priority, submit time, best score, and which worker
holds it under which lease epoch and attempt. It never carries a dataset's contents.

The workers listing shows every registered worker: its id, the models it can solve, its slots and how
many are busy, whether it is draining, and when it was last seen. A worker that has not registered for
sixty seconds is marked stale.

## Drain a worker

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" $SAT/api/platform/v1/ops/workers/<workerId>/drain
```

A draining worker claims nothing more, and at its next heartbeat releases each dataset it holds back to
the queue with its best solution reported, so another worker continues them. It keeps running and
registering; `undrain` puts it back to work. Drain a worker before taking its node away, or to move a
long solve off an instance that is about to be replaced.

## Pause a tenant's queue

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" $SAT/api/platform/v1/ops/tenants/<tenantId>/queue/pause
```

While paused, nothing of that tenant's starts: its queued datasets stay `SOLVING_SCHEDULED` and their
metadata says `queuePaused`. Solves already running continue. `resume` starts the queue again. Pausing
is the tool for a tenant that is submitting more than it should, without revoking its keys.

## Force-terminate a dataset

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" $SAT/api/platform/v1/ops/datasets/<datasetId>/terminate
```

The dataset completes with its best solution, as a client's own terminate would, from any tenant. It is
recorded as `force-terminate` in the audit trail.

## The audit trail

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$SAT/api/platform/v1/ops/audit?limit=100"
```

Each entry names the operator's subject, the action (`force-terminate`, `drain`, `undrain`,
`pause-queue`, `resume-queue`), its target and the time. The last five hundred are kept.

## Metrics

`GET /metrics` serves Prometheus text when the request carries `Authorization: Bearer <token>` with the
value of `SAT_METRICS_TOKEN`. With that variable unset the route refuses everyone.

| Metric | Labels | Meaning |
|---|---|---|
| `satisfactory_queued` | `model` | datasets waiting for a worker |
| `satisfactory_slots` | `state` of `busy` or `free` | the pool's slots |
| `satisfactory_workers` | `state` of `ready`, `draining` or `stale` | registered workers |
| `satisfactory_lease_losses_total` | | leases that expired without a heartbeat |
| `satisfactory_requeues_total` | | datasets put back in the queue |
| `satisfactory_solves_total` | `outcome` of `completed` or `failed` | finished solves |
| `satisfactory_webhook_failures_total` | | deliveries that exhausted their retries |

Queue depth divided by free slots is the number that says whether the pool is the right size.

## Logs

`ankka services logs api -p satisfactory` and `ankka services logs solver -p satisfactory` are the
services' logs. A worker's log lines that could quote a dataset are redacted before they leave the
worker, and no route, metric or log carries a dataset's contents, so the logs are safe to ship to a
shared system.

## Webhook deliveries

Failed deliveries are a tenant admin's to inspect and retry on the platform API;
[Receive webhooks](../build/webhooks.md) has the delivery log. An operator sees the count in
`satisfactory_webhook_failures_total`.
