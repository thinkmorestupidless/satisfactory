---
title: Stream progress
description: Follow a dataset's improving score over server-sent events, resume exactly after a disconnect, filter by status, and follow a superseded dataset into its successor.
kind: guide
related: [concepts/receiving-results.md, build/client.md, concepts/lineage.md, reference/model-api.md]
---

# Stream progress

`GET /{entity}/{id}/events` is a `text/event-stream` of the dataset's metadata: one frame per recorded
improvement, and one for each status change, until the status is final. It is the way to watch a
solve during development, and the source for a dashboard that draws the score curve. It holds a
connection open for the whole solve, which is why it is not the production way to receive a result;
[Receive webhooks](webhooks.md) is.

## The frames

```bash
curl -sN -H "X-API-KEY: $KEY" $API/schedules/$ID/events
```

```text
data: "{\"seq\":7,\"metadata\":{\"id\":\"ds_01K6…\",\"solverStatus\":\"SOLVING_ACTIVE\",\"score\":\"-3hard/-2310soft\", …}}"
data: "{\"seq\":8,\"metadata\":{\"id\":\"ds_01K6…\",\"solverStatus\":\"SOLVING_ACTIVE\",\"score\":\"0hard/-2085soft\", …}}"
data: "{\"heartbeat\":true}"
data: "{\"seq\":19,\"metadata\":{\"id\":\"ds_01K6…\",\"solverStatus\":\"SOLVING_COMPLETED\",\"score\":\"0hard/-1830soft\", …}}"
```

Each `data:` payload is a JSON string, so a reader decodes it twice: the string, then the frame in it.
A frame is `{ seq, metadata, skipped? }` or `{ heartbeat: true }`:

| Field | Meaning |
|---|---|
| `seq` | the dataset's sequence number for this frame; what `after` resumes from |
| `metadata` | the dataset's metadata: status, score, timestamps, and `supersededBy` when it applies |
| `skipped` | present on the first frame when `after` named a sequence number the dataset no longer holds; how many were missed |
| `heartbeat` | a keep-alive, every fifteen seconds while nothing else is sent |

Frames carry metadata, never solutions. Fetch `GET /{id}` when the score says it is worth fetching.

The stream sends at most one frame a second, latest wins, and every frame's score is at least as good
as the one before, because the worker throttles its reports and the dataset entity records only a
strictly better score. The final frame is never dropped. The stream ends after the frame carrying a
final status.

## Resume

Every frame's sequence number is journaled, so `?after=<seq>` resumes exactly after a dropped
connection, a gateway timeout or an API redeploy:

```bash
curl -sN -H "X-API-KEY: $KEY" "$API/schedules/$ID/events?after=8"
```

The entity keeps a bounded ring of recent updates. If `after` names a sequence number older than the
ring holds, the stream opens with the current best and says `"skipped": n` on that frame, so a reader
knows it did not see everything and can fetch the dataset.

## A dataset that is already final

Opening the stream on a final dataset answers `410 Gone` with a message naming the status, unless
`after` is given, in which case the frames after it are sent and the stream ends. Fetch the dataset
instead.

## Filter by status

`?status=SOLVING_ACTIVE&status=SOLVING_COMPLETED`, repeatable, sends only frames whose status is one of
those. The final frame is sent regardless of the filter.

## Follow lineage

Deriving a new dataset from one that is still solving supersedes it: the parent completes and its
metadata names the child in `supersededBy`. With `?follow=lineage` the parent's stream sends that final
frame and continues with the child's frames instead of ending, so a dashboard following a plan across
changes keeps one connection.

## From the client

The client library's `events` method returns a blocking iterator of typed frames that reconnects from
the last sequence number it saw, and `eventsPublisher` the same frames as a reactive streams publisher.
[Use the client](client.md) shows both.

## A browser

A browser's `EventSource` receives the frames but must parse each payload as a JSON string first, and
it cannot resume automatically, because the frames carry no `id:` field for `Last-Event-ID`; pass
`after` on reconnect instead. See [Limitations](../reference/limitations.md).
