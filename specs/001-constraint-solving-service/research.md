# Research: Constraint Solving as a Service (satisfactory)

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

Each finding says whether it was verified against the ankka repository at `../ankka` (tag `v0.5.0`, commit `daa5cd9`, read 2026-09-26), against Timefold's published sources and documentation fetched on 2026-09-26, or is an assumption a named task settles at implementation. The list at the end collects the latter. Findings that change what the spec promised are marked **spec impact** and repeated in the plan's summary.

## R1 — Timefold Solver 2.7.0 Community, JDK 21, Apache-2.0

**Verified upstream.** Maven Central lists `ai.timefold.solver:timefold-solver-core` at **2.7.0** (Apache-2.0), with a BOM `ai.timefold.solver:timefold-solver-bom:2.7.0`; the Hello World guide requires **JDK 21+**. DESIGN.md was written against 2.6; nothing in it depends on the minor version.

The quickstarts repository (`TimefoldAI/timefold-quickstarts`, branch `stable`, Apache-2.0) holds `use-cases/employee-scheduling` and `use-cases/vehicle-routing`, both Quarkus applications on `timefold-solver-quarkus` 2.7.0, `maven.compiler.release` 21. Employee scheduling scores with `HardSoftBigDecimalScore`; vehicle routing uses a list variable (`Vehicle.visits`) and a `HardSoftLongScore`.

**Decision**: pin `timefold-solver-core` 2.7.0 through the BOM in the Java model modules; port only each quickstart's `domain/` and `solver/` packages plus the demo-data generator, dropping Quarkus, REST and UI. The Timefold Jackson module (`timefold-solver-jackson`) is used for score (de)serialisation.

**Alternatives considered**: depending on the quickstart artifacts as-is — they are applications, not libraries, and drag in Quarkus; writing models from scratch — the quickstarts are the reference implementations Timefold's own platform models descend from.

## R2 — Score analysis, recommendations and solution diff are Enterprise-only (**spec impact**)

**Verified upstream.** `SolutionManager.java` on `main` documents: "Score analysis is exclusive to Timefold Solver Enterprise Edition", "Solution diff is exclusive to Timefold Solver Enterprise Edition", "Recommendations are exclusive to Timefold Solver Enterprise Edition". The commercial-editions page lists score analysis, the recommendation API, nearby selection, multithreaded solving, partitioned search, constraint profiling, multistage moves and throttled best-solution events as Enterprise. The Community `SolutionManager` still offers `update(solution)` → the solution's `Score`, and `ConstraintWeightOverrides.of(Map[String, Score])` is Community core. The quickstarts call `analyze` only inside a Maven profile named `enterprise`.

API.md §2.3 and spec FR-062/FR-063 (per-constraint weight, score, match count, optional justifications) are `SolutionManager.analyze` on the wire, which the Community edition does not provide.

**Decision**: v1 computes score analysis **by ablation**: score the plan once with the resolved weights (`update`), then once per constraint with that constraint's weight overridden to zero; the constraint's contribution is the difference. The response keeps Timefold's `ScoreAnalysis` shape so clients written against the spec work: `score`, and per constraint `name`, `weight`, `score`; `matchCount` and `matches` are omitted, and `includeJustifications=true` is accepted and answered with a `justifications: "unsupported"` field rather than an error. Cost is `constraints + 1` full score calculations of one solution: for employee scheduling (62 constraints, ~120 shifts) that is tens of milliseconds on the worker; it runs on the worker (for `DATASET_COMPUTED`) and on `api` (for the stateless `POST /score-analysis`), never on the solver's thread.

**Spec amendment required**: FR-062 and Story 7 scenarios 1–2 must drop match counts and justifications, or the feature must carry an Enterprise licence. The plan proceeds with the Community shape; the amendment is listed in the completion report for the owner to make.

**Alternatives considered**: reading `impl` score-director internals that `analyze` is built on — circumvents the licence boundary and is refused on those grounds; `timefold-solver-test`'s `ConstraintVerifier` — a test utility, not a production API; an Enterprise licence — a purchase, not a design decision, and DESIGN.md §9 says it is deferred.

## R3 — Models are Java modules under a small SPI, one jar on both services

