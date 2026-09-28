# Run it on your machine

> Start the whole of satisfactory in one JVM with the worker in-process and a development API key, from a clone of the repository and a Postgres in Docker.

Source: https://satisfactory.ankka.cloud/get-started/run-locally/
The `api` service can host the worker in-process, so one JVM is the whole system: the API, the dataset
entities, and a worker solving through the same code the `solver` service runs. This is how the
repository's own tests run it, and it is enough to submit, stream and receive webhooks on a laptop.

## Prerequisites

| Tool | Version | For |
|---|---|---|
| JDK | 21 | everything |
| [sbt](https://www.scala-sbt.org/) | any recent 1.x | building and running |
| Docker | any recent | Postgres, and the test kits |
| [just](https://github.com/casey/just) | any | optional shortcuts; every recipe is one command |

The ankka libraries the services depend on are resolved from Maven Central.

## Start Postgres and the API

Clone the repository, create the database with ankka's schema, and start the API with a development
key:

```bash
git clone https://github.com/thinkmorestupidless/satisfactory
cd satisfactory
sbt schema && docker compose up -d
SAT_BOOTSTRAP_KEY=sk_dev sbt api/run
```

`just db` and `just run-api` are the same two steps. The service listens on port 9000. On its first
start with `SAT_BOOTSTRAP_KEY` set it creates a tenant, `t_local`, with that value as a read-write API
key. The bootstrap key is a development convenience: the service refuses it when an identity provider
is configured, which is how a deployment creates tenants instead.

The worker is in-process because `SAT_LOCAL_RUNNER` defaults to `true`, with one slot; set
`SAT_LOCAL_SLOTS` to solve more datasets at once. Solutions are stored in Postgres; `SAT_BLOBS=memory`
keeps them in memory for a run that need not survive a restart.

## Check it answers

```bash
curl -s -H 'X-API-KEY: sk_dev' http://localhost:9000/api/aboutme
```

```json
{"tenantId":"t_local","tenantName":"local","keyId":"…","role":"read-write","queuePaused":false}
```

A missing key answers `401`; a key the service does not know answers `401` too. From here,
[Your first solve](first-solve.md) submits a demo dataset.

## Run the pool instead

To run the worker as the separate `solver` service, the way a deployment does, start the API without
its in-process worker and start the solver against it:

```bash
SAT_BOOTSTRAP_KEY=sk_dev SAT_LOCAL_RUNNER=false sbt api/run
sbt solver/run
```

The solver finds the API at `SAT_API_URL`, `http://localhost:9000` by default, and both sides share
the runner token, which defaults to the same development value. A dataset submitted now is claimed by
the solver over HTTP; `sbt solver/run` prints the claim and every report.

## Run the tests

```bash
sbt test                                   # everything; about ten minutes, Docker required
sbt modelSpi/test employeeScheduling/test  # the models alone, in seconds
sbt 'api/testOnly *HttpSuite'              # the whole API in one JVM
```

The HTTP suites start the API under ankka's test kit with a throwaway Postgres in a container, seed two
tenants, and drive the API over HTTP with the employee scheduling demo dataset.
