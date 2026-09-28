# Deploy on ankka

> Build the two images, create the secrets the descriptors reference, deploy the api and solver services to an ankka project, expose the API, and create the first tenant and key.

Source: https://satisfactory.ankka.cloud/deploy/deploy-on-ankka/
satisfactory deploys as two [ankka](https://docs.ankka.cloud/) services in one project: `api`, exposed,
and `solver`, which serves nothing. The service descriptors are in the repository under `deploy/`, and
everything a deployment must supply is an environment variable read from a secret.

## Build the images

```bash
sbt api/Docker/publishLocal solver/Docker/publishLocal deployDescriptors
```

The first two publish `satisfactory-api` and `satisfactory-solver` to the local Docker daemon. The
third writes the descriptors to `target/deploy/` with the image names and the ankka runtime version
filled in; set `DOCKER_REPOSITORY` to prefix the image names with a registry. Push the images wherever
the cluster pulls from, or load them into a local kind cluster:

```bash
kind load docker-image satisfactory-api:<version> satisfactory-solver:<version> --name ankka
```

## Create the secrets

Both descriptors read from one Kubernetes secret, `satisfactory-secrets`, in the project's namespace:

| Key | Used by | What it is |
|---|---|---|
| `runner-token` | both | the shared token workers present on `/internal`; any long random string |
| `secret-key` | api | encrypts webhook signing secrets at rest; any long random string, and changing it makes existing subscriptions unreadable |
| `auth-issuer` | api | the identity provider's issuer URL, which every platform token must carry |
| `auth-jwks-url` | api | where the identity provider publishes its signing keys |

```bash
ankka projects create satisfactory -O <organization>
kubectl -n ankka-satisfactory create secret generic satisfactory-secrets \
  --from-literal=runner-token="$(openssl rand -base64 32)" \
  --from-literal=secret-key="$(openssl rand -base64 32)" \
  --from-literal=auth-issuer="https://<keycloak>/realms/<realm>" \
  --from-literal=auth-jwks-url="https://<keycloak>/realms/<realm>/protocol/openid-connect/certs"
```

The identity provider is the one ankka's control plane already uses, so a person who can log in to
ankka can administer a tenant here with the same token. The operator role,
`satisfactory-operator` by default, is a realm role on that provider.

## Deploy the services

```bash
ankka services apply -f target/deploy/api.json -p satisfactory
ankka services apply -f target/deploy/solver.json -p satisfactory
ankka services expose api -p satisfactory
```

The `api` descriptor sets `SAT_LOCAL_RUNNER` to `false`, so the API hosts no worker of its own, and
runs three `small` instances. The `solver` descriptor sets `http` to `false` and runs three `large`
instances, each with one solving slot, so the pool's capacity is its instance count; it names no api
address, because in a cluster the solver calls the `api` service as itself over mutual TLS. Both are
odd-counted because ankka's cluster overlay keeps the majority side of a partition.

Two settings the descriptor leaves at their defaults are worth setting on `api`:

- `SAT_PUBLIC_URL`, the address the API is exposed at. It is how the links in webhook events are
  rooted; left unset they point at `http://localhost:9000`.
- `SAT_METRICS_TOKEN`, the bearer token Prometheus presents to `/metrics`. Left unset, `/metrics`
  refuses every caller.

`expose` prints the hostname, `https://api-satisfactory.<base domain>`. The runner endpoint under
`/internal` is reachable there too, and its token is the only thing in front of it: keep the token long
and random. [Configuration](../reference/configuration.md) lists every variable.

## Create the first tenant and key

With a token from the identity provider for a user holding the operator role:

```bash
export SAT=https://api-satisfactory.<base domain>
curl -s -X POST $SAT/api/platform/v1/tenants -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"acme","firstAdminSubject":"<the subject of the tenant admin>"}'
curl -s -X POST $SAT/api/platform/v1/tenants/<tenantId>/keys -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"label":"planner","role":"read-write"}'
```

The second reply is the only time the key's plaintext is shown. From here the model API answers that
key, and [Your first solve](../get-started/first-solve.md) applies with `$SAT/api/models/…` as the base.

## Check it

```bash
ankka services logs solver -p satisfactory       # claims and reports as datasets are solved
curl -s -H "X-API-KEY: $KEY" $SAT/api/aboutme
```

Deleting a solver pod mid-solve is a good first test of the pool: the dataset's stream stays monotonic,
another worker continues from the best solution within the lease's time to live, and the dataset
completes. [Workers and leases](../concepts/workers-and-leases.md) says why.

## Roll out a new version

`ankka services apply` with a new image tag rolls each service. A solver instance that receives the
stop signal releases every dataset it holds, with its best solution reported, before it exits, and
another instance continues them. An API roll does not interrupt a solve at all: the workers retry an
unavailable API and the event stream resumes from its sequence number.
