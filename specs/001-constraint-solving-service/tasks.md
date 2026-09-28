# Tasks: Constraint Solving as a Service (satisfactory)

**Input**: Design documents from `/specs/001-constraint-solving-service/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Included. The spec's success criteria name test suites as deliverables (SC-007 replayed-report test, SC-011 authorisation matrix, SC-018 log scan) and quickstart.md defines the six tiers; each story's phase ends with its tier suite. Write a suite before the code it covers where the checkpoint says so, and make it fail first.

**Organization**: Phases follow the plan's build order. Setup and Foundational are the spike and walking-skeleton groundwork every story needs; then one phase per user story in priority order (US1 → US2, US3 → US4, US10 → US5, US6, US7, US8 → US9); then polish, which includes the second catalog model.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1–US10 from spec.md)
- Paths are repository-relative; module layout is in plan.md

## Path Conventions

Multi-module sbt build. Scala under `modules/<module>/src/{main,test}/scala/satisfactory/<pkg>/`, Java under `modules/model-spi/src/main/java/satisfactory/spi/` and `modules/models/<model>/src/main/java/satisfactory/models/<model>/`. Services are `modules/api` and `modules/solver`; libraries are `modules/client` and `modules/ankka-satisfactory`.

---

## Phase 1: Setup (build and repository)

**Purpose**: One sbt build for nine modules, formatted and packaged the way ankka's template is.

- [X] T001 Create `build.sbt` with modules `protocol`, `client`, `modelSpi`, `employeeScheduling`, `vehicleRouting`, `runner`, `api`, `solver`, `ankkaSatisfactory`, `spike`; `ThisBuild / scalaVersion := "3.9.0"`, `javacOptions ++= Seq("--release", "21")`, `Test / fork := true`, `Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)`; `api` and `solver` enable `JavaAppPackaging` + `DockerPlugin` with `dockerBaseImage := "eclipse-temurin:21-jre"`, `dockerUpdateLatest := true`, `solver` adding `-XX:MaxRAMPercentage=70 -XX:+UseParallelGC` to `javaOptions`; the dependency graph of plan.md (`protocol`, `client`, `modelSpi`, `models`, `runner` never depend on ankka)
- [X] T002 [P] Create `project/build.properties` (sbt 1.12.15), `project/plugins.sbt` (sbt-native-packager 1.11.7, sbt-scalafmt, sbt-ci-release), and `project/Dependencies.scala` with `V.ankka = "0.5.0"`, `V.timefold = "2.7.0"`, jsoniter-scala 2.40.1, munit 1.3.6, JUnit 5, `com.networknt:json-schema-validator`, `com.nimbusds:nimbus-jose-jwt`, HikariCP, `org.postgresql:postgresql`, testcontainers 1.21.4, and the ankka artifacts (`ankka-sdk`, `ankka-runtime`, `ankka-http`, `ankka-agent`, `ankka-testkit`)
- [X] T003 [P] Copy `.scalafmt.conf`, `.githooks/pre-commit` and `.gitignore` from `../ankka`; add a `Justfile` with `test`, `run-api`, `images`, `hooks` recipes that each wrap one sbt command
- [X] T004 [P] Create `docker-compose.yml` (postgres:17-alpine, user/password/db `ankka`, port 5432) and the `schema` sbt task that extracts `ankka/ddl/*.sql` from the `ankka-runtime` jar into `target/ddl` for the compose volume, as `../ankka/ankka.g8/src/main/g8/build.sbt` does
- [X] T005 [P] Create `deploy/api.json` (`name: api`, `image` from `version.value`, `runtime: 0.5.0`, `http: true`, env `SAT_RUNNER_TOKEN` and `SAT_AUTH_ISSUER`/`SAT_AUTH_JWKS_URL` from secret `satisfactory-secrets`, `resources.instanceType: small`, `minInstances: 3`) and `deploy/solver.json` (`name: solver`, `http: false`, env `SAT_API_URL: http://api:9000`, `SAT_RUNNER_TOKEN` from the same secret, `instanceType: large`, `minInstances: 3`), rendered by an sbt task `deployDescriptors` so image tags are never literal
- [X] T006 [P] Initialise git (`git init`, first commit) and write a `CLAUDE.md` for this repository stating the module dependency direction, the seam rules (no Pekko or HTTP types in `runner`, no ankka in `protocol`/`client`/`model-spi`/`models`), and the commands from the Justfile

---

## Phase 2: Foundational (SPI, first model, protocol, runner, entity, api skeleton)

**Purpose**: Everything every story stands on: the model SPI and the employee-scheduling model, the wire types, the runner without a runtime, the dataset entity with its fold rules, and an `api` service that starts. Ends with quickstart tiers 1 and 2 green.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Model SPI (Java)

- [X] T007 Create `modules/model-spi/src/main/java/satisfactory/spi/SolverModel.java` and the supporting records `ModelKey`, `Maturity`, `ModelInfo`, `ConstraintInfo(name, description, level, defaultWeight)`, `MetricInfo`, `IssueType`, `Issue`, `ValidationResult`, `DemoDataset`, `PatchPath`, `InvalidDataset`, `InvalidChange` exactly as `contracts/model-spi.md` declares them
- [X] T008 [P] Create `modules/model-spi/src/main/java/satisfactory/spi/JsonSchema.java` wrapping a Jackson `JsonNode` with `validate(JsonNode): List<String>` on `com.networknt` draft 2020-12, and `overridesSchemaFor(List<ConstraintInfo>)` generating one `<name>Weight: integer ≥ 0` property per constraint
- [X] T009 [P] Create `modules/model-spi/src/main/java/satisfactory/spi/{WeightOverrides,ScoreCodec,ScoreKey}.java`: `WeightOverrides` (name → long, `toConstraintWeightOverrides(ScoreCodec, List<ConstraintInfo>)` producing a `Score` at each constraint's level), `ScoreCodec` (parse/format the model's score class, comparator, `toKey(Score): ScoreKey` as level-wise `BigDecimal`s), `ScoreKey` comparable
- [X] T010 Create `modules/model-spi/src/main/java/satisfactory/spi/Ablation.java`: `analyze(model, problem, overrides)` scores once with the resolved weights then once per constraint with that weight zeroed, returning `ScoreAnalysisView { score, constraints[{name, weight, score}] }` (research R2); wire it as `SolverModel.analyze`'s default method
- [X] T011 [P] Create `modules/model-spi/src/main/java/satisfactory/spi/ModelCatalog.java`: `of(SolverModel...)`, `find(registrationKey, version)`, `byEntity(entity)`, `all()`; duplicate keys throw at construction
- [X] T012 Write `modules/model-spi/src/test/java/satisfactory/spi/{JsonSchemaTest,WeightOverridesTest,AblationTest,ModelCatalogTest}.java` (JUnit 5) with a tiny two-constraint test model so `Ablation` is checked against a hand-computed score

### Employee scheduling model (Java, ported from the quickstart)

- [X] T013 Port `domain/{Employee,Shift,EmployeeSchedule}.java` from `TimefoldAI/timefold-quickstarts@stable use-cases/employee-scheduling` into `modules/models/employee-scheduling/src/main/java/satisfactory/models/employeescheduling/domain/`, dropping Quarkus/Jackson-Quarkus annotations, keeping `@PlanningId`, `@PlanningSolution`, `@PlanningEntity`, `@PlanningPin`, `HardSoftBigDecimalScore`, with an Apache-2.0 header naming the origin
- [X] T014 Port `solver/EmployeeSchedulingConstraintProvider.java` to `…/solver/EmployeeSchedulingConstraintProvider.java` and add `Constraints.java` listing every constraint as a `ConstraintInfo` (name, description, level, default weight 1) in the provider's order (62 entries, matching the quickstart OpenAPI `EmployeeScheduleConfigOverrides` names)
- [X] T015 [P] Write `modules/models/employee-scheduling/src/main/resources/satisfactory/models/employee-scheduling/{input,output}.schema.json` (draft 2020-12, derived from `docs/timefold/employee-scheduling-v1.json`'s `EmployeeScheduleInput`/`EmployeeSchedule` schemas, trimmed to the ported domain) and `PatchPaths.java` declaring `/employees`, `/shifts`, `/availabilities` as `[field=value]`-selectable arrays
- [X] T016 Create `…/EmployeeSchedulingCodecs.java`: `decodeProblem` (Jackson → domain, applying `WeightOverrides` via `ConstraintWeightOverrides`), `encodeSolution` (domain → `modelOutput` JSON including assignments), `decodeSolution` (output → domain with assigned shifts pinned so a warm start keeps them), `ScoreCodec` for `HardSoftBigDecimalScore`
- [X] T017 [P] Create `…/EmployeeSchedulingValidation.java`: issue types `ShiftUnknownEmployee`, `ShiftOverlapsUnavailability`, `EmptySchedule`, `DuplicateShiftId`, `RequiredSkillUnknown` with typed fields, `validate(problem)` producing them, and the catalog for `validation-issue-types`
- [X] T018 [P] Create `…/EmployeeSchedulingMetrics.java`: input metrics (`employees`, `shifts`, `pinnedShifts`, `unavailabilities`), KPIs (`assignedShifts`, `unassignedShifts`, `workingTimeFairnessPercentage`, `disruptionPercentage` computed against pinned/previous assignments) with `MetricInfo` descriptors
- [X] T019 [P] Port `rest/DemoDataGenerator.java` to `…/DemoData.java` producing seeded, deterministic `SMALL` and `LARGE` demo datasets with descriptions, tags and suggested config
- [X] T020 Create `…/EmployeeScheduling.java` exposing `V1: SolverModel<EmployeeSchedule>` (key `employee-scheduling`, version `v1`, entity `schedules`, maturity `STABLE`, `baseConfig()` with solution/entity classes and the provider, no termination) and write `modules/models/employee-scheduling/src/test/java/…/{CodecsRoundTripTest,ConstraintProviderTest (ConstraintVerifier),ValidationTest,DemoDataTest,SolveSmokeTest}.java`

### Protocol (Scala, no ankka)

- [X] T021 [P] Create `modules/protocol/src/main/scala/satisfactory/protocol/{Metadata,Dataset,Config,Validation,Analysis,Platform,Webhooks}.scala` with the public types listed in data-model.md "Protocol types" (Timefold field names per API.md, plus `supersededBy` and `seq` on `Metadata`) and jsoniter codecs in `Codecs.scala` (`Codecs.make`, discriminator `type`)
- [X] T022 [P] Create `modules/protocol/src/main/scala/satisfactory/protocol/Runner.scala` with `ClaimRequest`, `ClaimResponse`, `PhaseRequest`, `ReportRequest`, `ReportResponse`, `HeartbeatRequest`, `HeartbeatResponse`, `CompleteRequest`, `FailRequest`, `ReleaseRequest`, `WorkerRegistration` and codecs, exactly as `contracts/runner-protocol.md`
- [X] T023 [P] Create `modules/protocol/src/main/scala/satisfactory/protocol/ScoreKey.scala` (a `List[BigDecimal]` with `Ordering`, parse from the model's key list) and `Ids.scala` (ULID generation, `ds_`/`t_`/`k_`/`cp_`/`wh_`/`evt_` prefixes, `blobRef(datasetId, kind, part)`), with `modules/protocol/src/test/scala/satisfactory/protocol/{CodecsSuite,ScoreKeySuite,IdsSuite}.scala`

### Runner (Scala, no Pekko, no HTTP types)

- [X] T024 Create `modules/runner/src/main/scala/satisfactory/runner/ControlChannel.scala`: trait with `register`, `claim`, `phase`, `report`, `heartbeat`, `complete`, `fail`, `release`, `getBlob`, `putBlob`, each returning `Either[ChannelError, R]` where `ChannelError.Stale` maps the 409 case; plus `WorkerConfig(workerId, slots, heartbeatInterval = 5s, minReportInterval = 1s, models)`
- [X] T025 [P] Create `modules/runner/src/main/scala/satisfactory/runner/Throttle.scala`: latest-wins, at most one emission per interval (floor 250 ms), `flushFinal()` always emits the last pending value; scheduler injected for tests
- [X] T026 Create `modules/runner/src/main/scala/satisfactory/runner/SolveSession.scala`: for one claimed dataset — fetch input (or warm start) by ref, `decodeProblem`/`decodeSolution`, `validate` → `phase(validated|invalid)`, `score` + `inputMetrics` + `analyze` → `phase(computed)` (stop here for `operation=NONE`), `SolverManager.solveBuilder().withProblemId(datasetId).withProblem(...).withConfigOverride(termination).withBestSolutionEventConsumer(throttled → putBlob + report).withFinalBestSolutionEventConsumer(→ analysis blob + complete).withExceptionHandler(→ fail).run()`, a heartbeat loop every 5 s acting on `terminate`/`force`/`drain`, and `Stale` handling (terminateEarly, discard, free slot)
- [X] T027 Create `modules/runner/src/main/scala/satisfactory/runner/ClaimLoop.scala`: one loop per slot on a virtual thread: `claim` (long-poll) → `SolveSession.run` → repeat; backoff 1 s on `204`; stops claiming when `drain` is seen; `Drain.scala` implements terminate-all → flush best → `release(reason)` → deregister within 20 s
- [X] T028 Write `modules/runner/src/test/scala/satisfactory/runner/{ThrottleSuite,ClaimLoopSuite,SolveSessionSuite}.scala` against a scripted in-memory `ControlChannel` and the employee-scheduling model with `spentLimit = PT2S` (quickstart tier 1)

### Spike (phase 0)

- [X] T029 Create `modules/spike/src/main/scala/satisfactory/spike/Main.scala` driving `EmployeeScheduling.V1` through `SolverManager` with the throttle for 10 s and printing the improving score sequence; run it and record the interop findings (V8, V9) in `specs/001-constraint-solving-service/research.md`

### `api` skeleton and shared components

- [X] T030 Create `modules/api/src/main/scala/satisfactory/api/Main.scala` (`Ankka.service.register(<components>).withExtension(ProjectionRuntime(), TimerRuntime(), HttpServer.of(...)).start()` with `sys.addShutdownHook`), `Settings.scala` reading `satisfactory.*` config, and `modules/api/src/main/resources/application.conf` with `pekko.http.server.parsing.max-content-length = 128m`, `ankka.http.body-timeout = 120s`, `satisfactory.{runner-token, lease-ttl = 20s, heartbeat = 5s, max-attempts = 3, retention = 30d, lifetime-ceiling = 12h, ring = 50, bootstrap-key}` with `SAT_*` env overrides
- [X] T031 [P] Create `modules/api/src/main/scala/satisfactory/api/blobs/{BlobStore,InMemoryBlobStore,PostgresBlobStore}.scala`: SPI `put/get/delete/deleteAll(datasetId)`, HikariCP pool from `ANKKA_DB_*`, `CREATE TABLE IF NOT EXISTS satisfactory_blobs(...)` per data-model.md at startup, all calls blocking (virtual threads); `modules/api/src/test/scala/satisfactory/api/BlobStoreSuite.scala` runs both implementations (Postgres via Testcontainers)
- [X] T032 [P] Create `modules/api/src/main/scala/satisfactory/api/api/Bodies.scala`: `given FromBody[Array[Byte]]` (declared `application/json`), `inflateIfGzip(request, bytes)` honouring `Content-Encoding: gzip` with caps 100 MiB compressed / 2 GiB inflated (abort while inflating), JSON parse to `JsonNode` with a 400 `ValidationErrorInfo` on failure; and `Errors.scala` mapping `CommandError` codes and domain errors to `ErrorInfo` responses (400/401/403/404/409/410/429/500)
- [X] T033 [P] Create `modules/api/src/main/scala/satisfactory/api/domain/ConfigResolver.scala`: model defaults → parent config → profile → request → clamps (`maxThreadCount = 1`, `spentLimit ≤ lifetimeCeiling`, weights ≥ 0, termination default `PT30S`/`0.0001`), returning `ResolvedConfig`; `modules/api/src/test/scala/satisfactory/api/ConfigResolverSuite.scala`
- [X] T034 Create `modules/api/src/main/scala/satisfactory/api/application/ApiKeyEntity.scala` (KV, id = SHA-256 hex of the key, state `{tenantId, keyId, role, revoked}`, commands `create`, `revoke`, query `get`) and the minimal `TenantEntity.scala` (id = tenant id; events `TenantCreated`, `KeyCreated`, `KeyRevoked`, `SlotAcquired`, `SlotReleased`, `LimitsChanged`; commands `create`, `create-key`, `revoke-key`, `acquire-slot` refusing at `limits.concurrency`, `release-slot` idempotent, `set-limits`; queries `get`) — the full tenant surface is US5
- [X] T035 Create `modules/api/src/main/scala/satisfactory/api/api/ApiKeyAcl.scala`: `Acl.Authenticate` reading `X-API-KEY`, hashing, `ApiKeyEntity.get` through the component client with a 5 s in-process cache keyed by hash, `Principal(tenantId, keyId, role)`; `Unauthenticated("ApiKey")` when missing/unknown/revoked; a `requireWrite` helper answering 403 for read-only keys; and `RunnerAcl.scala` (`Acl.AllowIf` constant-time comparing `X-Runner-Token` to `satisfactory.runner-token`)
- [X] T036 Create `modules/api/src/main/scala/satisfactory/api/Bootstrap.scala`: on start, if `satisfactory.bootstrap-key` is set and tenant `t_local` does not exist, create it with default limits and a read-write key whose plaintext is that value (local development and tests only; refuses to run when `SAT_AUTH_ISSUER` is set)
- [X] T037 Create `modules/api/src/main/scala/satisfactory/api/application/DatasetEntity.scala` — state, events and `applyEvent` per data-model.md: `Dataset`, `DatasetSpec`, `Best`, `Lease`, the 50-entry ring, `retainedSolutionRefs` (final + first feasible + last 10) with `evictedRefs` on `SolutionRecorded`, status derivation, serializers `dataset`/`dataset-event`, `snapshotEvery = Some(50)`
- [X] T038 Add the command and query handlers to `DatasetEntity.scala`: `create`, `solve`, `request-terminate`, `supersede`, `update-metadata`, `purge`, `restore`, `lease`, `validated`, `computed`, `started`, `active`, `record-solution` (strictly-better rule via `ScoreKey`, reply `Recorded(seq, evictedRefs)` or `NotBetter(seq)`), `heartbeat` (reply `Control(terminate, force, leaseTtl)`, persist `LogsAppended` only with lines), `release`, `complete`, `fail`, `expire-lease` (attempt counting, `Failed` on the 3rd loss, warm start), `expire-lifetime`, `expire-retention`; queries `get`, `metadata`, `updates-since`, `resolved-config`, `validation-result`, `logs`; every worker command refuses a stale epoch with `Conflict` before persisting
- [X] T039 Write `modules/api/src/test/scala/satisfactory/api/DatasetEntitySuite.scala` on `EventSourcedTestKit` as a property suite: stale epoch refused and nothing persisted; equal or worse score → `NotBetter`, nothing persisted; ring never exceeds 50; eviction keeps final + first feasible + last 10; three `LeaseLost` → `Failed` with `best` intact; `supersede` on active persists `Superseded` then `Completed`/`Incomplete`; `request-terminate` on final persists nothing; `expire-lease` after a fresh heartbeat is a no-op (quickstart tier 2)
- [X] T040 [P] Create `modules/api/src/main/scala/satisfactory/api/application/DatasetRows.scala` (view over `eventsOf(DatasetEntity)`, row per data-model.md, `internalQueued` true while re-leasable) with the queries `myDatasets(tenant, statuses, tags, page, size)`, `claimCandidates(models, limit = 10)` ordered priority desc / submittedAt asc, `byDataset(id)`; `modules/api/src/test/scala/satisfactory/api/DatasetRowsSuite.scala`
- [X] T041 [P] Create `modules/api/src/main/scala/satisfactory/api/application/DatasetTimers.scala` (timed action; handlers `expire-lease {datasetId, epoch}`, `expire-lifetime {datasetId}`, `expire-retention {datasetId}` each calling the entity and replying `done` on every outcome except transport failure) and `Timers.scala` helpers `armLease(id, epoch, ttl)`, `armLifetime(id, ceiling)`, `armRetention(id, period)` using `TimerScheduler.createSingleTimer` with names `lease-<id>`, `lifetime-<id>`, `retention-<id>`
- [X] T042 Create the shared test harness `modules/api/src/test/scala/satisfactory/api/ApiFixture.scala`: starts `api` under `AnkkaTestKit` with `InMemoryBlobStore`, the bootstrap tenant and a second tenant with read-write and read-only keys, a JDK `HttpClient` helper with `X-API-KEY`, JSON helpers, `awaitStatus(id, status, timeout)`, and a log capture that later suites scan (SC-018)

**Checkpoint**: `sbt modelSpi/test employeeScheduling/test protocol/test runner/test` and `sbt 'api/testOnly *DatasetEntitySuite *ConfigResolverSuite *BlobStoreSuite *DatasetRowsSuite'` green; `sbt spike/run` prints an improving score sequence.

---

## Phase 3: User Story 1 — Submit a planning problem and get a solution (Priority: P1) 🎯 MVP

**Goal**: Submit → poll → fetch a solution against the employee scheduling model with the runner in-process; validation at both stages; `operation=NONE` scoring; idempotent submit; catalog and demo data.

**Independent Test**: quickstart tier 3 `SubmitHttpSuite`: submit the demo dataset with `spentLimit = PT5S`, poll to `SOLVING_COMPLETED`, fetch a feasible schedule with score, KPIs and input metrics; schema 400; model-invalid → `DATASET_INVALID`; NONE → `DATASET_COMPUTED` then `POST /{id}`; repeated `Idempotency-Key` returns the same id; read-only key 403 on POST.

### Implementation for User Story 1

- [X] T043 [US1] Create `modules/api/src/main/scala/satisfactory/api/api/RunnerEndpoint.scala` (prefix `/internal`, `acl = RunnerAcl`): `POST /workers/{workerId}`, `DELETE /workers/{workerId}` (stubbed until US4's `WorkerEntity`), `POST /leases` (candidates from `DatasetRows.claimCandidates` → `TenantEntity.acquire-slot` → `DatasetEntity.lease`, release the slot on refusal, long-poll ≤ 8 s with 500 ms retries, `204` when nothing; arms the lease timer), `POST /datasets/{id}/phase`, `POST /datasets/{id}/solutions` (entity first, then `BlobStore.delete(evictedRefs)`), `POST /datasets/{id}/heartbeat` (re-arms the lease timer), `POST /datasets/{id}/complete`, `POST /datasets/{id}/fail`, `POST /datasets/{id}/release`, `PUT/GET /blobs/{ref}` (ref must start with a dataset id the worker holds)
- [X] T044 [P] [US1] Create `modules/api/src/main/scala/satisfactory/api/application/SlotReleaser.scala`: consumer over `DatasetEntity` events calling `TenantEntity.release-slot(datasetId)` on `Completed`, `Incomplete`, `Failed`, `Invalid`, `Computed` (operation NONE) and `LeaseLost`; idempotent
- [X] T045 [US1] Create `modules/api/src/main/scala/satisfactory/api/local/LocalControlChannel.scala` implementing `ControlChannel` over the component client and `BlobStore` directly, and `LocalRunner.scala` starting `ClaimLoop`s with `slots = 1` when `satisfactory.local-runner = true` (default for `sbt api/run` and tests); wire into `Main`
- [X] T046 [P] [US1] Create `modules/api/src/main/scala/satisfactory/api/application/IdempotencyEntity.scala` (KV, id `<tenantId>:<sha256(key)>`, state `{datasetId, createdAt}`, `expireAfter(24h)`, commands `put`, query `get`)
- [X] T047 [US1] Create `modules/api/src/main/scala/satisfactory/api/domain/Submission.scala`: the submit pipeline shared by submit, `from-input` and `from-patch` — parse body, schema-validate with the model (400 with details), resolve config, check `Idempotency-Key`, `BlobStore.put(input)`, `DatasetEntity.create`, arm the lifetime timer, `IdempotencyEntity.put`, return `Metadata`; rate-limit hook left as a no-op until US5
- [X] T048 [US1] Create `modules/api/src/main/scala/satisfactory/api/api/ModelsEndpoint.scala` (prefix `/api/models`, `acl = ApiKeyAcl`), resolving `{model}` through `ModelCatalog` and `{entity}` to the model's entity name: `POST /{model}/v1/{entity}` (query `name`, `operation`, `configurationId`, `priority`, `tags`; `Idempotency-Key`; gzip via `Bodies`) → 202, `POST /{model}/v1/{entity}/{id}` (solve after NONE), `GET /{model}/v1/{entity}/{id}` (metadata + `modelOutput` from the best blob + `inputMetrics` + `kpis`), `GET …/{id}/metadata`, `GET …/{id}/input`, `GET …/{id}/model-request`, `GET …/{id}/config`, `GET …/{id}/validation-result`, `GET …/{id}/logs`, `GET /{model}/v1/{entity}` (list via `DatasetRows`, `status`/`tag`/`page`/`size`), with 404 for another tenant's id and 403 for read-only keys on writes
- [X] T049 [P] [US1] Add to `ModelsEndpoint.scala`: `GET /{model}/v1/demo-data`, `GET …/demo-data/{id}`, `GET …/demo-data/{id}/input`, `GET /{model}/v1/model` (`ModelDescriptor`: schemas, constraints, KPI and input-metric descriptors, issue types, maturity, version, entity), `GET /{model}/v1/{entity}/validation-issue-types` and `…/validation-issue-types/{code}`
- [X] T050 [P] [US1] Create `modules/api/src/main/scala/satisfactory/api/api/AboutMeEndpoint.scala`: `GET /api/aboutme` → `WhoAmI { tenantId, tenantName, keyId, role, queuePaused }`
- [X] T051 [US1] Register `DatasetEntity`, `TenantEntity`, `ApiKeyEntity`, `IdempotencyEntity`, `DatasetRows`, `DatasetTimers`, `SlotReleaser` and the three endpoints in `Main.scala`; run `sbt api/run` against compose Postgres and complete a demo solve with curl; write the local walkthrough into `README.md` "Run it"
- [X] T052 [US1] Write `modules/api/src/test/scala/satisfactory/api/SubmitHttpSuite.scala` on `ApiFixture` covering Story 1 scenarios 1–10 and SC-001, SC-002, SC-013 (fetch during solve < 1 s), plus a gzip-encoded submit and a body over 100 MiB compressed refused with 413

**Checkpoint**: `sbt 'api/testOnly *SubmitHttpSuite'` green; `sbt api/run` is the whole system. MVP deliverable.

---

## Phase 4: User Story 2 — Follow progress live and stop when it is good enough (Priority: P2)

**Goal**: A resumable, monotonic, throttled metadata stream with keep-alives, and terminate-keeps-best.

**Independent Test**: quickstart tier 3 `StreamHttpSuite`: frames strictly improve; `?after=` exact; too-old `after` → `skipped`; final → 410; heartbeat frame within 15 s; terminate → `SOLVING_COMPLETED` with best kept; terminate before any solution → `SOLVING_INCOMPLETE`; terminate on final returns unchanged.

### Implementation for User Story 2

- [X] T053 [US2] Create `modules/api/src/main/scala/satisfactory/api/api/StreamSource.scala`: a `Source[String, ?]` that polls `DatasetEntity.updates-since(lastSeq)` every 500 ms, emits `{"seq","metadata"}` frames coalesced to ≤ 1/s, `{"skipped": n}` on the first frame when `after+1` fell out of the ring, `{"heartbeat":true}` every 15 s of silence, filters by `status`, and completes after a final-status frame; `modules/api/src/test/scala/satisfactory/api/StreamSourceSuite.scala` with a stubbed component client (`TestTransport`)
- [X] T054 [US2] Add `GET /{model}/v1/{entity}/{id}/events` to `ModelsEndpoint.scala` via `sse(...)` with query `after`, `status` (repeatable), `follow` (lineage handled in US3), answering 410 when the dataset is final and `after` is absent or ≥ the final seq
- [X] T055 [US2] Add `DELETE /{model}/v1/{entity}/{id}` (query `force`) to `ModelsEndpoint.scala` calling `request-terminate` and returning the `DatasetResponse`; ensure `SolveSession` (T026) acts on `Control.terminate` within one heartbeat (`terminateEarly` → final report → `complete(reason = terminated)`) and on `force` (skip the final report)
- [X] T056 [US2] Write `modules/api/src/test/scala/satisfactory/api/StreamHttpSuite.scala` covering Story 2 scenarios 1–10 and SC-003, SC-004, SC-005, using `spentLimit = PT20S` and a client that disconnects and resumes

**Checkpoint**: `sbt 'api/testOnly *StreamHttpSuite *StreamSourceSuite'` green.

---

## Phase 5: User Story 3 — Change the problem without starting over (Priority: P2)

**Goal**: `from-input`, `from-patch`, lineage ids, supersede-on-active with warm start, `follow=lineage`, disruption KPI.

**Independent Test**: quickstart tier 3 `LineageHttpSuite`: derive by input and by patch from a completed parent (child starts from the parent's assignments, `disruptionPercentage` present); bad patch path → 400 naming the op index; derive from an active parent → parent `supersededBy`, child warm-started; `follow=lineage` continues into the child; solved-select on a parent with no solution → 400.

### Implementation for User Story 3

- [X] T057 [P] [US3] Create `modules/api/src/main/scala/satisfactory/api/domain/Patch.scala`: JSON Pointer with `[field=value]` array selection and `-` append, ops `add`/`remove`/`replace`, applied to a `JsonNode` copy, errors carrying the op index; `modules/api/src/test/scala/satisfactory/api/PatchSuite.scala`
- [X] T058 [US3] Create `modules/api/src/main/scala/satisfactory/api/domain/Lineage.scala`: `fromInput(parent, select, config, opts)` and `fromPatch(parent, patch, select, config, opts)` — read the parent's input or best solution blob (400 `no-solution` when SOLVED is asked of a dataset without one), apply the patch, schema-validate, resolve config with the parent layer, `Submission` with `parentId`/`originId`/`select`/`patchRef`, and when the parent is active call `DatasetEntity.supersede(childId)` and pass `warmStartRef = parent.best.solutionRef` to the child's `create`
- [X] T059 [US3] Add `POST /{model}/v1/{entity}/{id}/from-input` (`select` default UNSOLVED, body `{config?}`) and `POST …/{id}/from-patch` (`select` default SOLVED, body `{patch, config?}`) to `ModelsEndpoint.scala`
- [X] T060 [US3] Extend `StreamSource.scala` for `follow=lineage`: on a `Superseded` parent emit its final frame, then switch to the child's `updates-since` from seq 0 without closing
- [X] T061 [US3] Extend `SolveSession.scala` and `ClaimResponse` handling so a claim carrying `warmStartRef` and a `patchRef` decodes the solution (pinned) and applies the patch through `decodeSolution` of the patched output; implement `decodeChange` in `EmployeeSchedulingCodecs.java` for the in-place optimisation (V7) or leave the default "unsupported → teardown path" and record which in research.md
- [X] T062 [US3] Write `modules/api/src/test/scala/satisfactory/api/LineageHttpSuite.scala` covering Story 3 scenarios 1–9 and SC-014

**Checkpoint**: `sbt 'api/testOnly *LineageHttpSuite *PatchSuite'` green.

---

## Phase 6: User Story 4 — Solving survives worker failure and deployments (Priority: P3)

**Goal**: The `solver` service and the HTTP control channel; leases, fencing, warm-start requeue, drain on SIGTERM, lifetime ceiling; deployable to ankka.

**Independent Test**: quickstart tier 4 `PoolResilienceSuite` (kill one runtime, drain another, replay a stale report → 409, every dataset completes, no score regression, `LeaseLost` count equals kills) and tier 5 on kind.

### Implementation for User Story 4

- [X] T063 [P] [US4] Create `modules/api/src/main/scala/satisfactory/api/application/WorkerEntity.scala` (KV, id = worker id, state `{models, slots, busy, draining, lastSeenAt}`, commands `register`, `heartbeat`, `drain`, `undrain`, `deregister`) and `WorkerRows.scala` (view over `stateOf`, `stale` when `lastSeenAt` > 60 s)
- [X] T064 [US4] Wire `WorkerEntity` into `RunnerEndpoint.scala`: `POST /workers/{id}` registers/refreshes and replies `{drain}`, every `ClaimResponse` and `HeartbeatResponse` carries `drain` from the entity, `busy` is updated on lease/complete/fail/release
- [X] T065 [P] [US4] Create `modules/solver/src/main/scala/satisfactory/solver/HttpControlChannel.scala` implementing `ControlChannel` with JDK `HttpClient` against `SAT_API_URL` with `X-Runner-Token`, mapping 409 → `ChannelError.Stale`, 204 → `None` on claim, raw bytes for blobs
- [X] T066 [US4] Create `modules/solver/src/main/scala/satisfactory/solver/{Main,SolverRuntime,SolverPool}.scala`: `Ankka.service.withExtension(SolverRuntime(config)).start()` with no components (V1; fall back to a no-op KV entity if needed); `SolverRuntime.start` builds one `SolverManager` per catalog model with `parallelSolverCount = slots` (`slots = max(1, cores − 1)`), a fixed platform-thread pool for solving, registers the worker, starts `ClaimLoop`s; `stop` runs `Drain` within 20 s; `readiness` true once managers are built; worker id from `HOSTNAME` or `local-<ULID>`
- [X] T067 [US4] Verify `expire-lease` in `DatasetEntity.scala` (T038) and the timer re-arm on heartbeat (T043) end to end: a worker whose heartbeats stop is re-queued with `warmStartRef` after the TTL, attempts increment, the third loss fails the dataset; and `expire-lifetime` finalises an over-long solve — add the two cases to `DatasetEntitySuite.scala` if missing
- [X] T068 [US4] Add `Dockerfile`-level settings in `build.sbt` for `solver` (`javaOptions` from T001, `dockerExposedPorts` empty, `dockerEntrypoint` with `-Dsatisfactory.slots` override) and confirm `sbt solver/Docker/publishLocal` and `sbt api/Docker/publishLocal` build
- [X] T069 [US4] Write `modules/solver/src/test/scala/satisfactory/solver/PoolResilienceSuite.scala` per quickstart tier 4: `api` under `AnkkaTestKit` with `local-runner = false`, two `SolverRuntime`s over `HttpControlChannel`, four datasets at `PT60S`, abrupt stop of one runtime, replay of its last report (expect 409), drain of the other through the entity, assertions for SC-006, SC-007, SC-017 and the `LeaseLost` count
- [ ] T070 [US4] **Not done — no local ankka cluster; creating one would switch the kubectl context (currently a production GKE cluster). Images build and the api image was smoke-tested against compose Postgres.** Deploy per quickstart tier 5 on a local ankka (`../ankka` `just up`): create the project and secret, apply both descriptors, expose `api`, run the curl walkthrough, delete a solver pod mid-solve, redeploy `api` with a new tag mid-solve (SC-008); record V1, V3, V4, V5, V6 outcomes in `research.md`'s V-list

**Checkpoint**: `sbt 'solver/testOnly *PoolResilienceSuite'` green; both images run on kind and a pod kill leaves the stream monotonic.

---

## Phase 7: User Story 10 — Operate the service (Priority: P3)

**Goal**: Keycloak-authenticated platform API foundation, operator views across tenants, force-terminate, worker drain/undrain, tenant queue pause/resume, audit trail, Prometheus metrics.

**Independent Test**: quickstart tier 3 `OperatorHttpSuite`: `/ops/datasets` rows carry no input or solution content; non-operator refused; pause → nothing of that tenant starts while the other's does; drain → worker claims nothing and its solves re-queue; force-terminate finalises with best; audit rows; `/metrics` text has the named series.

### Implementation for User Story 10

- [X] T071 [US10] Create `modules/api/src/main/scala/satisfactory/api/auth/{TokenVerifier,Principals}.scala`: nimbus JWKS cache from `SAT_AUTH_JWKS_URL` with refresh, verify `iss` = `SAT_AUTH_ISSUER`, `exp`, signature; `Principal(subject, operator = realm roles contains "satisfactory-operator")`; and `api/PlatformAcl.scala` (`Acl.Authenticate` on `Authorization: Bearer`, `Unauthenticated("Bearer")` otherwise); a test signer in `modules/api/src/test/scala/satisfactory/api/TestTokens.scala`
- [X] T072 [P] [US10] Add `pause-queue`/`resume-queue` commands and `queuePaused` state to `TenantEntity.scala`; make `acquire-slot` refuse while paused; surface `queuePaused` on `WhoAmI` and on `Metadata` for queued datasets; add `tenantPaused` to `claimCandidates` in `RunnerEndpoint.scala` by checking `TenantEntity.get` per candidate tenant (cached 2 s)
- [X] T073 [P] [US10] Create `modules/api/src/main/scala/satisfactory/api/application/AuditEntity.scala` (event sourced, id `ops`, events `OperatorActed(subject, action, target, at)`, bounded query `recent(limit ≤ 500)`)
- [X] T074 [US10] Create `modules/api/src/main/scala/satisfactory/api/api/OpsEndpoint.scala` (prefix `/api/platform/v1/ops`, `acl = PlatformAcl` + operator role check → 403): `GET /datasets` (from `DatasetRows`, filters `tenantId`/`model`/`status`, paged, metadata only), `GET /workers` (from `WorkerRows`), `POST /datasets/{id}/terminate` (force), `POST /workers/{id}/drain` and `/undrain`, `POST /tenants/{id}/queue/pause` and `/resume`, `GET /audit`; every mutation writes `AuditEntity`
- [X] T075 [P] [US10] Create `modules/api/src/main/scala/satisfactory/api/metrics/MetricsExtension.scala`: a `RuntimeExtension` serving `/metrics` in Prometheus text (`satisfactory_queued{model}`, `satisfactory_slots{state}`, `satisfactory_workers{state}` from views; `satisfactory_lease_losses_total`, `satisfactory_requeues_total`, `satisfactory_solves_total{outcome}`, `satisfactory_webhook_failures_total` from an in-process `Counters` object incremented by the entity-facing endpoints and consumers); document where it is served once V3 is known
- [X] T076 [US10] Write `modules/api/src/test/scala/satisfactory/api/OperatorHttpSuite.scala` covering Story 10 scenarios 1–7 with two tenants solving, an operator token and a member token from `TestTokens`

**Checkpoint**: `sbt 'api/testOnly *OperatorHttpSuite'` green.

---

## Phase 8: User Story 5 — Administer a tenant: keys, limits, profiles (Priority: P4)

**Goal**: Tenants created by operators, membership, keys with two roles, limits, priority queueing, rate limits, configuration profiles by id or name, full cross-tenant isolation.

**Independent Test**: quickstart tier 3 `TenantHttpSuite`: create tenant + two keys; concurrency 1 queues the second submit and starts it in priority order; profile weight visible in resolved config; `maxThreadCount` capped; rate limit → 429; read-only key 403; membership roles enforced; cross-tenant 404 matrix over every dataset, key, profile and webhook route (SC-011).

### Implementation for User Story 5

- [X] T077 [US5] Extend `TenantEntity.scala` to the full data-model.md shape: members (`add-member`, `remove-member` refusing the last admin, `change-member-role`), `membership(subject)` query, profiles (`create/update/delete-profile` ≤ 50 per model, `profile(modelKey, idOrName)` with the synthesised read-only `standard`), `take-submit-token` (token bucket at `limits.submitPerMinute`), `set-limits` bounds (admins may lower, operators may raise); `modules/api/src/test/scala/satisfactory/api/TenantEntitySuite.scala`
- [X] T078 [P] [US5] Create `modules/api/src/main/scala/satisfactory/api/application/TenantRows.scala` (view over `TenantEntity` events: `tenantId, name, memberSubjects, queuePaused, concurrency, activeSlots`) with `tenantsFor(subject)` and `all()`
- [X] T079 [US5] Create `modules/api/src/main/scala/satisfactory/api/api/PlatformEndpoint.scala` (prefix `/api/platform/v1`, `acl = PlatformAcl`, membership resolved per tenant route, 404 for non-members): tenants (`POST` operator-only with `firstAdminSubject`, `GET` list scoped, `GET /{tenantId}`), members (list/add/patch/delete, admin for writes), `GET /whoami`, keys (`GET`, `POST` → `ApiKeyEntity.create` then `TenantEntity.create-key`, plaintext once; `DELETE` → `ApiKeyEntity.revoke` first), `GET/PUT /limits`
- [X] T080 [US5] Add profile routes to `PlatformEndpoint.scala` (`GET/POST /tenants/{t}/models/{model}/configurations`, `GET/PUT/DELETE …/{configurationId}`, `standard` → 405 on write, 51st → 409) and make `ConfigResolver.scala` accept `configurationId` by id or name through `TenantEntity.profile`, applying model maximums last; extend `ConfigResolverSuite.scala`
- [X] T081 [US5] Wire rate limiting into `Submission.scala` (`take-submit-token` → 429 `rate-limited`) and priority ordering into `claimCandidates` (already priority desc / submittedAt asc; verify with two priorities in the suite); store `priority` from `POST /{id}` re-solve
- [X] T082 [US5] Write `modules/api/src/test/scala/satisfactory/api/TenantHttpSuite.scala` covering Story 5 scenarios 1–13, SC-009, SC-010 and the SC-011 authorisation matrix (every route × two tenants × {read-only, read-write, member, admin, operator, none})

**Checkpoint**: `sbt 'api/testOnly *TenantHttpSuite *TenantEntitySuite'` green.

---

## Phase 9: User Story 6 — Receive results by webhook (Priority: P4)

**Goal**: Subscriptions with filters and signing; final-status events; durable delivery with Timefold's timing; delivery log with retry; `webhook.failing` system event.

**Independent Test**: quickstart tier 3 `WebhookHttpSuite`: one signed notification per final status with envelope fields and links; filters exclude non-matching; receiver down 60 s → still delivered; duplicate identifiable by `id`; exhausted → `webhook.failing` to the other subscription, at most once per two hours; log entries with per-entry retry; no solution in any payload.

### Implementation for User Story 6

- [X] T083 [P] [US6] Add subscriptions to `TenantEntity.scala` (`create/update/delete-subscription`, `rotate-secret`, secret stored hashed for verification display and encrypted with `satisfactory.secret-key` for signing, `subscriptions-for(eventType, dataset metadata)` applying `events` and `filters`, `note-failing-notified(subscriptionId)` with the two-hour rule) and the routes `GET/POST /tenants/{t}/webhooks`, `GET/PUT/DELETE …/{id}`, `POST …/{id}/rotate-secret` in `PlatformEndpoint.scala`
- [X] T084 [P] [US6] Create `modules/protocol/src/main/scala/satisfactory/protocol/Signing.scala` (HMAC-SHA256 base64 over body or path, `X-Satisfactory-Signature`/`-Timestamp`/`-Event`/`-Delivery` headers, `{hmac_signature}`/`{hmac_timestamp}` substitution in custom headers, verification with a 5-minute skew) shared by the service and `ankka-satisfactory`; `SigningSuite.scala`
- [X] T085 [US6] Create `modules/api/src/main/scala/satisfactory/api/application/WebhookDeliveryWorkflow.scala` (id `<eventId>:<subscriptionId>`; steps `deliver` via JDK `HttpClient` 5 s timeout → on non-2xx or timeout `thenPause(10 s)` back to `deliver` up to 10 attempts → `exhausted`; `start` idempotent; `retry` command; settings per data-model.md) and `WebhookDeliveryEntity.scala` (KV log entry updated after each attempt, `expireAfter(30 days)`) plus `WebhookDeliveryRows.scala` (view over `stateOf`)
- [X] T086 [US6] Create `modules/api/src/main/scala/satisfactory/api/application/DatasetEventFanout.scala`: consumer over `DatasetEntity` events mapping `Computed` (NONE only), `Invalid`, `Completed`, `Incomplete`, `Failed` to `DatasetEvent` envelopes (`evt_` id derived deterministically from dataset id + event type + seq so redelivery is a duplicate start), reading matching subscriptions from `TenantEntity`, starting one workflow per subscription; and the `webhook.failing` path from the workflow's `exhausted` step through the same fan-out to the tenant's other subscriptions
- [X] T087 [US6] Add `GET /tenants/{t}/webhooks/deliveries` (paged, filters) and `POST …/deliveries/{id}/retry` to `PlatformEndpoint.scala`; increment `satisfactory_webhook_failures_total` on exhaustion
- [X] T088 [US6] Write `modules/api/src/test/scala/satisfactory/api/WebhookHttpSuite.scala` with an in-process receiver that can be paused, covering Story 6 scenarios 1–8 and SC-009

**Checkpoint**: `sbt 'api/testOnly *WebhookHttpSuite *SigningSuite'` green.

---

## Phase 10: User Story 7 — Understand and trust the result (Priority: P4)

**Goal**: Per-dataset and stateless score analysis (Community shape), input scoring at `DATASET_COMPUTED`, typed KPIs and metrics, issue catalog, logs.

**Independent Test**: quickstart tier 3 `AnalysisHttpSuite`: stateless analysis of the demo input and per-dataset analysis after the solve both list every constraint with weight and score contribution; `includeJustifications=true` answers `justifications: "unsupported"`; NONE dataset's `score`/`kpis`/`inputMetrics` reflect the submitted plan; issue types listed and by code; logs returned to the owning tenant and never contain input content.

### Implementation for User Story 7

- [X] T089 [US7] Have `SolveSession.scala` write the ablation analysis as an `analysis` blob at `phase(computed)` (input plan) and at `complete` (final best), passing `analysisRef` in `PhaseRequest`/`CompleteRequest`; store it on the entity (`Computed.analysisRef`, `Completed.analysisRef`)
- [X] T090 [US7] Add `GET /{model}/v1/{entity}/{id}/score-analysis` (serve the latest analysis blob; `includeJustifications` accepted) and `POST /{model}/v1/{entity}/score-analysis` (stateless: schema-validate, resolve config with `configurationId`, `decodeProblem`, `Ablation.analyze` on a bounded platform-thread pool in `api`, nothing persisted) to `ModelsEndpoint.scala`
- [X] T091 [US7] Ensure `LogsAppended` lines from the worker (solver phase transitions, termination reason, score calculation speed) never include entity data: add a `LogHygiene` filter in `SolveSession.scala` and `ModelsEndpoint.scala` that rejects lines longer than 512 chars or containing `{`
- [X] T092 [US7] Write `modules/api/src/test/scala/satisfactory/api/AnalysisHttpSuite.scala` covering Story 7 scenarios 1–7

**Checkpoint**: `sbt 'api/testOnly *AnalysisHttpSuite'` green.

---

## Phase 11: User Story 8 — Manage datasets over their lifetime (Priority: P4)

**Goal**: Listing filters, metadata editing with limits, full request and config inspection, purge with restore window, retention expiry with physical deletion, log/metric hygiene proven.

**Independent Test**: quickstart tier 3 `LifecycleHttpSuite`: list by tag and status paged; rename/retag with 255/100/unique limits; purge refused while solving; purged → 410 on bodies, metadata still listed; restore within the window; expiry deletes blobs and refuses restore; SC-018 scan of captured logs, metrics text, webhook payloads and ops rows finds no input or solution content.

### Implementation for User Story 8

- [X] T093 [US8] Add `PATCH /{model}/v1/{entity}/{id}/metadata` (`{name?, tags?}` → `update-metadata`, 400 on limits or purged), `DELETE …/{id}/purge` (400 while solving; entity `purge`; blobs kept until the restore window closes), `PUT …/{id}` (restore; 404 after expiry) to `ModelsEndpoint.scala`; bodies answer 410 while purged
- [X] T094 [US8] Implement `expire-retention` end to end: arm `retention-<id>` on every final event (in `RunnerEndpoint`/`Lineage` where finals are produced, or a small `RetentionArmer` consumer), handler deletes `BlobStore.deleteAll(datasetId)` then persists `Expired`; a purged dataset's blobs are deleted at the same timer; `Restored` re-arms with the remaining period
- [X] T095 [US8] Make list filters complete in `DatasetRows.scala` and `ModelsEndpoint.scala`: repeatable `status` and `tag`, `page`/`size` ≤ 200, exclusion of `expired` unless `includeExpired=true`
- [X] T096 [US8] Write `modules/api/src/test/scala/satisfactory/api/LifecycleHttpSuite.scala` covering Story 8 scenarios 1–8, SC-016 (with `retention = PT10S` in the fixture), and the SC-018 scan across `ApiFixture`'s captured logs, `/metrics`, webhook payloads recorded by the tier's receiver and `/ops/datasets`

**Checkpoint**: all tier 3 suites green: `sbt 'api/testOnly *HttpSuite'`.

---

## Phase 12: User Story 9 — Integrate from an ankka application (Priority: P5)

**Goal**: `satisfactory-client` (no ankka) and `ankka-satisfactory` (tools from the catalog, webhook verifier, scripted fake), published.

**Independent Test**: quickstart tier 6: `FakeSatisfactorySuite` and `PlannerWorkflowSuite` — a workflow submits through an agent tool, pauses, is resumed by the fake's signed webhook, reaches its result step; tampered signature refused; duplicate webhook a no-op; script exhaustion fails loudly; the client compiles without ankka on the classpath.

### Implementation for User Story 9

- [X] T097 [P] [US9] Create `modules/client/src/main/scala/satisfactory/client/{SatisfactoryClient,ModelClient,Errors}.scala` per `contracts/libraries.md` over JDK `HttpClient` (gzip option, `Idempotency-Key`, `awaitFinal` with backoff, errors → `SatisfactoryError`)
- [X] T098 [P] [US9] Create `modules/client/src/main/scala/satisfactory/client/Sse.scala`: `events(...)` as a `Flow.Publisher[StreamFrame]` that parses `data:` lines, unwraps the JSON-quoted string, maps `{seq, metadata, skipped}` and heartbeats, and reconnects with `after = lastSeq`
- [X] T099 [US9] Write `modules/client/src/test/scala/satisfactory/client/{ClientSuite,SseSuite}.scala` against `ApiFixture`-style `api` (the client module's tests may depend on `api` in `Test` scope only) and a scripted SSE server
- [X] T100 [P] [US9] Create `modules/ankka-satisfactory/src/main/scala/satisfactory/ankka/{SatisfactoryTools,Tagging}.scala`: `forModel(client, key, version)` → `solve_<key>` (`dataset: String` validated against `ModelDescriptor.schemas.input` with `JsonSchema`, `name`, `spentLimit`; description carries a schema summary; returns `{datasetId}` or `{validationErrors}`), `get_best_solution_<key>`, `terminate_<key>`; tags from the workflow/session context (`ankka-workflow:<id>` / `ankka-session:<id>`); check V2 and, if `SchemaType` is open, add a `SchemaType[ModelInput]` emitting the schema verbatim
- [X] T101 [P] [US9] Create `modules/ankka-satisfactory/src/main/scala/satisfactory/ankka/SatisfactoryWebhook.scala`: `looksSigned(maxSkew)` for `Acl.AllowIf`, `verify(secret, request, body): Either[WebhookError, Event]` on `protocol.Signing`, `given FromBody[Array[Byte]]`, typed `Event.Dataset | Event.WebhookFailing`
- [X] T102 [US9] Create `modules/ankka-satisfactory/src/main/scala/satisfactory/ankka/FakeSatisfactory.scala`: an in-process HTTP server on a random port answering the client from a `Script` of expected calls and canned responses, `fire(event, to)` posting a signed webhook, `remaining`, and a loud failure when the script runs out
- [X] T103 [US9] Write `modules/ankka-satisfactory/src/test/scala/satisfactory/ankka/{FakeSatisfactorySuite,PlannerWorkflowSuite}.scala`: the second runs an `AnkkaTestKit` service with `AgentRuntime.withDefaultModel(TestModelProvider)`, an agent using the generated tools, a workflow with `thenPause(after, onTimeout)`, and an endpoint using `SatisfactoryWebhook` to resume it; asserts SC-015 and Story 9 scenarios 1–8
- [X] T104 [US9] Add publishing settings (`sbt-ci-release`, `publishTo`, POM metadata, `versionScheme`) for `protocol`, `client`, `modelSpi`, `ankkaSatisfactory`; `publishLocal` them and build a throwaway `sbt new thinkmorestupidless/ankka.g8` project that adds only `ankka-satisfactory` and compiles the README's example

**Checkpoint**: `sbt client/test ankkaSatisfactory/test` green; libraries publish locally.

---

## Phase 13: Polish & cross-cutting

**Purpose**: The second catalog model (FR-002), documentation, the ankka follow-ups, and the final validation pass.

- [X] T105 [P] Port `use-cases/vehicle-routing` `domain/` (`Location`, `Vehicle` with the list variable, `Visit`, `VehicleRoutePlan`, `HaversineDrivingTimeCalculator`) and `solver/VehicleRoutingConstraintProvider.java` into `modules/models/vehicle-routing/src/main/java/satisfactory/models/vehiclerouting/`, with `Constraints.java` (`vehicleCapacity`, `serviceFinishedAfterMaxEndTime`, `minimizeTravelTime`), `HardSoftLongScore` codec
- [X] T106 [P] Add `modules/models/vehicle-routing` schemas (`input`/`output.schema.json`), `VehicleRoutingCodecs.java` (pinning assigned visits on decode), `VehicleRoutingValidation.java` (`VisitUnknownVehicle`, `VehicleCapacityNegative`, `EmptyPlan`), `VehicleRoutingMetrics.java` (`totalDrivingTimeSeconds`, `unassignedVisits`, `disruptionPercentage`), `DemoData.java`, `VehicleRouting.java` with `V1` (entity `route-plans`) and JUnit tests mirroring T020
- [X] T107 Register `VehicleRouting.V1` in both services' `ModelCatalog.of(...)`; add a vehicle-routing case to `SubmitHttpSuite` and `LineageHttpSuite` (list-variable warm start, V7) and a second-model case to `PoolResilienceSuite`
- [X] T108 [P] Update `API.md` §2.3 (Community score analysis shape) and `DESIGN.md` §6 (SPI as built, Timefold 2.7, slots per instance) with dated notes, and `README.md` (module map, run, test tiers, deploy)
- [X] T109 [P] Write `docs/ankka-requests.md` listing the follow-ups for ankka with the evidence from this build: raw `data:` + `id:` + comment SSE frames, a larger instance type, `param.json(schema)` on the tool builder, request `Content-Encoding` decoding, `terminationGracePeriodSeconds`; link it from `DESIGN.md` §8
- [X] T110 Security pass: constant-time comparisons for the runner token and signatures, secrets never logged, `ApiKeyAcl` cache bounded, gzip bomb cap tested, `/internal/*` unreachable without the token from the gateway (tier 5 curl), operator role required on every `/ops` route (grep test), no `println` of request bodies anywhere (`grep -rn 'println\|body' modules/*/src/main` reviewed)
- [X] T111 Run every quickstart tier in order (`just test`, `sbt 'solver/testOnly *PoolResilienceSuite'`, tier 5 on kind, `sbt ankkaSatisfactory/test`), record results and timings in `specs/001-constraint-solving-service/quickstart.md`, and close the V-list in `research.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies
- **Foundational (Phase 2)**: depends on Setup; blocks every story. Inside it: T007 → T008–T011 → T012; T013 → T014 → T016 → T020 (T015, T017–T019 parallel with T014/T016); T021–T023 parallel; T024 → T025/T026 → T027 → T028; T029 after T020 and T025; T030 → T034 → T035/T036; T037 → T038 → T039; T040–T041 after T037; T042 last
- **US1 (Phase 3)**: after Foundational
- **US2 (Phase 4)** and **US3 (Phase 5)**: after US1 (they extend `ModelsEndpoint`, `SolveSession`, `StreamSource`)
- **US4 (Phase 6)**: after US1; independent of US2/US3 except T061's warm-start path, which US4's suite exercises only for the requeue case
- **US10 (Phase 7)**: after US4 (workers) and provides `PlatformAcl` that **US5, US6, US8 need**
- **US5 (Phase 8)**: after US10 (`PlatformAcl`, queue pause in `TenantEntity`)
- **US6 (Phase 9)**: after US5 (subscriptions live in `TenantEntity`, platform routes)
- **US7 (Phase 10)**: after US1; independent of US4–US6
- **US8 (Phase 11)**: after US1; its SC-018 scan is most useful after US6 and US10
- **US9 (Phase 12)**: after US1 (client), US5 (keys) and US6 (webhooks)
- **Polish (Phase 13)**: after all stories

