# Quickstart: validating satisfactory end to end

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Contracts**: [contracts/](./contracts/)

Five tiers, cheapest first. Each tier names the spec stories and success criteria it proves. Tiers 1–4 run on a laptop; tier 5 needs a local ankka installation (`just up` in `../ankka`).

## Prerequisites

- JDK 21, sbt 1.12.x, Docker (tiers 2–5 start Postgres through Testcontainers or compose)
- `../ankka` checked out at `v0.5.0` or later, and its artifacts resolvable (Maven Central, or `sbt publishLocal` in `../ankka`)
- For tier 5: the `ankka` CLI on PATH, a kind cluster from `../ankka` (`just up`), and `ankka login` as the `dev` user

## Tier 1 — models and runner, no runtime (seconds)

```bash
sbt modelSpi/test employeeScheduling/test vehicleRouting/test   # codecs round-trip, validation, weights, ablation, a real solve
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
| `SecurityHttpSuite` | — | runner token required; operator-only routes; API keys and tokens do not cross APIs; the inflation cap |

Proves: SC-001 (demo → feasible < 60 s), SC-002, SC-003, SC-005, SC-009, SC-010, SC-011, SC-013, SC-014, SC-016, SC-018 (a log scan over the suite's captured output finds no input or solution content).

## Tier 4 — the pool: kill a worker mid-solve (about two minutes)

```bash
sbt 'solver/testOnly satisfactory.solver.PoolResilienceSuite satisfactory.solver.SolverServiceSuite'
```

Starts `api` under `AnkkaTestKit` with no runner of its own, then workers in the same JVM speaking the runner protocol over HTTP against `api`'s bound port, each with one slot. `SolverServiceSuite` also starts the real `solver` service (an ankka service with no components) and solves through it. Submits four datasets at `spentLimit = PT60S`; after 10 s, stops one runtime abruptly (no drain); after 20 s, drains the other through `/ops/workers/{id}/drain` and starts a third. Asserts: every dataset reaches `SOLVING_COMPLETED`; each dataset's final score ≥ its score at the moment its worker died; a replayed report from the dead worker's epoch answers 409 and changes nothing; the drained worker's datasets re-queue with `warmStartRef` and resume elsewhere within 60 s; `LeaseLost` count equals the number of kills. Proves: Story 4, Story 10 drain; SC-006, SC-007, SC-008 (worker half), SC-017.

## Tier 5 — deployed on a local ankka (ten minutes)

```bash
sbt api/Docker/publishLocal solver/Docker/publishLocal deployDescriptors   # images tagged with the build's version; descriptors rendered into target/deploy
kind load docker-image $(jq -r .service.image target/deploy/api.json) $(jq -r .service.image target/deploy/solver.json) --name ankka
ankka organizations create satisfactory --name Satisfactory     # whoever is logged in becomes its owner
ankka projects create satisfactory --name Satisfactory -O satisfactory
# Two instances of each, not the descriptors' three: three small api and three large solver instances ask 7.5 CPU on top of the platform, more than a laptop's kind node has.
jq '.service.resources.autoscaling.minInstances = 2' target/deploy/api.json | ankka services apply -f - -p satisfactory
until kubectl get namespace ankka-satisfactory >/dev/null 2>&1; do sleep 1; done   # the project's namespace appears with its first service
kubectl -n ankka-satisfactory create secret generic satisfactory-secrets \
  --from-literal=runner-token="$(openssl rand -base64 32)" \
  --from-literal=secret-key="$(openssl rand -base64 32)" \
  --from-literal=auth-issuer=https://auth.127.0.0.1.sslip.io:8443/realms/ankka \
  --from-literal=auth-jwks-url=http://ankka-keycloak-service.ankka-auth.svc:8080/realms/ankka/protocol/openid-connect/certs
jq '.service.resources.autoscaling.minInstances = 2' target/deploy/solver.json | ankka services apply -f - -p satisfactory
ankka services expose api -p satisfactory                    # https://api-satisfactory.127.0.0.1.sslip.io:8443
```

The secret holds everything `deploy/api.json` and `deploy/solver.json` read by `secretKeyRef`. The `api` pods created before it exists stay in `CreateContainerConfigError` for a few seconds and then start. The issuer is what a token's `iss` says, the gateway address; the keys are fetched over Keycloak's plain in-cluster address, because that hostname resolves to loopback inside a pod and the api trusts no private authority. That is the same split ankka's own control plane uses.

Give `dev` the operator role in the identity provider's console, `https://auth.127.0.0.1.sslip.io:8443/admin/` as `admin` with password `admin`: in realm `ankka`, create the realm role `satisfactory-operator` and assign it to the user `dev`. The next `ankka` command renews the saved access token with the role in it.

