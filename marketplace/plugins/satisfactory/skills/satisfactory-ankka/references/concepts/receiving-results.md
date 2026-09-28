# Receiving results

> The three ways a result reaches you — webhooks for production, server-sent events for development, polling as the fallback — and what each carries.

Source: https://satisfactory.ankka.cloud/concepts/receiving-results/
A submit answers at once with the dataset's metadata, and the solution arrives later. There are three
ways to learn that it has, and the rules for choosing are Timefold's:

| | Webhooks | Server-sent events | Polling |
|---|---|---|---|
| Recommended for production | **yes** | no | no |
| Recommended for development | no | **yes** | no |
| Needs a public endpoint on your side | yes | no | no |
| Holds an open connection | no | yes | no |
| Real-time progress | no | **yes** | no |

None of the three carries a solution. A webhook carries metadata and links, an event frame carries
metadata, and polling reads metadata. The solution is one `GET /{id}` away, made when the score says it
is worth making, because a dashboard wants the curve and not four megabytes a second.

## Webhooks

A tenant registers subscriptions: a URL, the event types it wants, optional filters on status, name,
tags and model, and how to sign. When a dataset reaches a final status satisfactory posts an event
envelope to every matching subscription, signed with HMAC-SHA256 and retried up to ten times, ten
seconds apart. Delivery is at-least-once, and every event carries an id and the dataset's sequence
number, so a receiver can drop a duplicate. The advice to receivers is to put the event on a queue,
answer `200`, and fetch the result asynchronously. [Receive webhooks](../build/webhooks.md) is the
guide.

## Server-sent events

`GET /{id}/events` is a `text/event-stream` of the dataset's metadata: one frame per recorded
improvement, at most one a second, with a heartbeat every fifteen seconds, until the status is final.
Every frame carries the dataset's sequence number, and `?after=<seq>` resumes exactly after a
disconnect, a gateway timeout or an API redeploy, because the sequence is journaled. A dataset that is
already final answers `410`; fetch it instead. [Stream progress](../build/streaming.md) is the guide.

## Polling

`GET /{id}/metadata` returns the dataset's metadata, and `solverStatus` says whether it is final. Start
with a short interval and lengthen it for a long solve; the client library's `awaitFinal` grows the
interval by half each time up to thirty seconds.

## What final means

Five statuses are final: `SOLVING_COMPLETED`, `SOLVING_INCOMPLETE`, `SOLVING_FAILED`,
`DATASET_INVALID`, and `DATASET_COMPUTED` for a dataset submitted with `operation=NONE`. The event
stream closes on them and the webhook fires on them. A terminate while solving is a success that keeps
its best solution: for a metaheuristic, "stop now and give me what you have" is normal.
[Dataset lifecycle](../reference/dataset-lifecycle.md) lists every status.