**Verified upstream.** `SolverJobBuilder` (Community) offers `withProblemId`, `withProblem`/`withProblemFinder`, `withBestSolutionEventConsumer(Consumer[NewBestSolutionEvent])`, `withFinalBestSolutionEventConsumer`, `withFirstInitializedSolutionEventConsumer`, `withSolverJobStartedEventConsumer`, `withExceptionHandler(BiConsumer[Object, Throwable])`, `withConfigOverride(SolverConfigOverride)`; `SolverConfigOverride` has `withTerminationConfig`, `withTerminationSpentLimit`, `withTerminationUnimprovedSpentLimit`; `SolverManager.terminateEarly(problemId)` and `addProblemChange(problemId, ProblemChange)` exist; `ProblemChange.doChange(workingSolution, ProblemChangeDirector)` with `lookUpWorkingObject` requiring `@PlanningId`.

**Decision**: the SPI of DESIGN.md §6 stands, in Java, with three adjustments from API.md §6 and R2:

- `constraints()` returns `ConstraintInfo(name, description, level: HARD|MEDIUM|SOFT, defaultWeight: long)`; an integer weight `w` from the request becomes a `Score` at the constraint's level (`HardSoftBigDecimalScore.ofSoft(w)` etc.), which is how `ConstraintWeightOverrides.of` wants them.
- `kpis()` and `inputMetrics()` are declared as typed descriptors (`MetricInfo(id, title, description, type, priority, example)`) and computed by `summarize(solution)` / `inputMetrics(problem)`.
- `validationIssueTypes()` returns the catalog; `validate(problem)` returns typed issues. Schema-level validation is JSON Schema (draft 2020-12) supplied by the model as a resource.
- No `analyze`; `score(problem, overrides)` returns the `Score` string (R2's ablation is written once in `model-spi` as a default method over `score`).

Registration is explicit (`ModelCatalog.of(EmployeeScheduling.V1, VehicleRouting.V1)`), and the same `models/*` jars are on `api`'s classpath so submission validation and stateless scoring run there without a worker.

**Alternatives considered**: Scala models — Timefold reads annotations off bean-style classes; DESIGN.md §6 already rejected the per-model friction. Classpath scanning — against ankka's explicit-registration rule.

## R4 — ankka v0.5.0: stack, artifacts, instance types

**Verified in-repo** (`project/Dependencies.scala`, `ankka.g8`, `docs/reference/service-descriptor.md`).

- Scala **3.9.0**, Pekko 1.7.0, Pekko HTTP 1.4.0, jsoniter-scala 2.40.1, munit 1.3.6, testcontainers 1.21.4, sbt 1.12.x, JDK 21 (`eclipse-temurin:21-jre` base image), sbt-native-packager 1.11.7 with `JavaAppPackaging` + `DockerPlugin`.
- Published: `ankka-core`, `ankka-sdk`, `ankka-runtime`, `ankka-http`, `ankka-agent`, `ankka-testkit`, `ankka-controlplane-api`; group `com.thinkmorestupidless`; dependency graph core ← sdk ← runtime ← http / agent ← testkit.
- Instance types, requests = limits: `small` 500m/512Mi, `medium` 1000m/1024Mi, **`large` 2000m/2048Mi, the largest**. `minInstances` is a fixed count; `maxInstances` and `targetCpuPercent` are stored and not acted on.
- Each service gets one Postgres database on the project's single-instance CNPG cluster (`ANKKA_DB_*` injected from Secret `<service>-db`); secrets are created out of band with `kubectl -n ankka-<project> create secret generic …`.
- Split-brain `keep-majority`, `coordinated-shutdown.exit-jvm = on` in the Kubernetes overlay, preStop sleep 5 s, no `terminationGracePeriodSeconds` set (Kubernetes default 30 s), remoting 17355, management 7626, `ankka.ask-timeout = 10s`, passivation 120 s.

**Decision**: the build pins `ankkaVersion = "0.5.0"` and mirrors the template's `build.sbt` for the two service modules. **Slots = cores − 1 = 1 per `large` instance**, so a pool of N instances solves N datasets at a time and each solve has under 2 GiB. That is enough for the quickstart-sized problems in SC-001 and for proving the pool; it is not a production capacity and the plan asks ankka for a larger instance type (DESIGN.md §8 item 4). JVM flags on the solver image: `-XX:MaxRAMPercentage=70 -XX:+UseParallelGC`.

**Alternatives considered**: solving inside `api` — rejected in DESIGN.md §2 for the `keep-majority` reason, which R4's numbers make sharper (a `small` api instance has half a core).

## R5 — The dataset entity in ankka's SDK

**Verified in-repo** (`modules/sdk/.../EventSourcedEntity.scala`, `core/effect/EventSourcedEffect.scala`, `core/CommandError.scala`). `EventSourcedEntity[S, E]` with `emptyState`, `applyEvent`, `currentState`; companion `EventSourcedEntity.Companion[C, S, E](componentId, stateSerializer, eventSerializer)` with `command[I, O](name)(_.m)` and `query[I, O](name)(_.m)` (read-only enforced by type), `snapshotEvery = Some(100)`; effects `persist(...).thenReply`, `reply`, `error(msg, code)` with `ErrorCode.{BadRequest, NotFound, Conflict, Forbidden, Unauthorized, Internal, Unavailable, Timeout}` mapped to 400/404/409/403/401/500/503/504. Serializers are jsoniter-scala via `Codecs.serializer[A]("manifest")`, discriminator `"type"`.

**Decision**: `DatasetEntity` (component id `dataset`, id = dataset id) with the state, events and commands of DESIGN.md §3.3 renamed to the spec's vocabulary (see data-model.md). The three fold rules live in `applyEvent` and the command handlers only:

1. every worker command carries `epoch`; a stale epoch is refused with `Conflict` (409, which the runner treats as "stop this solve");
2. `record-solution` persists `SolutionRecorded` only when the score is strictly better than `best.score` (compared with the model's score comparator, carried as a parsed score in state); a non-improvement replies with the current `seq` and persists nothing;
3. events carry `solutionRef`, `score`, `feasible`, `kpis` (small JSON) — never the body.

The state keeps a ring of the last 50 `(seq, Metadata)` for `updates-since`; `snapshotEvery = Some(50)`.

**Alternatives considered**: a workflow as the orchestrator — DESIGN.md §3.4's reasoning stands; a KV entity — the history is the product (streams, audit, replay).

## R6 — Claiming: view proposes, entity decides, tenant slots are exact

**Verified in-repo** (`modules/sdk/.../View.scala`, `runtime/ViewClient.scala`, docs/build/views.md, docs/concepts/designing-services.md). Views are one source → one Postgres table, queried with `views.forView(Rows).where(sql"...")`, eventually consistent, require `ProjectionRuntime()`. Entities are the only exact check.

**Decision**: `RunnerEndpoint.claim` reads `DatasetRows` for up to 10 candidates (`status = SCHEDULED AND model IN (…) AND NOT tenantPaused ORDER BY priority DESC, submittedAt ASC`), then for each: `TenantEntity.acquire-slot(datasetId)` (exact; refuses at the limit), then `DatasetEntity.lease(workerId)`; on refusal, release the slot and try the next; on 10 refusals answer 204 and the runner retries after a short backoff (long-poll ≤ 8 s inside `ask-timeout`). Slot release is a `Consumer` over dataset events on any terminal event or lease loss (at-least-once; `release-slot` is idempotent by dataset id). A per-tenant pause (Q4) is a flag in `TenantEntity` mirrored onto `TenantRows` and checked exactly in `acquire-slot`.

**Alternatives considered**: counting active datasets from the view — DESIGN.md §3.4: eventually consistent, so a limit of N could admit N+1.

## R7 — Timers: leases, lifetimes, retention

**Verified in-repo** (`sdk/TimedAction.scala`, `runtime/TimerRuntime.scala`, docs/build/timers.md). `TimedAction.Companion(componentId)` with `handler[I](name)(_.m)`; `TimerScheduler.createSingleTimer(name, delay, call)` (same name replaces), `delete`, `exists`; timers persist in Postgres and are polled by a singleton every 1 s; payload cap **1024 bytes**; an erroring handler is retried forever, so handlers must report `done` when nothing is left to do.

**Decision**: one `DatasetTimers` timed action with handlers `expire-lease`, `expire-lifetime`, `expire-retention`, each taking `{datasetId, epoch?}` (well under 1 KiB). `lease` and each `heartbeat` re-arm `lease-<id>` with the TTL (a replace); the handler calls `DatasetEntity.expire-lease(epoch)`, which is a no-op when the epoch moved on or a heartbeat is fresh. `lifetime-<id>` is armed once on `Scheduled`. `retention-<id>` is armed on any final event with the retention period, and the handler deletes blobs then records `Expired`. All three handlers reply `done` on every outcome except a transport failure.

## R8 — Authentication: API keys by hash, Keycloak for the platform API, a runner token

**Verified in-repo** (`http/HttpEndpoint.scala`, `RequestContext.scala`, docs/concepts/tenancy-and-access.md, `specs/008-authenticated-control-plane`). `Acl.Authenticate(RequestContext => AuthDecision)` yields `Allow(Principal) | Unauthenticated(challenge) | Forbidden | Unavailable`; `principal` is readable in handlers; the control plane's JWT verifier (nimbus-jose-jwt, offline JWKS) lives in the unpublished `controlplane` module, so a service verifies tokens itself; the identity provider is the installation's Keycloak and `platform-admin` is a realm role.

**Decision**:

- **Model API**: `X-API-KEY` → SHA-256 → `ApiKeyEntity` (KV entity, id = hash) holding `{tenantId, keyId, role, revoked}`; the ACL calls it through the component client (endpoints run on virtual threads; one entity lookup per request, cached in-process for 5 s keyed by hash with revocation invalidating on the next miss). Revocation is exact on the entity and at most 5 s late through the cache. `TenantEntity` keeps `keyId → {label, createdAt, revokedAt}` for listing; the key itself is shown once at creation.
- **Platform API**: `Authorization: Bearer <Keycloak token>` verified with `com.nimbusds:nimbus-jose-jwt` against `SAT_AUTH_ISSUER`/`SAT_AUTH_JWKS_URL` (the same pattern as ankka's control plane, in a `satisfactory.auth` package, ~200 lines). Subject = identity-provider user id; **platform operator** = realm role `satisfactory-operator` (a second realm role beside `platform-admin`, so running ankka does not imply running satisfactory). Membership is checked on `TenantEntity` on every request; non-members get 404.
- **Runner endpoints** (`/internal/*`): `Acl.AllowIf` comparing `X-Runner-Token` to `SAT_RUNNER_TOKEN` in constant time.
- **Webhook receivers** verify HMAC-SHA256 over the raw body bytes with the subscription secret (R10).

**Alternatives considered**: a view keyed by key hash — revocation would lag the projection; storing keys only in `TenantEntity` — the request does not name the tenant, so lookup by hash needs its own entity.

## R9 — HTTP limits, gzip, SSE on ankka `http`

**Verified in-repo** (`http/HttpServer.scala`: body read `toStrict(bodyTimeout)` at lines 315/376; `sse` marshals `source.map(text => ServerSentEvent(JsonText.encode(text)))` at line 398; `http/reference.conf`: `ankka.http.body-timeout = 10s`, no `max-content-length`; no `decodeRequest`; no `id:` frames, no `Last-Event-ID`, no keep-alive). Envoy Gateway is HTTP/1.1 with no timeout policies configured.

**Decision**:

- `api` sets `pekko.http.server.parsing.max-content-length = 128m` and `ankka.http.body-timeout = 120s` in its own `application.conf`, and reads submit bodies as `Array[Byte]` (a `given FromBody[Array[Byte]]` declared `application/json`, as DESIGN.md §12 verified), gunzipping in the endpoint when `Content-Encoding: gzip`, with a hard cap of 100 MiB compressed / 2 GiB decompressed enforced while inflating. **2 GiB uncompressed cannot be held in a `small` instance's heap**; the cap is enforced but SC-012's 2 GiB case is only satisfiable once a larger instance type exists — recorded as a known limit in quickstart.md, and the 100 MiB compressed limit is the one tested.
- SSE frames are what ankka emits: `data: "<json-string>"` (a JSON-quoted string the client parses twice). `satisfactory-client` hides this. Resumption is `?after=<seq>`; a keep-alive is a `{"heartbeat":true}` data frame every 15 s from the poll source; `follow=lineage` is handled inside the same source. An ankka follow-up issue asks for raw `data:` frames, `id:` and comment frames, after which `Last-Event-ID` works with no change here.
- Gateway idle timeout: not configured, Envoy's default stream idle timeout is 5 min, longer than the 15 s heartbeat; verified at deployment (V-list).

## R10 — Webhooks as a workflow per delivery, a KV entity per delivery for the log

**Verified in-repo** (`sdk/Consumer.scala`, `sdk/Workflow.scala`, `core/effect/WorkflowEffect.scala`, `View.scala` `ChangeSource`: `eventsOf`, `stateOf`, `fromTopic`). Consumers are at-least-once on virtual threads with no SDK HTTP client; workflows have `thenPause(after, onTimeout)`, per-step timeouts and `RecoverStrategy.maxRetries(n).failoverTo(step)`; a view cannot read workflow state.

**Decision**: `DatasetEventFanout` (consumer over `DatasetEntity` events) turns each final event into a `DatasetEvent` (envelope of API.md §2.7) and, for each matching subscription in the tenant (read from `TenantEntity`), starts `WebhookDeliveryWorkflow` with id `<eventId>:<subscriptionId>` (a duplicate start addresses the running workflow, so at-least-once fan-out is safe). Steps: `deliver` (JDK `HttpClient`, 5 s timeout, POST with `X-Satisfactory-Signature` and `X-Satisfactory-Timestamp`) → on non-2xx or timeout `thenPause(10 s)` back to `deliver`, up to 10 attempts → `exhausted` emits `webhook.failing` (Q3) to the tenant's other opted-in subscriptions via the same fan-out, rate-limited by a `lastFailingNotifiedAt` per subscription in `TenantEntity`. Each attempt is recorded by `WebhookDeliveryEntity` (KV, same id) so `WebhookDeliveryRows` (view over `stateOf`) is the delivery log; `retry` on a log entry restarts the workflow. Entries expire after 30 days via `expireAfter`.

**Alternatives considered**: retrying inside the consumer — a dead receiver would hold consumer parallelism for 100 s per event; a timer per attempt — the workflow already journals attempts and handles the pause.

## R11 — Blob store: a table in the api service's own database, plain JDBC

**Verified in-repo** (docs/platform/databases.md: one database per service, `ANKKA_DB_*` from a Secret, init container applies ankka's DDL; declaring `ANKKA_DB_*` yourself means your own database). The runtime uses r2dbc; nothing stops the application from opening its own JDBC pool to the same database.

**Decision**: `PostgresBlobStore` over HikariCP (JDBC, `org.postgresql:postgresql`), table `satisfactory_blobs(ref TEXT PK, dataset_id TEXT, kind TEXT, content_type TEXT, size BIGINT, body BYTEA, created_at TIMESTAMPTZ)` with an index on `dataset_id`, created by the store at startup (`CREATE TABLE IF NOT EXISTS`), called on virtual threads from endpoints and consumers. `BlobStore` is an SPI (`put`, `get`, `delete`, `deleteAll(datasetId)`) with an in-memory implementation for tests and the local single-process mode. Retention per dataset (final + first feasible + last 10) is enforced by the runner endpoint on `record-solution`: after the entity accepts, the endpoint deletes refs the entity reports as evicted. Under 2 GiB per row is Postgres' limit; under tens of MiB is the design's expectation.

## R12 — The solver service: an ankka service with no components

**Verified in-repo** (`runtime/Ankka.scala:24` `RuntimeExtension { name; start(service); stop; readiness; boundAddress; routes }`; readiness = cluster member + HTTP bound; `http: false` in the descriptor). `start` runs after cluster membership.

**Decision**: `solver` is `Ankka.service.withExtension(SolverRuntime(config)).start()` with no registered components and `"http": false`. `SolverRuntime.start` builds one `SolverManager` per catalog model with `parallelSolverCount = slots` and starts `slots` claim loops on virtual threads; `stop` runs the drain of DESIGN.md §6 (terminate all, flush best, `release`) and returns within 20 s so the preStop 5 s + 30 s grace covers it. Solving runs on a dedicated platform-thread `ExecutorService` sized `slots`, never on Pekko dispatchers. `readiness` reports true once the SolverManagers are built. Whether a service with zero components starts is on the V-list; if not, a no-op KV entity is registered.

The `runner` module has no Pekko and no HTTP types: `ControlChannel` is a trait with `claim`, `report`, `heartbeat`, `release`, `complete`, `fail`; `Http` (JDK `HttpClient`) lives in `solver`; `Local` (component client) lives in `api`.

## R13 — Agent tools: the FunctionTool builder cannot take a raw JSON schema today

**Verified in-repo** (`agent/FunctionTool.scala`, `SchemaType.scala`): `FunctionTool.named(..).describedAs(..).param[A](name, desc)` up to three params whose JSON schema derives from a `SchemaType[A]` (`String`, `Boolean`, `Double`, `Int`, `Long`, `Option[A]`, `List[A]`); results need a `ToolOutput[R]`.

**Decision**: `SatisfactoryTools.forModel` builds `solve_<model>` with one `String` parameter `dataset` described as "JSON matching <input schema summary>" plus `name` and `spentLimit`, and validates the JSON against the model's input schema **inside the tool** before submitting, returning the validation errors as the tool result so the agent can correct. `get_best_solution(datasetId)` and `terminate(datasetId)` are plain. If `SchemaType` proves to be an open type class (V-list), a `SchemaType[ModelInput]` that emits the model's schema verbatim replaces the string parameter with no API change. **Spec impact**: FR-071's "parameter schema is the model's input schema" is met by validation, not by schema exposure, until then; an ankka follow-up asks for `param.json(schema)`.

## R14 — Idempotency keys and identifiers

**Verified in-repo** (`sdk/KeyValueEntity`, `expireAfter` on effects). **Decision**: `IdempotencyEntity` (KV, id = `<tenantId>:<sha256(key)>`) stores the dataset id for 24 h (`expireAfter`); the submit endpoint checks it before creating and writes it after. Dataset ids are `ds_<ULID>`; event ids `evt_<ULID>`; worker ids `<pod-name>` or a random ULID locally; `seq` is a per-dataset `Long` from the entity; `epoch` is a per-dataset `Long` incremented on every `lease`.

## R15 — Operator metrics and views

**Verified in-repo** (docs/concepts/observability.md: `/ankka/metrics` on the management port is a window of invocation spans, not application gauges; `RuntimeExtension.routes: Vector[ServedRoute]`). **Decision**: `MetricsExtension` in `api` serves `/metrics` (Prometheus text) from the extension's routes: gauges read from `DatasetRows` and `WorkerRows` counts (queued per model, busy/free slots, workers draining) and counters kept in-process for lease losses, requeues, completions, failures and webhook failures (reset on restart; documented). Cross-tenant operator views are `GET /api/platform/v1/ops/datasets` and `/ops/workers` over the same views, gated on the operator role. Whether extension routes are served on the management port or the HTTP port is on the V-list; either is scrapeable.

## R16 — Testing strategy on ankka's testkit

**Verified in-repo** (`testkit/EventSourcedTestKit.scala`, `AnkkaTestKit`, `TestTransport`, template suites). Entity unit tests need no database; `AnkkaTestKit.start(descriptors, extensions)` starts a Testcontainers Postgres (Docker required); `TestTransport().stub(handle)(fn).client` fakes a component client for endpoint and workflow tests.

**Decision**, by tier:

1. `model-spi`/`models`: JUnit 5 in Java — codecs round-trip, validation catalog, weight mapping, ablation analysis against a hand-computed score; `ConstraintVerifier` for constraint behaviour.
2. `protocol`, `runner`: munit, pure — the throttle (latest-wins, floor, final always sent), the claim loop against a scripted `ControlChannel`.
3. `api` entities: `EventSourcedTestKit` — the fold rules as a property suite (stale epoch refused, non-improvement not persisted, ring bounded, requeue after N losses fails).
4. `api` HTTP: `AnkkaTestKit` with the in-process `ControlChannel.Local` and in-memory blob store — Stories 1–3, 5–8 end to end on one JVM, employee scheduling demo data with `spentLimit = PT5S`.
5. `solver` ↔ `api`: one suite starting `api` under `AnkkaTestKit` and a `SolverRuntime` in the same JVM against `ControlChannel.Http`, killing the runtime mid-solve (Story 4, SC-006/007).
6. `ankka-satisfactory`: `FakeSatisfactory` suite plus a workflow test on `AnkkaTestKit` proving submit → pause → webhook → resume.
7. Deployed: the quickstart's tier 5 on a local ankka (kind) with both descriptors applied.

## Verify at implementation (V-list)

| # | What | Settles |
|---|---|---|
| V1 | An ankka service with zero registered components reaches `Ready` (R12) | else register a no-op KV entity in `solver` |
| V2 | `SchemaType` is an open type class (R13) | tool parameter schema exposure |
| V3 | `RuntimeExtension.routes` are served on the management port or the HTTP port (R15) | where Prometheus scrapes |
| V4 | `pekko.http.server.parsing.max-content-length` and `ankka.http.body-timeout` are honoured from the service's `application.conf` (R9) | body limits |
| V5 | Envoy Gateway's default stream idle timeout against a 15 s heartbeat SSE stream, and whether `Content-Encoding: gzip` passes through untouched (R9) | streaming and upload in a cluster |
| V6 | A `large` instance under one saturated solver thread keeps cluster heartbeats on time for an hour (R4) | pool stability; else raise `slots` to `cores − 2` = 0 and ask for a larger type first |
| V7 | `ProblemChange` in-place patching keeps assignments across a warm start for the vehicle-routing list variable (R3) | whether supersede uses the optimisation or the teardown path for VRP |
| V8 | `SolverManager` with `parallelSolverCount` = slots on a `large` instance with `-XX:MaxRAMPercentage=70` solves both demo datasets without GC pressure (R4) | JVM flags |
| V9 | Whether `HardSoftBigDecimalScore` weight overrides accept integer weights without precision surprises (R3) | weight mapping |

## Findings at implementation (2026-09-28)

Recorded as they were found; each changes a detail above, not a decision.

- **ankka 0.7.1, not 0.5.0.** Maven Central had moved on; `modules/` is identical between the two
  tags (the difference is a TypeScript SDK, a native CLI and CI), so R4–R16 stand.
- **Timefold 2.7 is on Jackson 3** (`tools.jackson`) and `timefold-solver-test` has no 2.x release
  (`ConstraintVerifier` is in core). Timefold core itself has no Jackson dependency, so dataset JSON is
  Jackson 2 (aligned with ankka's databind 2.21) and `json-schema-validator` 1.5.9; Timefold's own
  Jackson module is not used.
- **The quickstart has 8 constraints**, not the 62 of Timefold's commercial model; the catalog lists
  those 8 with camelCase keys (`<key>Weight` on the wire).
- **Weights work as designed** (R3/V9): `ConstraintWeightOverrides` on the solution, found by type;
  `missingRequiredSkillWeight: 5` gives `-5hard`, `0` disables. Ablation contributions sum to the total
  score exactly (`EmployeeSchedulingSuite`).
- **V4 resolved**: ankka reads bodies with `toStrict`, which pekko-http caps with
  `pekko.http.parsing.max-to-strict-bytes` (8m default), separately from
  `pekko.http.server.parsing.max-content-length`. Both are set to 128m in `api`'s `application.conf`.
- **ankka's HTTP layer allows two path parameters per route**, so there is one model endpoint per catalog
  model, prefixed `/api/models/<key>/<version>`, with the entity name as a literal segment.
- **Errors**: ankka renders its own refusals (ACL 401/403, unknown routes) as `{status, error}`; every
  handler answers `ErrorInfo` itself through `Replies.handle`. Its 401 challenge is
  `WWW-Authenticate: Bearer …` even for API keys.
- **Model tests are munit suites in Scala** (in the Java modules' `src/test/scala`) rather than JUnit 5,
  so every module tests with one runner and no extra sbt plugin.
- **Performance seen**: the SMALL demo reaches `0hard` within 1.5 s at about 150,000 score calculations a
  second on a laptop core (spike).

### The V-list, closed

| # | Outcome |
|---|---|
| V1 | **Passed.** An ankka service with no components reaches ready; `SolverServiceSuite` solves through the real `solver` service |
| V2 | **Passed, and better than planned.** `SchemaType` is open: the solve tool's parameter schema is the model's input schema, `$ref`s inlined, and arguments are validated against it before anything is sent |
| V3 | **Settled differently.** `RuntimeExtension.routes` are listed, not served; `/metrics` is an endpoint on the HTTP port behind `satisfactory.metrics-token` |
| V4 | **Passed with a second setting.** `max-content-length` and `pekko.http.parsing.max-to-strict-bytes` are both needed; both are 128m |
| V5 | **Not verified**: needs tier 5 on a cluster (Envoy's idle timeout against the 15 s keep-alive; gzip passing through the gateway) |
| V6 | **Not verified**: needs an hour-long solve on a `large` instance in a cluster |
| V7 | **Passed** for both models by the teardown path: a superseding or patched child warm-starts from the parent's routes/assignments (`LineageHttpSuite`); no in-place `ProblemChange` |
| V8 | **Partly**: both demo datasets solve in-JVM under the test heap (2 GiB); the container flags are unexercised under load |
| V9 | **Passed.** Integer weights on `HardSoftBigDecimalScore` and `HardMediumSoftScore` scale exactly (`EmployeeSchedulingSuite`, `VehicleRoutingSuite`) |
