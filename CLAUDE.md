# CLAUDE.md

Guidance for coding agents working in this repository.

## What this is

satisfactory is constraint solving as a service, built on Timefold Solver 2.7 (Community) and
deployed as two [ankka](../ankka) services. `API.md` is the public contract (a reimplementation of
the Timefold Platform model API); `DESIGN.md` is how it works; `specs/001-constraint-solving-service/`
is the feature being built, with its plan, research, data model, contracts and task list.

ankka is the owner's own platform (Scala 3 on Pekko), not Akka. Its source is at `../ankka`; read
its `CLAUDE.md` and `docs/` before relying on an SDK behaviour.

## Modules and the direction dependencies run

```
protocol            wire types + codecs (jsoniter)                     no ankka
client              satisfactory-client: HTTP + SSE over the JDK        no ankka
model-spi           SolverModel, ModelRuntime, ModelCatalog (Java)      no ankka, no Scala
models/*            employee-scheduling, vehicle-routing (Java)         no ankka
runner              claim loop, throttle, solve session                 no ankka, NO Pekko, NO HTTP types
api                 the ankka service: entities, views, endpoints        [image satisfactory-api]
solver              the ankka service: runner over HTTP, no components   [image satisfactory-solver]
ankka-satisfactory  tools, webhook verifier, fake                        ankka + client
spike               phase 0: a worker against a printing channel
```

Seam rules, kept deliberately: `runner` has no Pekko and no HTTP types (a dedicated pod runs it
without an actor system); the SPI is the only thing a model sees; dataset JSON is Jackson 2
(aligned with ankka's databind), wire types are jsoniter.

## The rules the dataset entity enforces

`DatasetFold` is the one fold, shared by `DatasetEntity` and the `DatasetRows` view. Three rules,
all in the entity: a worker command with a stale lease epoch is refused with `Conflict` before
anything persists; only a strictly better score is recorded, and `seq` is the entity's; no event
carries a body (blobs are references). Change the fold and `DatasetEntitySuite` says so.

## Commands

```bash
sbt test                                   # everything; Docker required (AnkkaTestKit starts Postgres)
sbt modelSpi/test employeeScheduling/test  # the models, seconds
sbt runner/test                            # the runner against a scripted channel
sbt 'api/testOnly *DatasetEntitySuite'     # the fold rules, no database
sbt 'api/testOnly *HttpSuite'              # the API in one JVM (quickstart tier 3)
sbt 'spike/run 10'                         # phase 0: watch a solve improve
just db && just run-api                    # the whole system in one process on :9000
sbt api/Docker/publishLocal solver/Docker/publishLocal deployDescriptors
```

Tests are serialised (`Tags.limit(Tags.Test, 1)`): overlapping Postgres suites contend. Do not
"optimise" this back.

## Things that are not what they look like

- Score analysis is Enterprise-only in Timefold 2.7; the per-constraint breakdown is computed by
  ablation in `ModelRuntime.analyze` (research R2). Do not reach for `SolutionManager.analyze`.
- ankka reads request bodies with `toStrict`, capped by `pekko.http.parsing.max-to-strict-bytes`,
  not only `max-content-length`; both are set in `api`'s `application.conf`.
- Scala nests block comments: `/internal/*` inside a doc comment opens a new one.
- ankka's SSE emits `data:` frames whose payload is a JSON-quoted string; the client unwraps it.
- A deployed service logs in to its Postgres by client certificate, never a password (ankka feature
  014, unreleased at 0.7.1). `V.ankka` must be a runtime that has `DatabaseTls`, and the blob store's
  own JDBC pool applies the same `ssl` block through `DatabaseSslSocketFactory`. Against a platform
  built from `../ankka` HEAD, a runtime without it fails at startup with `password authentication
  failed for user "api"`.
- In a cluster every service port is mutual TLS, so the solver reaches `api` through ankka's service
  client (`ServiceControlChannel`, from `service.services("api")`), never by URL. `SAT_API_URL` is
  the plain-HTTP path for a laptop or a test and stays unset in `deploy/solver.json`.
- `import satisfactory.protocol.*` shadows a same-named type defined in another file of the current
  package (`DatasetEvent` exists in both `protocol` and `api.application`); import the local one
  explicitly. Stale incremental state can hide this until `sbt clean`.
