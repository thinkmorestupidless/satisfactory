# Contract: Platform API (`Authorization: Bearer <Keycloak token>`)

Base: `https://api-satisfactory.<base domain>/api/platform/v1`. Authenticated by a token from the installation's Keycloak (research R8); the subject is the caller's user id. Tenant routes require membership; `admin` role where marked; `operator` = realm role `satisfactory-operator`. A tenant the caller is not a member of answers `404`. Every mutating operator route writes an `AuditEntity` event.

## Tenants and membership

| Method | Path | Who | Body / Response |
|---|---|---|---|
| POST | `/tenants` | operator | `{ name, firstAdminSubject }` → `201 Tenant` |
| GET | `/tenants` | any member (their tenants) / operator (all) | `[Tenant]` |
| GET | `/tenants/{tenantId}` | member | `Tenant { id, name, createdAt, limits, queuePaused, memberCount, keyCount }` |
| GET | `/tenants/{tenantId}/members` | member | `[Member { subject, role, addedAt }]` |
| POST | `/tenants/{tenantId}/members` | admin | `{ subject, role: admin\|member }` → `201` |
| PATCH | `/tenants/{tenantId}/members/{subject}` | admin | `{ role }` |
| DELETE | `/tenants/{tenantId}/members/{subject}` | admin | `204`; last admin → `409` |
| GET | `/whoami` | any | `{ subject, operator: boolean, tenants: [{tenantId, role}] }` |

## API keys

| Method | Path | Who | Body / Response |
|---|---|---|---|
| GET | `/tenants/{tenantId}/keys` | member | `[ApiKeySummary { keyId, label, role, createdAt, revokedAt }]` |
| POST | `/tenants/{tenantId}/keys` | admin | `{ label, role: read-only\|read-write }` → `201 ApiKeyCreated { keyId, key }` (the only time `key` is returned) |
| DELETE | `/tenants/{tenantId}/keys/{keyId}` | admin | `204` revoke |

## Limits and queue

| Method | Path | Who | Body / Response |
|---|---|---|---|
| GET/PUT | `/tenants/{tenantId}/limits` | member / admin | `Limits { concurrency, submitPerMinute, lifetimeCeiling, retention }`; admins may lower, operators may raise above installation defaults |
| POST | `/ops/tenants/{tenantId}/queue/pause`, `…/resume` | operator | `204` (see Operator routes) |

## Configuration profiles

ankka routes take at most two path parameters, so the model is in the body (`"model": "employee-scheduling/v1"`) or `?model=` rather than the path.

| Method | Path | Who | Body / Response |
|---|---|---|---|
| GET | `/tenants/{tenantId}/configurations?model=` | member | `[ConfigurationProfile]` including the synthesised read-only `standard` per model |
| POST | `/tenants/{tenantId}/configurations` | admin | `ConfigurationProfile { model, name ≤ 60, description ≤ 1000, defaultConfigProfileId, runConfiguration, modelConfiguration (weights), resourcesConfiguration }` → `201`; the 51st for a model → `409` |
| GET/PUT/DELETE | `/tenants/{tenantId}/configurations/{configurationId}` | member / admin / admin | `standard` → `405` on PUT/DELETE |

## Webhook subscriptions and delivery log

| Method | Path | Who | Body / Response |
|---|---|---|---|
| GET | `/tenants/{tenantId}/webhooks` | member | `[WebhookSubscription]` (secret never returned) |
| POST | `/tenants/{tenantId}/webhooks` | admin | `{ url (https), events: ["dataset.*", "dataset.completed", "webhook.failing", …], filters: { status?, nameRegex?, tags?, model? }, signing: { method: "hmac-sha256", over: "body"\|"path" }, headers: {} }` → `201 { …, secret }` (once) |
| GET/PUT/DELETE | `…/webhooks/{subscriptionId}` | member / admin / admin | |
| POST | `…/webhooks/{subscriptionId}/rotate-secret` | admin | `{ secret }` |
| GET | `/tenants/{tenantId}/webhooks/deliveries` | member | `Page[DeliveryLogEntry]`; query `subscriptionId`, `outcome`, `since`, `page`, `size` |
| POST | `…/deliveries/{deliveryId}/retry` | admin | `202` |

## Operator routes (realm role `satisfactory-operator`)

| Method | Path | Response |
|---|---|---|
| GET | `/ops/datasets` | `Page[OpsDatasetRow { datasetId, tenantId, model, status, priority, submittedAt, bestScore, workerId, epoch, attempt }]`; query `tenantId`, `model`, `status`, `page`, `size`. **No input or solution content** |
| GET | `/ops/workers` | `[WorkerRow { workerId, models, slots, busy: [datasetId], draining, lastSeenAt, stale }]` |
| POST | `/ops/datasets/{datasetId}/terminate` | `200 Metadata`; always `force` |
| POST | `/ops/workers/{workerId}/drain`, `…/undrain` | `200 WorkerRow` |
| POST | `/ops/tenants/{tenantId}/queue/pause`, `…/resume` | `204` |
| GET | `/ops/audit` | `[ { subject, action, target, at } ]` last 500 |
| GET | `/metrics` (an endpoint on the HTTP port, `Authorization: Bearer <satisfactory.metrics-token>`; research R15) | Prometheus text: `satisfactory_queued{model}`, `satisfactory_slots{state=free\|busy}`, `satisfactory_workers{state=ready\|draining\|stale}`, `satisfactory_lease_losses_total`, `satisfactory_requeues_total`, `satisfactory_solves_total{outcome}`, `satisfactory_webhook_failures_total` |
