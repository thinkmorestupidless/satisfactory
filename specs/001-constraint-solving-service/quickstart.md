# Quickstart: validating satisfactory end to end

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Contracts**: [contracts/](./contracts/)

Five tiers, cheapest first. Each tier names the spec stories and success criteria it proves. Tiers 1–4 run on a laptop; tier 5 needs a local ankka installation (`just up` in `../ankka`).

## Prerequisites

- JDK 21, sbt 1.12.x, Docker (tiers 2–5 start Postgres through Testcontainers or compose)
- `../ankka` checked out at `v0.5.0` or later, and its artifacts resolvable (Maven Central, or `sbt publishLocal` in `../ankka`)
- For tier 5: the `ankka` CLI on PATH, a kind cluster from `../ankka` (`just up`), and `ankka login` as the `dev` user

## Tier 1 — models and runner, no runtime (seconds)

```bash
sbt modelSpi/test models/test          # JUnit: codecs round-trip, validation catalog, weight mapping, ablation analysis
sbt protocol/test runner/test          # munit: throttle (latest-wins, 250 ms floor, final always sent), claim loop on a scripted ControlChannel
sbt spike/run                          # phase 0: one model, SolverManager, throttle → prints improving scores for 10 s
```

Expected: the spike prints a strictly increasing score sequence and one final line; both model suites green. Proves: R1/R3 interop, FR-040/041 at the runner.

## Tier 2 — the dataset entity's fold rules (seconds)

```bash
sbt 'api/testOnly satisfactory.api.DatasetEntitySuite'
```

`EventSourcedTestKit` properties: a report with a stale epoch is refused with `Conflict` and persists nothing; an equal-or-worse score replies `NotBetter` and persists nothing; the ring never exceeds 50; three `LeaseLost` events end in `Failed` with `best` intact; `supersede` on an active dataset persists `Superseded` then `Completed`. Proves: FR-037, FR-039, FR-040, FR-043; SC-004, SC-007 at the entity.

## Tier 3 — the whole API in one JVM (about a minute)

```bash
docker info >/dev/null                 # Testcontainers needs Docker
sbt 'api/testOnly satisfactory.api.*HttpSuite'
```

`AnkkaTestKit` starts `api` with `ControlChannel.Local` (the runner in-process) and the in-memory blob store, seeds one tenant with a read-write and a read-only key, and drives the Model API over HTTP with the employee scheduling demo dataset at `spentLimit = PT5S`:

| Suite | Stories | Checks |
|---|---|---|
| `SubmitHttpSuite` | 1 | 202 then SCHEDULED → … → COMPLETED; schema 400; model-invalid → `DATASET_INVALID` with typed issues; `operation=NONE` stops at COMPUTED and `POST /{id}` continues; `Idempotency-Key` returns the same id; read-only key gets 403 on POST |
| `StreamHttpSuite` | 2 | frames monotonic; `?after=` exact; old `after` → `skipped`; final → 410; heartbeat frame within 15 s; terminate → COMPLETED with best kept |
| `LineageHttpSuite` | 3 | `from-input` UNSOLVED/SOLVED; `from-patch` with `[id=…]` path; invalid path → 400 naming the op; derive on active parent → parent `supersededBy`, child warm-started; `follow=lineage` continues |
| `TenantHttpSuite` | 5 | concurrency 1 queues the second submit; priority order; profile weight in resolved config; `maxThreadCount` capped; cross-tenant 404 matrix |
| `WebhookHttpSuite` | 6 | signed delivery to a local receiver; receiver down 60 s → still delivered; duplicate detectable by `id`; exhausted → `webhook.failing` to the other subscription; delivery log entries; retry |
| `AnalysisHttpSuite` | 7 | stateless `POST /score-analysis`; per-dataset analysis after solve; Community shape (no `matches`) |
| `LifecycleHttpSuite` | 8 | list by tag; PATCH limits; purge refused while solving; purge → 410 on bodies; restore; expiry deletes blobs |
| `OperatorHttpSuite` | 10 | `/ops/datasets` without content; pause queue → nothing starts; force-terminate; audit rows; `/metrics` text |

