# Contract: Runner protocol (`/internal/*`, `X-Runner-Token`)

Served by `RunnerEndpoint` in `api`; spoken by `ControlChannel.Http` in `solver`. `ControlChannel.Local` makes the same calls through the component client inside `api`. JSON bodies use the `protocol` module's codecs. Every dataset-scoped call carries the `epoch` the worker was granted; a stale epoch answers `409` and the worker must stop that solve, discard its state and free the slot.

| Method | Path | Request | Response |
|---|---|---|---|
| POST | `/internal/workers/{workerId}` | `{ models: [modelKey], slots }` | `204` register / refresh (`lastSeenAt`); `{ drain: boolean }` |
| DELETE | `/internal/workers/{workerId}` | | `204` deregister |
| POST | `/internal/leases` | `ClaimRequest { workerId, models: [modelKey], datasetId? }` | `200 ClaimResponse { datasetId, epoch, spec: DatasetSpec, inputRef, patchRef?, warmStartRef?, leaseTtl: "PT20S", drain }`, or `204` when nothing is claimable within the long-poll window (≤ 8 s), or `409` when `datasetId` was named and is not claimable |
| POST | `/internal/datasets/{id}/phase` | `PhaseRequest { epoch, phase: validated\|invalid\|computed\|started\|active, payload }` | `204`; `payload` is `ValidationResult` for validated/invalid, `{ inputScore, inputMetrics, analysisRef }` for computed |
| POST | `/internal/datasets/{id}/solutions` | `ReportRequest { epoch, score, scoreKey, feasible, solutionRef, kpis }` | `200 ReportResponse { seq?, notBetter, evictedRefs: [ref] }` — the endpoint deletes `evictedRefs` from the blob store before replying |
| POST | `/internal/datasets/{id}/heartbeat` | `HeartbeatRequest { epoch, status, scoreCalcSpeed, moveCount, logLines? }` | `200 HeartbeatResponse { terminate, force, leaseTtl, drain }`; re-arms the lease timer |
| POST | `/internal/datasets/{id}/complete` | `CompleteRequest { epoch, reason: termination\|terminated, analysisRef? }` | `204` |
| POST | `/internal/datasets/{id}/fail` | `FailRequest { epoch, message }` | `204` |
| POST | `/internal/datasets/{id}/release` | `ReleaseRequest { epoch, reason: drained\|shutdown }` | `204`; the dataset is re-queued with `warmStartRef = best` |
| PUT | `/internal/blobs/{datasetId}/{rest}` | raw bytes, `Content-Type` | `204`. A ref `<dataset>/<kind>/<part>` travels as two segments, the second URL-encoded (`solution%2Fe1-3`) |
| GET | `/internal/blobs/{datasetId}/{rest}` | | raw bytes |

## Worker obligations

- Heartbeat every held dataset every 5 s; treat `terminate` as `SolverManager.terminateEarly`, then report the final best and `complete(reason=terminated)`; treat `force` as: skip the final report, `complete` at once.
- Report at most one solution per `minInterval` (default 1 s, floor 250 ms), latest wins, and always the final one before `complete`.
- On `drain: true` in any reply: claim nothing further; finish or release current solves; deregister when idle.
- On SIGTERM: terminate all, flush best, `release(reason=shutdown)` each, deregister, exit — under 20 s.
- Never log or write to a blob anything but what the protocol names; input bodies are fetched by ref and held in memory only.

## Sequence

```text
worker                                  api
  │ POST /internal/workers/w1           │  register
  │ POST /internal/leases (long-poll)   │  view candidates → tenant.acquire-slot → dataset.lease
  │ ◀ ClaimResponse{epoch=3,…}          │
  │ GET  /internal/blobs/<inputRef>     │
  │ POST …/phase validated → computed → started → active
  │ POST …/solutions {epoch=3,…}   ×N   │  entity records strictly-better only; evicted refs deleted
  │ POST …/heartbeat {epoch=3}     ×M   │  ◀ {terminate?, leaseTtl}
  │ POST …/complete {epoch=3}           │  → Completed; slot released by consumer; webhooks fan out
```
