# Contract: Webhook events

Delivered by `WebhookDeliveryWorkflow` (research R10). At-least-once; receivers dedupe on `id`.

## Request

```http
POST <subscription.url>
Content-Type: application/json
X-Satisfactory-Signature: <base64(HMAC-SHA256(secret, body | path))>
X-Satisfactory-Timestamp: 2026-09-26T10:15:30Z
X-Satisfactory-Event: dataset.completed
X-Satisfactory-Delivery: evt_…:wh_…
<custom headers from the subscription, with {hmac_signature} and {hmac_timestamp} substituted>
```

Signing `over: "body"` (default) signs the exact request bytes; `over: "path"` signs the URL path. Receivers reject a timestamp older than 5 minutes. Delivery expects a `2xx` within 5 s; otherwise retried up to 10 times, 10 s apart, including after a read timeout.

## Envelope

```jsonc
{ "id": "evt_01J…", "type": "dataset.completed", "occurredAt": "…", "tenant": "t_…",
  "data": {
    "id": "ds_…", "name": "week-39", "model": "employee-scheduling", "modelVersion": "v1",
    "tags": ["site:leeds", "ankka-workflow:wf-42"], "status": "SOLVING_COMPLETED", "score": "0hard/-311soft",
    "seq": 23, "parentId": null, "originId": null, "supersededBy": null,
    "runLink": "https://…/api/models/employee-scheduling/v1/schedules/ds_…",
    "outputLink": "https://…/api/models/employee-scheduling/v1/schedules/ds_…" } }
```

## Event types (v1)

| Type | When |
|---|---|
| `dataset.computed` | a dataset submitted with `operation=NONE` reaches `DATASET_COMPUTED` |
| `dataset.invalid` | `DATASET_INVALID` |
| `dataset.completed` | `SOLVING_COMPLETED` (termination, terminate, supersede, lifetime) |
| `dataset.incomplete` | `SOLVING_INCOMPLETE` |
| `dataset.failed` | `SOLVING_FAILED` |
| `webhook.failing` | a subscription's delivery attempts were exhausted; `data: { subscriptionId, url, failedEventId, failedEventType, attempts, lastStatus, lastError }`; sent to the tenant's *other* subscriptions that list it, at most once per two hours per failing subscription |

Subscriptions select with `events` (`dataset.*` or exact types) and `filters` (`status`, `nameRegex`, `tags` all-of, `model`). Filters apply to `dataset.*` only. Payloads never carry a solution.
