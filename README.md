# satisfactory

Constraint solving as a service, built on [Timefold Solver](https://github.com/TimefoldAI/timefold-solver)
and deployed as [ankka](../ankka) services. Submit a planning problem, get back a solution — or
follow the stream of improving ones.

Built through feature `001-constraint-solving-service` (phases 1–4 of the build order); see its
`tasks.md` for what is done and what is left.

| Document | What it is |
|---|---|
| [satisfactory.ankka.cloud](https://satisfactory.ankka.cloud/) | The documentation: get started, concepts, guides and reference, built from [`docs/`](docs/) and published as a site, `llms.txt` and agent skills |
| [`DESIGN.md`](DESIGN.md) | The system: why it is shaped the way it is, the job entity and its fold rules, the runner protocol and pool, how it is hosted on ankka, what it asks of ankka, the three artefacts (`satisfactory`, `satisfactory-client`, `ankka-satisfactory`), and the build order |
| [`API.md`](API.md) | The public API: a reimplementation of the Timefold Platform model API, with every divergence listed and justified. Supersedes the API parts of `DESIGN.md` |
| [`notes/timefold/`](notes/timefold/) | The two Timefold OpenAPI specs the API is derived from: the Employee Shift Scheduling model API (`v1`) and the Platform API (`1.12.3`) |

Read `API.md` first if you want to know what it does; `DESIGN.md` if you want to know how.

## The shape, in five lines

- A **model** is compiled Java behind a small SPI (Timefold problems are code plus data); a
  request names a model and carries a **dataset**.
- Datasets are **immutable with lineage**: a change is a new dataset derived from the old one.
- An event-sourced **entity per dataset** is the source of truth; **runners** pull work, solve,
  and report improving solutions with a fenced lease.
- Results arrive by **webhook** (production), **SSE** (development) or polling — Timefold's rules.
- An ankka application uses it through **`ankka-satisfactory`**: catalog-generated agent tools,
  a webhook verifier for an endpoint the developer writes, and a fake for tests.

## Modules

| Module | What it is |
|---|---|
| `modules/protocol` | Wire types and codecs, the webhook signature. No ankka |
| `modules/model-spi` | The Java SPI a model implements, and `ModelRuntime` |
| `modules/models/employee-scheduling`, `…/vehicle-routing` | The two catalog models, ported from Timefold's quickstarts |
| `modules/runner` | The worker: claim loop, throttle, solve session. No Pekko, no HTTP types |
| `modules/api` | The ankka service: entities, views, timers, workflows, endpoints |
| `modules/solver` | The ankka service with no components: the runner over HTTP |
| `modules/client` | `satisfactory-client`, published |
| `modules/ankka-satisfactory` | Tools, webhook verifier and fake for ankka applications, published |

## Run it

The whole system in one process, with the runner in-process and a development key:

```bash
sbt schema && docker compose up -d            # Postgres with ankka's schema
SAT_BOOTSTRAP_KEY=sk_dev sbt api/run          # :9000, tenant t_local with key sk_dev

API=http://localhost:9000/api/models/employee-scheduling/v1
curl -s -H 'X-API-KEY: sk_dev' $API/demo-data/SMALL \
  | curl -s -X POST -H 'X-API-KEY: sk_dev' -H 'Content-Type: application/json' --data-binary @- $API/schedules
curl -s -H 'X-API-KEY: sk_dev' $API/schedules/<id>/metadata      # poll until SOLVING_COMPLETED
curl -s -H 'X-API-KEY: sk_dev' $API/schedules/<id>               # the schedule, score and KPIs
```

`sbt test` runs every suite (Docker required). `just docs` builds the documentation site into
`target/docs-site`; `just docs-serve` serves it with live reload. `CLAUDE.md` lists the rest.
