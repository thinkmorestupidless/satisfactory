# Tenants, keys and profiles

> How the platform API and the model API authenticate differently, what a tenant owns, how limits queue and refuse work, and where configuration profiles fit.

Source: https://satisfactory.ankka.cloud/concepts/tenancy/
A tenant is an isolated workspace: its members, its API keys, its limits, its configuration profiles,
its webhook subscriptions, and every dataset submitted with one of its keys. Nothing crosses tenants. A
dataset that belongs to another tenant is a `404`, not a `403`, so a key learns nothing about what it
cannot see.

## Two APIs, two credentials

| API | Prefix | Credential | Who uses it |
|---|---|---|---|
| Model API | `/api/models/{model}/{version}` | `X-API-KEY: sk_…`, a tenant's key | applications submitting and reading datasets |
| Platform API | `/api/platform/v1` | `Authorization: Bearer <token>` from the identity provider | people and operators administering tenants |

An API key is scoped to one tenant and has a role, `read-only` or `read-write`. It is shown once, when
created, and stored hashed. A read-only key can read everything the tenant owns and submit nothing; a
`POST`, `PATCH`, `PUT` or `DELETE` with it is a `403`.

A platform token is a JSON Web Token from the identity provider the installation is configured with,
verified offline against its published keys. It names a person, who may be a member of several tenants
with a role of `admin` or `member` in each, and may hold the operator role, which the installation
names in `satisfactory.auth.operator-role`. Members read a tenant's settings; admins change them;
operators create tenants and see every one. The platform API answers `503` until an identity provider
is configured, which is why a development instance uses a bootstrap key instead.

Keys and tokens do not cross: a token on the model API and a key on the platform API are both refused.

## Limits

Each tenant has four limits, set by the installation's defaults and changed on the platform API:

| Limit | Default | Effect |
|---|---|---|
| `concurrency` | 2 | datasets solving at once; the rest wait in the queue in priority order |
| `submitPerMinute` | 60 | submits per minute; over it, `429` |
| `lifetimeCeiling` | `PT12H` | the hard ceiling on any solve, above whatever `spentLimit` asked for |
| `retention` | `P30D` | how long a dataset's bodies are kept before they expire |

A tenant admin may set a limit up to the installation's default; only an operator may exceed it. The
queue is `SOLVING_SCHEDULED`: a dataset waits there until the tenant has a free slot, and `priority`
from 0 to 10 orders a tenant's own queue. An operator can pause a tenant's queue, after which nothing
of that tenant's starts until it is resumed, and every metadata answer says `queuePaused` while a
dataset waits on it.

## Configuration profiles

A profile is a named, tenant-owned bundle of run configuration and constraint weights for one model,
referenced by id or by name in a submit's `configurationId`. Every tenant has a read-only `standard`
profile per model, which is the model's defaults, and up to fifty of its own.
[Configuration profiles](../build/configuration-profiles.md) is the guide.

## Webhook subscriptions

Subscriptions belong to the tenant, are managed on the platform API by an admin, and each has its own
signing secret, encrypted at rest. A subscription hears only events that happen after it was created.
