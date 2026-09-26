# satisfactory

Constraint solving as a service, built on [Timefold Solver](https://github.com/TimefoldAI/timefold-solver)
and deployed as [ankka](../ankka) services. Submit a planning problem, get back a solution — or
follow the stream of improving ones.

Nothing is built yet. This repository holds the design.

| Document | What it is |
|---|---|
| [`DESIGN.md`](DESIGN.md) | The system: why it is shaped the way it is, the job entity and its fold rules, the runner protocol and pool, how it is hosted on ankka, what it asks of ankka, the three artefacts (`satisfactory`, `satisfactory-client`, `ankka-satisfactory`), and the build order |
| [`API.md`](API.md) | The public API: a reimplementation of the Timefold Platform model API, with every divergence listed and justified. Supersedes the API parts of `DESIGN.md` |
| [`docs/timefold/`](docs/timefold/) | The two Timefold OpenAPI specs the API is derived from: the Employee Shift Scheduling model API (`v1`) and the Platform API (`1.12.3`) |

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
