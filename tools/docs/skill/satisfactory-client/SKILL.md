---
name: satisfactory-client
description: Call satisfactory from code — the Scala client (SatisfactoryClient, ModelClient, submit, awaitFinal, events, fromInput, fromPatch, SatisfactoryError) or plain HTTP with curl — including the submit request's termination and weights, polling, the server-sent event stream and resuming it with after, registering and verifying webhooks, configuration profiles, and lineage. Use when the task submits a dataset, reads a result, streams progress, receives a webhook, or handles a refusal from the API.
pages:
  - build/submit-and-poll.md
  - build/streaming.md
  - build/webhooks.md
  - build/configuration-profiles.md
  - build/client.md
  - concepts/lineage.md
  - concepts/receiving-results.md
  - reference/model-api.md
  - reference/webhook-events.md
  - reference/dataset-lifecycle.md
  - reference/employee-scheduling.md
  - reference/vehicle-routing.md
---

# satisfactory client

`satisfactory-client` (`"com.thinkmorestupidless" %% "satisfactory-client"`) is a typed client over
the JDK's HTTP client with no ankka dependency. `SatisfactoryClient(baseUrl, apiKey).model(key,
version)` gives a `ModelClient` whose paths come from the model's descriptor. Everything it does is
also one HTTP call, documented on the model API reference page.

## Rules

1. **Submit, then wait elsewhere.** `submit` returns `Metadata` with the id at once. Wait with
   `awaitFinal(id)` (polling, growing interval), `events(id)` (a blocking iterator of `StreamFrame`,
   metadata only), or a webhook. Fetch the solution with `get(id)` when the status or score says to.
2. **Put the length of the solve in `config.run.termination`.** `spentLimit` and
   `unimprovedSpentLimit` are ISO-8601 strings (`"PT5M"`). With nothing set the solve stops on
   diminishing returns. Weights go in `config.model.overrides` as `<key>Weight` integers; the keys are
   in the model's descriptor and its reference page.
3. **Change a problem by deriving.** `fromInput(id, select, config)` re-solves from the parent's input
   (`UNSOLVED`) or output (`SOLVED`); `fromPatch(id, patch, select, config)` applies `PatchOp`s
   (`add`, `remove`, `replace`; paths like `/employees/[name=Ann]/skills`, `/shifts/-`). Both return
   the child's metadata. Deriving from an active parent supersedes it.
4. **Every error is `SatisfactoryError`** with `status` and `info: ErrorInfo { id, code, message,
   details }`. `validation` on 400 carries each schema problem in `details`. A 401 from ankka's ACL
   has code `http-401`.
5. **Resume a stream with `after`.** `events(id, after = Some(seq))`. The iterator reconnects itself
   from the last `seq`; it ends after the final frame, and ends at once on a final dataset. Frames are
   `Update(seq, metadata, skipped)` or `Heartbeat`. Over raw HTTP each `data:` payload is a JSON string
   holding the frame: decode twice.
6. **Webhooks are registered on the platform API by a tenant admin**, not by the client, which has no
   webhook methods. The receiver verifies HMAC-SHA256 over `<timestamp>.<body>` with the
   subscription's `whsec_` secret, refuses a timestamp over five minutes old, answers 2xx fast, and
   dedupes on the event `id`.
7. **Use `Idempotency-Key` for a submit that may be retried**, `SubmitOptions(idempotencyKey = ...)`.
   `gzip = true` compresses the body.

## Before writing

- Which model, and therefore which entity path, weight fields and input shape? Read the model's
  reference page.
- How will the result arrive: polling, stream, or webhook? Choose before writing the submit, because a
  webhook needs tags to route by and a subscription registered by an admin.
- Is the input already a solution to continue from, or a fresh problem? That decides `fromInput`'s
  `select` and whether to send `employee`/`vehicle` assignments in the input.

## Mistakes to check for

- Waiting for the solution inside a request handler or a tool call.
- Parsing a stream frame once, or expecting `id:` lines for `Last-Event-ID`.
- Treating a 410 on `/events` as an error: the dataset is final, fetch it.
- Sending weights as a map of names; they are `<key>Weight` fields on `overrides`.
- Setting `maxThreadCount` above 1 and expecting parallelism.