### Within each story

Entities and domain before endpoints; endpoints before the HTTP suite; the suite is the checkpoint.

### Parallel opportunities

- Setup: T002–T006 together after T001
- Foundational: the Java SPI/model track (T007–T020), the protocol track (T021–T023) and the api skeleton track (T030–T033) can proceed on three seats; the runner (T024–T028) follows SPI and protocol
- Stories: once US1 is done, US2+US3 (one seat), US4 (one seat) and US7 (one seat) can run in parallel; US10 → US5 → US6 is a chain; US8 can follow US1 on any seat
- Polish: T105–T106, T108–T109 together

---

## Parallel Example: Foundational

```bash
# Seat A (Java):   T007 → T008 T009 T011 → T010 → T012 → T013 → T014 T015 T017 T018 T019 → T016 → T020
# Seat B (Scala):  T021 T022 T023 → T024 → T025 T026 → T027 → T028 → T029
# Seat C (api):    T030 → T031 T032 T033 → T034 → T035 T036 → T037 → T038 → T039 → T040 T041 → T042
```

## Parallel Example: User Story 1

```bash
# After T043 (RunnerEndpoint) and T047 (Submission):
Task: "T044 SlotReleaser consumer in modules/api/src/main/scala/satisfactory/api/application/SlotReleaser.scala"
Task: "T046 IdempotencyEntity in modules/api/src/main/scala/satisfactory/api/application/IdempotencyEntity.scala"
Task: "T049 demo-data, /model and issue-type routes in ModelsEndpoint.scala"
Task: "T050 AboutMeEndpoint in modules/api/src/main/scala/satisfactory/api/api/AboutMeEndpoint.scala"
```

