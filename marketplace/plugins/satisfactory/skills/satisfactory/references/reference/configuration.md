# Configuration

> Every setting of the api and solver services, the environment variable that overrides each, its default, and what it does.

Source: https://satisfactory.ankka.cloud/reference/configuration/
Both services are configured with HOCON files on their classpath, and every setting a deployment must
set has an environment variable that overrides it. The variables are the deployment's whole interface:
the descriptors under `deploy/` set them from a secret, and a development run sets them in the shell.

| Variable | Configuration key | Default | Applies in |
|---|---|---|---|
| `SAT_RUNNER_TOKEN` | `satisfactory.runner-token` | `"local-runner-token"` | api |
| `SAT_SECRET_KEY` | `satisfactory.secret-key` | `"local-development-secret-key-change-me"` | api |
| `SAT_LOCAL_RUNNER` | `satisfactory.local-runner` | `true` | api |
| `SAT_LOCAL_SLOTS` | `satisfactory.local-slots` | `1` | api |
| `SAT_BOOTSTRAP_KEY` | `satisfactory.bootstrap-key` | `""` | api |
| `SAT_BLOBS` | `satisfactory.blobs` | `postgres` | api |
| `SAT_PUBLIC_URL` | `satisfactory.public-url` | `"http://localhost:9000"` | api |
| `SAT_METRICS_TOKEN` | `satisfactory.metrics-token` | `""` | api |
| `SAT_AUTH_ISSUER` | `satisfactory.auth.issuer` | `""` | api |
| `SAT_AUTH_JWKS_URL` | `satisfactory.auth.jwks-url` | `""` | api |
| `SAT_API_URL` | `satisfactory.solver.api-url` | `"http://localhost:9000"` | solver |
| `SAT_API_SERVICE` | `satisfactory.solver.api-service` | `"api"` | solver |
| `SAT_RUNNER_TOKEN` | `satisfactory.solver.runner-token` | `"local-runner-token"` | solver |
| `SAT_SLOTS` | `satisfactory.solver.slots` | `0` | solver |

Settings with no environment variable, overridable in the service's own `application.conf`:

| Configuration key | Default | Applies in |
|---|---|---|
| `satisfactory.lease-ttl` | `20s` | api |
| `satisfactory.heartbeat` | `5s` | api |
| `satisfactory.max-attempts` | `3` | api |
| `satisfactory.claim-wait` | `5s` | api |
| `satisfactory.webhooks.timeout` | `5s` | api |
| `satisfactory.webhooks.retry-interval` | `10s` | api |
| `satisfactory.webhooks.max-attempts` | `10` | api |
| `satisfactory.defaults.concurrency` | `2` | api |
| `satisfactory.defaults.submit-per-minute` | `60` | api |
| `satisfactory.defaults.lifetime-ceiling` | `12h` | api |
| `satisfactory.defaults.retention` | `30d` | api |
| `satisfactory.auth.operator-role` | `"satisfactory-operator"` | api |
| `satisfactory.solver.heartbeat` | `5s` | solver |
| `satisfactory.solver.report-interval` | `1s` | solver |
## The api

`SAT_RUNNER_TOKEN` is the token every worker presents on `/internal`, and it must be the same value on
the solver. Without it a runner route answers `403`. `SAT_SECRET_KEY` is the key under which webhook
signing secrets are encrypted at rest; changing it makes existing subscriptions' secrets unreadable, so
rotate them afterwards.

`SAT_LOCAL_RUNNER` hosts the worker inside the api, which is the development mode and the default;
a deployment sets it to `false` and runs the solver service. `SAT_LOCAL_SLOTS` is how many datasets
that in-process worker solves at once.

`SAT_BOOTSTRAP_KEY` creates a tenant, `t_local`, with that read-write key at startup. It is refused
when `SAT_AUTH_ISSUER` is set, so a deployment creates tenants on the platform API instead.

`SAT_BLOBS` chooses where solutions are stored: `postgres`, the service's own database, or `memory`
for a run that need not survive a restart. `SAT_PUBLIC_URL` is the address the API is exposed at, and
is how the links in webhook events are rooted. `SAT_METRICS_TOKEN` is the bearer token Prometheus
presents to `/metrics`; unset, the route refuses everyone.

`SAT_AUTH_ISSUER` and `SAT_AUTH_JWKS_URL` name the identity provider whose tokens the platform API
accepts: the issuer every token must carry, and where its signing keys are published. Without them the
platform API answers `503`. The operator role is a realm role on that provider, named by
`satisfactory.auth.operator-role`.

The lease settings are the pool's timing: a lease not renewed within `lease-ttl` expires, `max-attempts`
expiries fail the dataset, and `claim-wait` is how long a worker's claim long-polls before an empty
answer. The `heartbeat` setting on the api is read but not used; the interval a worker heartbeats at is
the solver's. The `webhooks` block is the delivery rule: how long to wait for an answer, how long
between retries, and how many. The `defaults` block is every new tenant's limits.

The two `pekko.http` limits allow a 100 MB compressed submission, and both are needed: ankka reads a
request body strictly, which pekko-http caps separately from the content length.

## The solver

How the solver reaches the api depends on where it runs. On a laptop or in a test it is plain HTTP at
`SAT_API_URL`, `http://localhost:9000` by default. In a cluster every service port is mutual TLS, so
the solver ignores the URL and calls the ankka service named by `SAT_API_SERVICE`, `api` by default, as
itself, presenting the certificate the platform mounted; the descriptor sets neither. `SAT_SLOTS` is how many datasets one instance solves at once; `0` means one
fewer than the instance's cores, and at least one. `heartbeat` is how often each held dataset heartbeats,
which is also the terminate latency, and `report-interval` is the most often a worker reports an
improvement, never under 250 milliseconds.

The worker's id is the pod's `HOSTNAME`, or `solver-<ulid>` where there is none.
