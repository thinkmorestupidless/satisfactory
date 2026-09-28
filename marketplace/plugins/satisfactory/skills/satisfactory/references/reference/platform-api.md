# Platform API

> Every route of the platform API — tenants, members, keys, limits, configuration profiles, webhook subscriptions and the delivery log — and the operator routes, with who may call each.

Source: https://satisfactory.ankka.cloud/reference/platform-api/
The platform API is `/api/platform/v1`, authenticated with a bearer token from the installation's
identity provider. It administers tenants; it never touches datasets. Until an identity provider is
configured every route answers `503`.

Three levels of access: a **member** of a tenant reads its settings; an **admin** of a tenant changes
them; an **operator**, whose token carries the operator role, creates tenants, sees every tenant, and
uses the routes under `/ops`. A tenant the caller is not a member of is a `404`; a change a member may
not make is a `403`.

## Tenants and members

| Method | Path | Body | Who | Answer |
|---|---|---|---|---|
| `POST` | `/tenants` | `{ name, firstAdminSubject, limits? }` | operator | `201` tenant |
| `GET` | `/tenants` | | anyone | the caller's tenants; every tenant for an operator |
| `GET` | `/tenants/{tenantId}` | | member | `{ id, name, createdAt, limits, queuePaused, memberCount, keyCount }` |
| `GET` | `/tenants/{tenantId}/members` | | member | `[{ subject, role, addedAt }]` |
| `POST` | `/tenants/{tenantId}/members` | `{ subject, role }` | admin | `201` member; `role` is `admin` or `member` |
| `PATCH` | `/tenants/{tenantId}/members/{subject}` | `{ role }` | admin | member |
| `DELETE` | `/tenants/{tenantId}/members/{subject}` | | admin | `204` |
| `GET` | `/whoami` | | anyone | `{ subject, operator, tenants: [{ tenantId, role }] }` |

## Keys

| Method | Path | Body | Who | Answer |
|---|---|---|---|---|
| `GET` | `/tenants/{tenantId}/keys` | | member | `[{ keyId, label, role, createdAt, revokedAt }]` |
| `POST` | `/tenants/{tenantId}/keys` | `{ label, role }` | admin | `201` `{ keyId, key, role }`; the plaintext `sk_…` appears only here; `role` is `read-only` or `read-write` |
| `DELETE` | `/tenants/{tenantId}/keys/{keyId}` | | admin | `204`: revoked |

## Limits

| Method | Path | Body | Who | Answer |
|---|---|---|---|---|
| `GET` | `/tenants/{tenantId}/limits` | | member | `{ concurrency, submitPerMinute, lifetimeCeiling, retention }` |
| `PUT` | `/tenants/{tenantId}/limits` | the same | admin up to the installation's defaults; operator beyond | the limits |

Durations are ISO-8601: `PT12H`, `P30D`.

## Configuration profiles

| Method | Path | Body | Who | Answer |
|---|---|---|---|---|
| `GET` | `/tenants/{tenantId}/configurations?model=` | | member | the model's profiles, `standard` included |
| `GET` | `/tenants/{tenantId}/configurations/{configurationId}` | | member | a profile |
| `POST` | `/tenants/{tenantId}/configurations` | a profile | admin | `201` profile; `400` `validation` for a bad weight; `409` for a duplicate name or a fifty-first |
| `PUT` | `/tenants/{tenantId}/configurations/{configurationId}` | a profile | admin | the profile; `405` for `standard` |
| `DELETE` | `/tenants/{tenantId}/configurations/{configurationId}` | | admin | `204`; `405` for `standard` |

A profile is `{ id, model, name, description, defaultConfigProfileId, runConfiguration,
modelConfiguration, resourcesConfiguration, updatedAt, readOnly }`.
[Configuration profiles](../build/configuration-profiles.md) explains the fields.

## Webhook subscriptions

| Method | Path | Body | Who | Answer |
|---|---|---|---|---|
| `GET` | `/tenants/{tenantId}/webhooks` | | member | subscriptions, without secrets |
| `POST` | `/tenants/{tenantId}/webhooks` | `{ url, events, filters?, signing?, headers? }` | admin | `201` subscription with its `secret` |
| `GET` | `/tenants/{tenantId}/webhooks/{subscriptionId}` | | member | a subscription |
| `PUT` | `/tenants/{tenantId}/webhooks/{subscriptionId}` | the same | admin | the subscription |
| `DELETE` | `/tenants/{tenantId}/webhooks/{subscriptionId}` | | admin | `204` |
| `POST` | `/tenants/{tenantId}/webhooks/{subscriptionId}/rotate-secret` | | admin | the subscription with a new `secret` |
| `GET` | `/tenants/{tenantId}/webhooks/deliveries?subscriptionId=&outcome=&page=&size=` | | member | a page of delivery log entries |
| `POST` | `/tenants/{tenantId}/webhooks/deliveries/{deliveryId}/retry` | | admin | `204` |

A subscription is `{ id, url, events, filters: { status, nameRegex, tags, model }, signing: { method,
over }, headers, createdAt, lastFailingNotifiedAt, secret? }`. A delivery log entry is `{ deliveryId,
subscriptionId, eventId, eventType, datasetId, target, attempts: [{ at, status, error, durationMs }],
outcome, createdAt }`, with `outcome` one of `pending`, `delivered`, `exhausted`.
[Receive webhooks](../build/webhooks.md) is the guide.

## Operator routes

All under `/api/platform/v1/ops`, operator only:

| Method | Path | Answer |
|---|---|---|
| `GET` | `/datasets?tenantId=&model=&status=&page=&size=` | rows of `{ datasetId, tenantId, model, status, priority, submittedAt, bestScore, workerId, epoch, attempt }` |
| `GET` | `/workers` | `[{ workerId, models, slots, busy, draining, lastSeenAt, stale }]` |
| `POST` | `/datasets/{id}/terminate` | metadata; a forced terminate |
| `POST` | `/workers/{id}/drain`, `/workers/{id}/undrain` | the worker row |
| `POST` | `/tenants/{id}/queue/pause`, `/tenants/{id}/queue/resume` | `204` |
| `GET` | `/audit?limit=` | `[{ subject, action, target, at }]`, up to 500 |

[Operate the service](../deploy/operate.md) says when to use each.

## Metrics

`GET /metrics`, outside the platform prefix, serves Prometheus text to a request carrying
`Authorization: Bearer <SAT_METRICS_TOKEN>`.

## Errors

Errors are `{ id, code, message, details }` with the same codes as the [Model API](model-api.md). A
`401` with a `WWW-Authenticate` challenge is ankka's, for a token that is missing or does not verify.
