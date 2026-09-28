---
title: Webhook events
description: The event types a subscription can receive, the envelope and the data of each, the headers on every delivery, and how the signature is computed.
kind: reference
related: [build/webhooks.md, build/ankka-applications.md, reference/platform-api.md]
---

# Webhook events

Every delivery is a `POST` with a JSON envelope and four `X-Satisfactory-*` headers.

## Event types

| Type | Fires when |
|---|---|
| `dataset.computed` | a dataset submitted with `operation=NONE` reaches `DATASET_COMPUTED` |
| `dataset.invalid` | the model's validation found an error |
| `dataset.completed` | solving ended with a solution, by termination, terminate or supersession |
| `dataset.incomplete` | solving was stopped with no solution |
| `dataset.failed` | an error stopped solving |
| `webhook.failing` | another subscription of the tenant has exhausted its retries |

A subscription's `events` may name types, or `dataset.*` for the five dataset types. The
`model.*` and `tenant.*` namespaces are reserved and emit nothing.

## The envelope

```json
{ "id": "evt_ds_01K6…_19", "type": "dataset.completed", "occurredAt": "2026-09-28T10:15:34Z", "tenant": "t_acme",
  "data": { … } }
```

| Field | Meaning |
|---|---|
| `id` | the event's id, stable across retries: `evt_<datasetId>_<seq>` for a dataset event |
| `type` | the event type |
| `occurredAt` | when the dataset reached the status |
| `tenant` | the tenant the dataset belongs to |
| `data` | the event's data, by type |

## Dataset events

`data` for the five `dataset.*` types:

| Field | Meaning |
|---|---|
| `id`, `name`, `tags` | the dataset |
| `model`, `modelVersion` | which model's API it belongs to |
| `status` | the final status |
| `score` | the best score, or `null` |
| `seq` | the dataset's sequence number at the event |
| `parentId`, `originId`, `supersededBy` | its lineage |
| `runLink`, `outputLink` | the dataset's URL on the model API, rooted at the installation's public URL; fetch it with an API key |

The solution is never in the event.

## The failing event

`data` for `webhook.failing`: `{ subscriptionId, url, failedEventId, failedEventType, attempts,
lastStatus, lastError }`. It is sent to the tenant's other subscriptions that listen for it, at most
once every two hours per failing subscription. The failed delivery stays in the log and can be retried.

## Headers

| Header | Value |
|---|---|
| `X-Satisfactory-Signature` | the HMAC, Base64 |
| `X-Satisfactory-Timestamp` | when the delivery was signed, ISO-8601 |
| `X-Satisfactory-Event` | the event type |
| `X-Satisfactory-Delivery` | the delivery's id in the log, the same on every retry |
| `Content-Type` | `application/json` |

A subscription's custom `headers` are sent too, with `{hmac_signature}` and `{hmac_timestamp}` replaced.

## The signature

```text
signature = base64( HMAC-SHA256( secret, timestamp + "." + payload ) )
```

`payload` is the request body's bytes when the subscription signs over `body`, the default, or the
request's raw path when it signs over `path`. A receiver refuses a missing header, a timestamp more than
five minutes from its own clock, and a mismatch, comparing in constant time. The secret is the
`whsec_…` value shown when the subscription was created or its secret rotated.

## Delivery

Each attempt waits five seconds for an answer, and any `2xx` is delivered. Otherwise the delivery is
retried after ten seconds, up to ten attempts, including after a read timeout. Delivery is therefore
at-least-once, and a receiver dedupes on `id`.