The CLI never prints a token, so take the access token `ankka login` saved in `~/.ankka/credentials.json`, keyed by the configured control plane URL. It lives five minutes; any `ankka` command (`ankka whoami`) renews it. A local platform's base domain is `127.0.0.1.sslip.io`, so the exposed service is `api-satisfactory` under it:

```bash
export SAT=https://api-satisfactory.127.0.0.1.sslip.io:8443
ankka whoami >/dev/null && export TOKEN=$(jq -r --arg u "$(ankka config get -o json | jq -r .url)" '.[$u].accessToken' ~/.ankka/credentials.json)
export TENANT=$(curl -sk -X POST $SAT/api/platform/v1/tenants -H "Authorization: Bearer $TOKEN" \
  -d "{\"name\":\"acme\",\"firstAdminSubject\":\"$(ankka whoami -o json | jq -r .subject)\"}" | jq -r .id)
export KEY=$(curl -sk -X POST $SAT/api/platform/v1/tenants/$TENANT/keys -H "Authorization: Bearer $TOKEN" \
  -d '{"label":"ci","role":"read-write"}' | jq -r .key)         # the plaintext key: in this response and nowhere else
curl -sk $SAT/api/models/employee-scheduling/v1/demo-data -H "X-API-KEY: $KEY"
export DATASET=$(curl -sk -X POST "$SAT/api/models/employee-scheduling/v1/schedules?name=smoke" -H "X-API-KEY: $KEY" \
  -H 'Content-Type: application/json' --data-binary @<(curl -sk $SAT/api/models/employee-scheduling/v1/demo-data/SMALL -H "X-API-KEY: $KEY") | jq -r .id)
curl -skN "$SAT/api/models/employee-scheduling/v1/schedules/$DATASET/events" -H "X-API-KEY: $KEY"
```

Expected: `202` with `DATASET_CREATED`; the stream shows improving scores and closes on `SOLVING_COMPLETED` within the profile's limit; `ankka services logs solver -p satisfactory` shows the claim and reports; `kubectl -n ankka-satisfactory delete pod <solver pod>` mid-solve leaves the stream monotonic and the dataset completed. Proves: FR-075, SC-008 (API redeploy: `ankka services apply` with a new image tag during a solve), and the V-list items V1, V3, V4, V5, V6.

## Tier 6 — an ankka application consumes it (about a minute, no deployment)

```bash
sbt ankkaSatisfactory/test
```

`FakeSatisfactorySuite` scripts a submit and a `dataset.completed` webhook; `PlannerWorkflowSuite` runs an `AnkkaTestKit` service with an agent using `SatisfactoryTools.forModel(...)` under `TestModelProvider`, a workflow that submits and `thenPause`s, and an endpoint that verifies the fake's webhook and resumes it. Asserts the workflow reaches its result step with the solution, the submit carried the `ankka-workflow:<id>` tag, a tampered signature is refused, and a duplicate webhook is a no-op. Proves: Story 9; SC-015.

## Tier 5 without a cluster

Tier 5 has not been run: this machine had no kind cluster, and creating one would change the kubectl
context. What stands in for it: both images build (`sbt api/Docker/publishLocal solver/Docker/publishLocal`),
the `api` image was run against compose Postgres and solved a demo dataset submitted with curl, and
`SolverServiceSuite` runs the real `solver` service against `api`.

## Known limits at this tier of ankka

- `SC-012` (2 GiB uncompressed) is enforced as a cap but cannot be exercised on a `small` instance; the 100 MiB compressed case is what tier 5 tests.
- Slots per `large` worker instance = 1 (research R4); the pool's capacity equals its instance count until ankka offers a larger type.
- Through ankka's gateway an event stream is cut after 15 s, Envoy's default route timeout, which
  ankka's operator does not yet override on the `HTTPRoute` (`response_timeout` in the gateway's
  access log, status `200`). The solve continues and the dataset completes; reconnect, or read the
  final state from `/metadata`. Inside the cluster and on a laptop the stream closes on the final event.
- SSE frames are JSON-quoted strings and there are no `id:` frames (research R9); browsers' `EventSource` needs the client library's unwrapping until the ankka follow-up lands.

## Results (2026-09-28, laptop, `sbt test`)

| Tier | Suites | Tests | Wall-clock |
|---|---|---|---|
| 1 | protocol, model SPI, both models, runner | 57 | under 1 min |
| 2 | dataset and tenant entities, config resolver | 28 | seconds |
| 3 | nine HTTP suites, stream source, patch, blob store | 80 | about 7 min |
| 4 | pool resilience, solver service | 4 | about 2.5 min |
| 6 | fake, planner workflow, client | 10 | under 1 min |
| **All** | | **172** | **11 min** |

One failure in that run (the solver service suite expected a one-model catalog after vehicle routing was
added); fixed and rerun green. Tier 5 was not run (see above).
