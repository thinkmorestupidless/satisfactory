# Divergences from Timefold

> Where satisfactory's API deliberately differs from the Timefold Platform model API it reimplements, and why, so a client written against Timefold's platform knows what to expect.

Source: https://satisfactory.ankka.cloud/reference/divergences/
satisfactory's public API is a reimplementation of the Timefold Platform's model API: the same
vocabulary, paths, statuses and delivery rules. The Timefold Platform has run as a product, and its
choices encode operational experience. Where satisfactory differs it is for one of the reasons in this
table.

| Timefold Platform | satisfactory | Why |
|---|---|---|
| Enterprise Solver: multi-threaded solving, nearby selection, a throttling consumer | Community Solver: `maxThreadCount` validated and capped at 1; its own report throttle | Licence. Enterprise features arrive only as a deliberate purchase |
| Score analysis with match counts and justifications | `{ score, constraints: [{ name, weight, score }] }`, each constraint's contribution computed by switching it off; `includeJustifications` answered with `"unsupported"` | `SolutionManager.analyze` is Enterprise-only in Timefold Solver 2.7 |
| Four commercial models | Two Apache-2.0 quickstart models behind an interface | satisfactory is the model author, not the model vendor |
| A managed cloud, or a self-hosted Kubernetes install | Two ankka services on any ankka installation | It is what ankka is for |
| Server-sent events with no documented resumption; `410` once final | `?after=<seq>` resumes exactly; `?follow=lineage` continues into a successor | Every event carries a journaled sequence number, so the receiver can always dedupe and the platform never has to drop |
| Dataset listing deprecated in favour of the UI | `GET /{entity}` kept, with status, tag and paging filters | An integrator needs to find datasets by API, and there is no UI |
| No idempotency on submit | `Idempotency-Key` honoured for 24 hours | A retried submit should not solve twice |
| `from-patch` in preview, behind a flag | Built in from the first version | It is the real-time planning story |
| No documented behaviour for deriving from a dataset that is still solving | Supersede: the parent completes with its best solution and names the child; the child warm-starts | Lineage preserved; a client should not have to terminate to change a problem |
| Webhooks configured in the UI; `X-Timefold-*` headers; a read timeout not retried | Subscriptions on the platform API; `X-Satisfactory-*` headers with the same signing scheme; every failure retried, with a stable event id to dedupe on | The ankka integration registers programmatically; at-least-once with dedupe loses nothing |
| Personal access tokens issued by the platform | Tokens from the identity provider ankka already uses | One identity, already verified |
| Webhook signing over the body or the path, the timestamp beside it | The timestamp is inside the signed string | A replayed request cannot carry a fresh timestamp |
| `POST /v1/demo-data` in the docs, `GET` in the spec | `GET` | The spec agrees |

## What is adopted unchanged

The dataset vocabulary and the ten statuses; typed integer weights per constraint; configuration
profiles referenced by id or name; `operation=NONE` and `POST /{id}`; the stateless score analysis;
metadata on the event stream and in webhooks, never solutions; terminate as `DELETE /{id}` returning
the dataset, `purge` as storage deletion, and restore while retention lasts; gzip with the same limits;
the delivery rules of five seconds, ten retries, ten seconds apart; and the advice to receivers to
answer at once and process asynchronously.