Proves: SC-001 (demo → feasible < 60 s), SC-002, SC-003, SC-005, SC-009, SC-010, SC-011, SC-013, SC-014, SC-016, SC-018 (a log scan over the suite's captured output finds no input or solution content).

## Tier 4 — the pool: kill a worker mid-solve (about two minutes)

```bash
sbt 'solver/testOnly satisfactory.solver.PoolResilienceSuite'
```

Starts `api` under `AnkkaTestKit`, then two `SolverRuntime` instances in the same JVM speaking `ControlChannel.Http` against `api`'s bound port, each with one slot. Submits four datasets at `spentLimit = PT60S`; after 10 s, stops one runtime abruptly (no drain); after 20 s, drains the other through `/ops/workers/{id}/drain` and starts a third. Asserts: every dataset reaches `SOLVING_COMPLETED`; each dataset's final score ≥ its score at the moment its worker died; a replayed report from the dead worker's epoch answers 409 and changes nothing; the drained worker's datasets re-queue with `warmStartRef` and resume elsewhere within 60 s; `LeaseLost` count equals the number of kills. Proves: Story 4, Story 10 drain; SC-006, SC-007, SC-008 (worker half), SC-017.

## Tier 5 — deployed on a local ankka (ten minutes)

```bash
sbt api/Docker/publishLocal solver/Docker/publishLocal
kind load docker-image satisfactory-api:latest satisfactory-solver:latest --name ankka
ankka projects create satisfactory -O dev-org
kubectl -n ankka-satisfactory create secret generic satisfactory-secrets \
  --from-literal=runner-token="$(openssl rand -base64 32)"
ankka services apply -f deploy/api.json    -p satisfactory
ankka services apply -f deploy/solver.json -p satisfactory
ankka services expose api -p satisfactory                    # https://api-satisfactory.<base domain>
```

Then, with a Keycloak token from `ankka login`'s credentials file and the `satisfactory-operator` realm role added to `dev`:

```bash
export SAT=https://api-satisfactory.localhost:8443 TOKEN=$(ankka whoami --print-token)
curl -sk -X POST $SAT/api/platform/v1/tenants -H "Authorization: Bearer $TOKEN" \
  -d '{"name":"acme","firstAdminSubject":"<dev subject>"}'
curl -sk -X POST $SAT/api/platform/v1/tenants/<t>/keys -H "Authorization: Bearer $TOKEN" \
  -d '{"label":"ci","role":"read-write"}'                      # note the key: shown once
export KEY=sk_…
curl -sk $SAT/api/models/employee-scheduling/v1/demo-data -H "X-API-KEY: $KEY"
curl -sk -X POST "$SAT/api/models/employee-scheduling/v1/schedules?name=smoke" -H "X-API-KEY: $KEY" \
  -H 'Content-Type: application/json' --data-binary @<(curl -sk $SAT/api/models/employee-scheduling/v1/demo-data/SMALL -H "X-API-KEY: $KEY")
curl -skN "$SAT/api/models/employee-scheduling/v1/schedules/<id>/events" -H "X-API-KEY: $KEY"
```

Expected: `202` with `DATASET_CREATED`; the stream shows improving scores and closes on `SOLVING_COMPLETED` within the profile's limit; `ankka services logs solver -p satisfactory` shows the claim and reports; `kubectl -n ankka-satisfactory delete pod <solver pod>` mid-solve leaves the stream monotonic and the dataset completed. Proves: FR-075, SC-008 (API redeploy: `ankka services apply` with a new image tag during a solve), and the V-list items V1, V3, V4, V5, V6.

## Tier 6 — an ankka application consumes it (about a minute, no deployment)

```bash
sbt ankkaSatisfactory/test
```

`FakeSatisfactorySuite` scripts a submit and a `dataset.completed` webhook; `PlannerWorkflowSuite` runs an `AnkkaTestKit` service with an agent using `SatisfactoryTools.forModel(...)` under `TestModelProvider`, a workflow that submits and `thenPause`s, and an endpoint that verifies the fake's webhook and resumes it. Asserts the workflow reaches its result step with the solution, the submit carried the `ankka-workflow:<id>` tag, a tampered signature is refused, and a duplicate webhook is a no-op. Proves: Story 9; SC-015.

## Known limits at this tier of ankka

- `SC-012` (2 GiB uncompressed) is enforced as a cap but cannot be exercised on a `small` instance; the 100 MiB compressed case is what tier 5 tests.
- Slots per `large` worker instance = 1 (research R4); the pool's capacity equals its instance count until ankka offers a larger type.
- SSE frames are JSON-quoted strings and there are no `id:` frames (research R9); browsers' `EventSource` needs the client library's unwrapping until the ankka follow-up lands.