---

## Implementation Strategy

### MVP first (Phases 1–3)

1. Setup, then Foundational (the spike proves the interop before the entity is written).
2. US1: submit → poll → fetch with the runner in-process. `sbt api/run` is the whole system.
3. Stop and validate with `SubmitHttpSuite` and the README walkthrough. This is DESIGN.md's phase 1 walking skeleton and is demoable.

### Incremental delivery

1. US2 + US3 → streaming and lineage (DESIGN.md phase 2)
2. US4 + US10 → the pool on ankka, operable (phase 3); first real deployment
3. US5 + US6 + US7 + US8 → the product (phase 4)
4. US9 → the libraries; publish
5. Polish → vehicle routing, docs, ankka follow-ups, full quickstart run

### Parallel team strategy

Three seats through Foundational as in the example; then seat A takes US4 → US10 → US5 → US6, seat B takes US2 → US3 → US8, seat C takes US7 → US9 and the vehicle-routing model.

---

## Notes

- Every `[P]` task touches files no other in-flight task touches.
- `runner` must compile with no Pekko or HTTP types on its classpath; `protocol`, `client`, `model-spi`, `models` must compile without ankka. Add a `sbt` check (`dependencyTree` grep) to T110.
- Inputs and solutions never appear in logs, metrics, webhooks or ops rows; T091 and T096 enforce it, T110 reviews it.
- Timefold Community only: no `moveThreadCount`, no `analyze`, no recommendations (research R2).
- Commit after each task or checkpoint; keep the pre-commit format hook installed (T003).
