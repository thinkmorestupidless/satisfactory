# Receive webhooks

> Register a webhook subscription on a tenant, verify a signed delivery, handle at-least-once delivery, filter what you receive, and read and retry the delivery log.

Source: https://satisfactory.ankka.cloud/build/webhooks/
A webhook is how a result reaches production code. When a dataset reaches a final status satisfactory
posts an event to every subscription of the tenant that matches it, signed, and retries until the
receiver answers. The event carries the dataset's metadata and links, never the solution; the receiver
fetches the solution with its own key.

## Register a subscription

Subscriptions belong to the tenant and are managed on the platform API by a tenant admin, with a bearer
token:

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  $SAT/api/platform/v1/tenants/$TENANT/webhooks \
  -d '{
    "url": "https://planner-acme.example.com/hooks/satisfactory",
    "events": ["dataset.*"],
    "filters": { "tags": ["weekly"] },
    "signing": { "method": "hmac-sha256", "over": "body" }
  }'
```

```json
{"id":"whs_01K6…","url":"https://planner-acme.example.com/hooks/satisfactory","events":["dataset.*"],
 "filters":{"tags":["weekly"]},"signing":{"method":"hmac-sha256","over":"body"},"headers":{},
 "createdAt":"2026-09-28T10:00:00Z","secret":"whsec_…"}
```

The `secret` is shown in this response and after `POST …/{id}/rotate-secret`, and nowhere else. Keep
it where the receiver can read it.

| Field | Meaning |
|---|---|
| `url` | an absolute `http` or `https` URL |
| `events` | `dataset.*`, or any of `dataset.computed`, `dataset.invalid`, `dataset.completed`, `dataset.incomplete`, `dataset.failed`, and `webhook.failing` |
| `filters` | `status`, `nameRegex`, `tags`, `model`: a dataset must match every filter given |
| `signing` | `method` is `hmac-sha256`; `over` is `body` or `path` |
| `headers` | custom headers to send, in which `{hmac_signature}` and `{hmac_timestamp}` are substituted |

A subscription hears only events that occur after it was created.

## The delivery

```text
POST /hooks/satisfactory
Content-Type: application/json
X-Satisfactory-Signature: 4Xk…=
X-Satisfactory-Timestamp: 2026-09-28T10:15:34.120Z
X-Satisfactory-Event: dataset.completed
X-Satisfactory-Delivery: whd_01K6…

{"id":"evt_ds_01K6…_19","type":"dataset.completed","occurredAt":"2026-09-28T10:15:34Z","tenant":"t_acme",
 "data":{"id":"ds_01K6…","name":"week-40","model":"employee-scheduling","modelVersion":"v1","tags":["weekly"],
         "status":"SOLVING_COMPLETED","score":"0hard/-1830soft","seq":19,"parentId":null,"originId":null,"supersededBy":null,
         "runLink":"https://api-satisfactory.example.com/api/models/employee-scheduling/v1/schedules/ds_01K6…",
         "outputLink":"https://api-satisfactory.example.com/api/models/employee-scheduling/v1/schedules/ds_01K6…"}}
```

[Webhook events](../reference/webhook-events.md) lists every field.

## Verify the signature

The signature is HMAC-SHA256, keyed with the subscription's secret, over the timestamp header's value,
a full stop, and the request body's exact bytes, Base64 encoded. With `signing.over` set to `path` the
body is replaced by the request's path. Verify before reading the body:

1. Refuse a request with no `X-Satisfactory-Signature` or `X-Satisfactory-Timestamp`.
2. Refuse a timestamp more than five minutes from now, in either direction. Because the timestamp is
   inside the signed string, a replayed request cannot carry a fresh one.
3. Compute the HMAC over `<timestamp>.<body bytes>` and compare it to the header in constant time.

Signing the body rather than the path is the default because a path signature does not authenticate the
payload. An ankka application gets both checks from `SatisfactoryWebhook`, described in
[From an ankka application](ankka-applications.md).

## Answer fast, work later

The sender waits five seconds for an answer. Any `2xx` counts as delivered; anything else, or no answer,
is retried up to ten times, ten seconds apart. Receive the event, put it on a queue of your own, answer
`200`, and fetch the result afterwards with your API key at `outputLink`.

## Duplicates

Delivery is at-least-once: a read timeout is retried, because not retrying would lose events. The event
`id` is stable across retries, and `data.seq` is the dataset's sequence number, so a receiver that has
seen an id can drop the repeat. A workflow that finds its work already done and changes nothing is the
usual shape.

## When a subscription keeps failing

After the last retry the delivery is marked `exhausted`, and a `webhook.failing` event goes to the
tenant's other subscriptions that listen for it, at most once every two hours per failing subscription.
It names the subscription, the URL, the event that failed and the last status. Nothing is lost: the
delivery log keeps the event, and an admin can retry it.

## The delivery log

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$SAT/api/platform/v1/tenants/$TENANT/webhooks/deliveries?outcome=exhausted"
```

Each entry has the delivery id, the subscription, the event id and type, the dataset, the target URL,
every attempt with its time, status, error and duration, and an outcome of `pending`, `delivered` or
`exhausted`. Entries are kept thirty days. `POST …/deliveries/{deliveryId}/retry` delivers it again
from the first attempt.

## Test a receiver

The delivery log's retry is the way to send a real event again. For a receiver under test with nothing
running, an ankka application uses the fake's `fire`, which posts a signed event to any URL;
[From an ankka application](ankka-applications.md) shows it. Any receiver can also be checked by hand:
sign `<timestamp>.<body>` with `openssl dgst -sha256 -hmac "$SECRET" -binary | base64` and post it with
the four headers.
