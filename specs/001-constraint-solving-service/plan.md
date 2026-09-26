# Implementation Plan: Constraint Solving as a Service (satisfactory)

**Branch**: `001-constraint-solving-service` | **Date**: 2026-09-26 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-constraint-solving-service/spec.md`, and the design documents it was derived from, [`DESIGN.md`](../../DESIGN.md) and [`API.md`](../../API.md).

## Summary

Build satisfactory as two ankka services in one project plus two published libraries. `api` holds the truth: an event-sourced `DatasetEntity` per dataset whose fold enforces the three rules that make the product safe (a lease epoch fences stale workers, only strictly better scores are recorded, bodies never enter the journal), a `TenantEntity` for exact concurrency slots, keys, profiles and webhook subscriptions, views for every listing, timers for leases, lifetimes and retention, a workflow per webhook delivery, and thin endpoints that speak Timefold's model API and a platform API authenticated by the installation's Keycloak. `solver` is an ankka service with no components and one `RuntimeExtension` that runs the pull-based runner: claim, solve with Timefold Solver 2.7 Community, throttle, report, heartbeat, drain. Models are Java modules behind a small SPI, compiled into both images. `satisfactory-client` is plain HTTP; `ankka-satisfactory` adds catalog-generated tools, a webhook verifier and a scripted fake.

Two research findings change what the spec literally promised and are called out for the owner:

1. **Score analysis is Enterprise-only in Timefold 2.7** (research R2). v1 keeps the response shape but computes per-constraint contributions by ablation and omits match counts and justifications. FR-062 and Story 7 need that amendment, or an Enterprise licence.
2. **ankka's tool builder takes typed parameters, not a raw JSON schema** (R13). The generated solve tool validates its JSON argument against the model's input schema inside the tool; the schema is exposed to the model through the tool description until ankka can carry it as the parameter schema. FR-071 is met by validation, not exposure.

Build order follows DESIGN.md §10: spike → walking skeleton (in-process runner) → streaming and lineage → the pool → the product surface → the libraries. Each phase is a slice of user stories and is deployable on its own.

## Technical Context

**Language/Version**: Scala 3.9.0 on JDK 21 for `protocol`, `client`, `runner`, `api`, `solver`, `ankka-satisfactory`; Java 21 for `model-spi` and `models/*` (research R3: Timefold reads annotations off bean-style classes). sbt 1.12.x, scalafmt as in ankka.

**Primary Dependencies**: ankka `0.5.0` (`ankka-sdk`, `ankka-runtime`, `ankka-http`, `ankka-agent`, `ankka-testkit`); Timefold Solver `2.7.0` Community via its BOM (`timefold-solver-core`, `timefold-solver-jackson`; `timefold-solver-test` in tests); jsoniter-scala (ankka's codec) in Scala modules, Jackson in Java modules; `com.networknt:json-schema-validator` for draft 2020-12 input schemas; `com.nimbusds:nimbus-jose-jwt` for platform-API tokens (the pattern ankka's control plane uses); HikariCP + `org.postgresql` for the blob table; JDK `HttpClient` everywhere HTTP is spoken outbound. No Pekko in `protocol`, `client`, `runner`, `model-spi`, `models`.

**Storage**: the `api` service's ankka-provisioned Postgres for the journal, snapshots, views, timers and the `satisfactory_blobs` table (research R11); `solver`'s provisioned database is unused. In-memory blob store and `EventSourcedTestKit` for unit tests.

**Testing**: munit for Scala, JUnit 5 for Java; `EventSourcedTestKit` for fold-rule properties; `AnkkaTestKit` (Testcontainers Postgres) for HTTP, pool and library suites; a k3s/kind deployment for the final tier (quickstart tiers 1–6). Tests are serialised as in ankka's build.

**Target Platform**: ankka `v0.5.0` on Kubernetes (Envoy Gateway, CNPG, Keycloak); `sbt api/run` as the whole system locally with docker-compose Postgres.

**Project Type**: a multi-module sbt build: two service images, two published Scala libraries, one published Java SPI, two Java model modules, one spike.

**Performance Goals**: SC-001 feasible demo solution < 60 s; SC-002 submit acceptance < 2 s for ≤ 10 MiB; SC-003 improvement visible < 2 s, ≤ 1 frame/s; SC-005 terminate reflected < 10 s (heartbeat 5 s); SC-006 resume < 60 s after a kill (lease TTL 20 s + claim long-poll); SC-013 fetch < 1 s for ≤ 10 MiB bodies.

**Constraints**: Community edition only, `maxThreadCount` capped at 1, no score-analysis internals (R2); `large` = 2 vCPU / 2 GiB so one solve slot per worker instance (R4); solving on its own platform-thread pool, never Pekko dispatchers; `runner` has no Pekko and no HTTP types (DESIGN.md §11.2b seam rule); `ankka-satisfactory` mounts no endpoint; inputs and solutions never in logs, metrics, webhooks or operator views (spec Q5); requests ≤ 100 MiB compressed, 2 GiB raw cap enforced but not exercisable on `small` (R9).

**Scale/Scope**: ~9 sbt modules, ~120 source files, 8 components in `api`, 26 model-API routes, 30 platform-API routes, 11 runner routes, 2 models (62 + 3 constraints), 6 test tiers. The largest pieces are `DatasetEntity` with its property suite and the HTTP suites; the riskiest are the pool suite (timing) and anything on the V-list.

## Constitution Check

`.specify/memory/constitution.md` is the unfilled template. This repository has no `CLAUDE.md` yet; DESIGN.md §10 says specs are "in the same style ankka uses", so ankka's `CLAUDE.md` principles are adopted as the gate and listed here for the record.

| Principle (from ankka) | How this plan keeps it |
|---|---|
| Effects are inert data; the runtime interprets them | every dataset and tenant rule is a command handler returning an effect; `record-solution` decides "strictly better" from state, and the blob deletion it implies is done by the endpoint from the reply, never inside the handler |
| The entity is the consistency boundary; cross-entity checks are edge checks | claiming: `DatasetRows` proposes, `TenantEntity.acquire-slot` and `DatasetEntity.lease` each decide for their own id (R6); nothing joins two entities inside a handler |
| Never keep an unbounded list in an entity | `ring` is 50, `logs` 200, `retainedSolutionRefs` 12, `TenantEntity.keys`/`profiles`/`subscriptions` bounded by the spec's limits; listings are views |
| Reactions are consumers, processes are workflows, time is a timer | slot release and webhook fan-out are consumers; a delivery with retries is a workflow; leases, lifetimes and retention are timers (R7, R10) |
| Explicit registration, no scanning | `ModelCatalog.of(...)`, `Ankka.service.register(...)` list every component; the same in both services |
| Module dependency direction | `protocol`, `client`, `model-spi`, `models`, `runner` depend on nothing from ankka; `api` and `solver` depend on ankka; `ankka-satisfactory` depends on both and lives here (DESIGN.md §12) |
| Withhold verbs rather than promise restraint | `/internal/*` has its own token; operator routes require a realm role the platform's own admins do not automatically hold; the solver image holds no database credential it uses and no Kubernetes credential at all |
| Verify on a real cluster | quickstart tier 5 deploys both descriptors and kills a solver pod mid-solve |
| Tests must never name an image by literal tag | `deploy/*.json` image tags come from `version.value` at build time; suites use the built image's name |
| Schema evolution is part of the design | manifests and wire names are fixed in data-model.md; events carry everything needed to apply them (`scoreKey`, `evictedRefs`) so replay never recomputes with a model on the classpath |

**One tension, named**: ankka's readiness is cluster membership, and a saturated solver node is exactly what `keep-majority` downs. The plan keeps one core free (slots = cores − 1) and puts V6 on the list; if a `large` instance cannot keep heartbeats on time under one solver thread, the pool waits for a larger instance type rather than solving inside `api`.

No violations.

## Project Structure

### Documentation (this feature)

```text
specs/001-constraint-solving-service/
├── plan.md                 # this file
├── research.md             # R1–R16 and the V-list
├── data-model.md           # entities, events, commands, views, blobs, config resolution, protocol types
├── quickstart.md           # tiers 1–6
├── contracts/
│   ├── model-api.md        # tenant-facing routes, SSE framing, Community score analysis
│   ├── platform-api.md     # tenants, members, keys, limits, profiles, webhooks, operator routes, metrics
│   ├── runner-protocol.md  # /internal/* and worker obligations
│   ├── model-spi.md        # the Java interface a model implements
│   ├── webhook-events.md   # envelope, signing, event types
│   └── libraries.md        # satisfactory-client and ankka-satisfactory
├── checklists/requirements.md
└── tasks.md                # /speckit-tasks output (not created here)
```

### Source Code (repository root)

```text
build.sbt                            # modules below; ankkaVersion = 0.5.0; timefoldVersion = 2.7.0
project/{build.properties, plugins.sbt, Dependencies.scala}
.scalafmt.conf, .githooks/            # copied from ankka

modules/
├── protocol/                        # Scala, no deps beyond jsoniter: public + runner wire types, codecs, ScoreKey
│   └── src/main/scala/satisfactory/protocol/{Metadata,Dataset,Config,Validation,Analysis,Webhooks,Platform,Runner,Codecs}.scala
├── client/                          # Scala, protocol + JDK HttpClient: SatisfactoryClient, ModelClient, SSE unwrapping, awaitFinal
│   └── src/main/scala/satisfactory/client/{SatisfactoryClient,ModelClient,Sse,Errors}.scala
├── model-spi/                       # Java: SolverModel, ConstraintInfo, MetricInfo, IssueType, WeightOverrides, ScoreCodec, JsonSchema, Ablation, ModelCatalog
│   └── src/main/java/satisfactory/spi/*.java
├── models/
│   ├── employee-scheduling/         # Java: domain/, solver/ (ported from the quickstart), EmployeeScheduling.V1, schemas/*.json, DemoData
│   └── vehicle-routing/             # Java: same shape, list variable
├── runner/                          # Scala, protocol + model-spi, no Pekko, no HTTP types
│   └── src/main/scala/satisfactory/runner/{ControlChannel,ClaimLoop,SolveSession,Throttle,Drain,WorkerConfig}.scala
├── api/                             # ankka service [image satisfactory-api]
│   └── src/main/scala/satisfactory/api/
│       ├── Main.scala               # Ankka.service.register(...).withExtension(ProjectionRuntime(), TimerRuntime(), MetricsExtension, HttpServer.of(...))
│       ├── application/
│       │   ├── DatasetEntity.scala          # state, events, fold rules, commands (data-model.md)
│       │   ├── TenantEntity.scala
│       │   ├── ApiKeyEntity.scala, IdempotencyEntity.scala, WorkerEntity.scala, AuditEntity.scala
│       │   ├── DatasetRows.scala, TenantRows.scala, WorkerRows.scala, WebhookDeliveryRows.scala
│       │   ├── SlotReleaser.scala           # consumer: terminal/lease-lost → tenant.release-slot
│       │   ├── DatasetEventFanout.scala     # consumer: final events → DatasetEvent → WebhookDeliveryWorkflow.start
│       │   ├── WebhookDeliveryWorkflow.scala, WebhookDeliveryEntity.scala
│       │   └── DatasetTimers.scala          # expire-lease, expire-lifetime, expire-retention
│       ├── api/
│       │   ├── ApiKeyAcl.scala              # X-API-KEY → ApiKeyEntity → Principal(tenant, role); 5 s cache
│       │   ├── PlatformAcl.scala            # Bearer JWT (nimbus) → Principal(subject, operator)
│       │   ├── ModelsEndpoint.scala         # /api/models/{model}/v1/* — submit, get, list, metadata, input, config, validation, analysis, logs, events, terminate, purge, restore, from-input, from-patch, demo-data, issue types, /model
│       │   ├── AboutMeEndpoint.scala
│       │   ├── PlatformEndpoint.scala       # /api/platform/v1/* tenants, members, keys, limits, profiles, webhooks, deliveries
│       │   ├── OpsEndpoint.scala            # /api/platform/v1/ops/* (operator role)
│       │   ├── RunnerEndpoint.scala         # /internal/* (runner token): claim, phase, solutions, heartbeat, complete, fail, release, blobs, workers
│       │   ├── Bodies.scala                 # FromBody[Array[Byte]], gzip inflate with caps, JSON parse
│       │   └── StreamSource.scala           # updates-since poller → frames, heartbeat, follow=lineage
│       ├── domain/
│       │   ├── ConfigResolver.scala         # model defaults → parent → profile → request → clamps
│       │   ├── Patch.scala                  # JSON Pointer + [field=value] + "-" selection, applied and validated
│       │   ├── Lineage.scala                # from-input / from-patch / supersede orchestration at the edge
│       │   └── Ids.scala                    # ds_/t_/k_/cp_/wh_/evt_ ULIDs
│       ├── blobs/{BlobStore,PostgresBlobStore,InMemoryBlobStore}.scala
│       ├── local/LocalControlChannel.scala  # ControlChannel.Local over the component client; LocalRunner started by Main when SAT_LOCAL_RUNNER=true
│       ├── metrics/MetricsExtension.scala   # /metrics Prometheus text from views + in-process counters
│       └── auth/{TokenVerifier,Principals}.scala
│   └── src/main/resources/application.conf # max-content-length 128m, body-timeout 120s, satisfactory.* settings
│   └── src/test/scala/satisfactory/api/    # DatasetEntitySuite, TenantEntitySuite, ConfigResolverSuite, PatchSuite, *HttpSuite (quickstart tier 3), MetricsSuite
├── solver/                          # ankka service [image satisfactory-solver], http:false
│   └── src/main/scala/satisfactory/solver/{Main,SolverRuntime,HttpControlChannel,SolverPool}.scala
│   └── src/test/scala/satisfactory/solver/PoolResilienceSuite.scala   # quickstart tier 4
├── ankka-satisfactory/              # published library
│   └── src/main/scala/satisfactory/ankka/{SatisfactoryTools,SatisfactoryWebhook,FakeSatisfactory,Tagging}.scala
│   └── src/test/scala/…/{FakeSatisfactorySuite,PlannerWorkflowSuite}.scala          # quickstart tier 6
└── spike/                           # phase 0: Scala main driving one Java model through SolverManager with the throttle

deploy/api.json, deploy/solver.json  # descriptors: api small ×3 http:true; solver large ×3 http:false; SAT_RUNNER_TOKEN from secret satisfactory-secrets
docker-compose.yml                   # Postgres for `sbt api/run`
README.md, DESIGN.md, API.md         # API.md §2.3 and DESIGN.md §6 updated for R2/R3
```

**Structure Decision**: DESIGN.md §7 and §12 fixed this layout and the dependency direction; the plan adds `spike/` (phase 0), `api/local/` (the in-process runner that makes `sbt api/run` the whole system), `api/metrics/` and `api/auth/` (spec Q4 and the platform API's Keycloak tokens), and keeps `runner` free of Pekko and HTTP types so the `solver` service and, later, a dedicated-pod entrypoint wrap the same code. Models and the SPI are Java for the reasons in DESIGN.md §6, confirmed by R1: the quickstarts are Java and Timefold's annotation processing wants bean-style classes.

## Phase ordering (for `/speckit-tasks`)

| Phase | Stories | Delivers |
|---|---|---|
| 0 Spike | — | `spike/`, `model-spi`, `models/employee-scheduling`, `runner` throttle; tier 1 green |
| 1 Walking skeleton | 1 | `protocol`, `DatasetEntity` + suite, `ConfigResolver`, blob store, `ModelsEndpoint` submit/get/metadata/list/validation/demo-data/model, `RunnerEndpoint`, `LocalControlChannel`, `DatasetTimers` lease/lifetime, seeded single tenant + key; tiers 2–3 (Submit) green; `sbt api/run` works |
| 2 Streaming & lineage | 2, 3 | `StreamSource`, events route, terminate, `operation=NONE` + `solve`, `Patch`, `Lineage`, supersede; tier 3 (Stream, Lineage) green |
| 3 The pool | 4, 10 | `solver` service, `HttpControlChannel`, `SolverRuntime`, `WorkerEntity`/rows, drain, `OpsEndpoint`, `MetricsExtension`, `AuditEntity`; tier 4 green; tier 5 deployed |
| 4 The product | 5, 6, 7, 8 | `TenantEntity` full, `ApiKeyEntity`, `PlatformEndpoint`, `PlatformAcl`, profiles, rate limits, `DatasetEventFanout`, `WebhookDeliveryWorkflow`/entity/rows, `webhook.failing`, ablation analysis on both services, purge/restore/retention, `IdempotencyEntity`, `models/vehicle-routing`; tier 3 (Tenant, Webhook, Analysis, Lifecycle) green |
| 5 The libraries | 9 | `client`, `ankka-satisfactory`, `FakeSatisfactory`; tier 6 green; publishing |

## Complexity Tracking

No constitution violations to justify. Three additions that could look like scope and are not:

| Addition | Why it is in this feature |
|---|---|
| `WorkerEntity` and `WorkerRows` | the spec's Q4 operator view and drain need a record of workers; a pull protocol has none otherwise |
| `IdempotencyEntity` | API.md §8 lists `Idempotency-Key` as a deliberate divergence; a KV entity with `expireAfter` is the smallest exact implementation |
| Ablation score analysis in `model-spi` | the only Community-edition way to give per-constraint contributions (R2); written once as a default method, ~40 lines |

## Spec amendments this plan needs (for the owner)

1. FR-062 and Story 7 scenarios 1–2: drop `matchCount` and `matches`/justifications, or add an Enterprise licence to scope (R2).
2. FR-071: "parameter schema is the model's input schema" becomes "the tool validates its dataset argument against the model's input schema and describes the schema to the model" until ankka can carry a raw parameter schema (R13).
3. SC-012: note that the 2 GiB uncompressed case is capped but not exercisable until a larger instance type exists (R9).
4. Assumptions: Timefold Solver 2.7.0 (not 2.6); ankka `v0.5.0`; worker slots = 1 per `large` instance.
