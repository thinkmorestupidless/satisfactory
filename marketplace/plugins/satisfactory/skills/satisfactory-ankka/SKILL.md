---
name: satisfactory-ankka
description: Use satisfactory from an ankka application with the ankka-satisfactory library — SatisfactoryTools.forModel for agent tools generated from a model's catalog, a workflow that submits and pauses with thenPause until the webhook, an HttpEndpoint you write with Acl.AllowIf(SatisfactoryWebhook.looksSigned()) and SatisfactoryWebhook.verify, the ankka-workflow tag that routes the event back, and FakeSatisfactory for tests with nothing running. Use when an ankka agent, workflow or endpoint must submit a planning problem or receive its result.
---

# satisfactory from ankka

`ankka-satisfactory` (`"com.thinkmorestupidless" %% "ankka-satisfactory"`) gives an ankka
application three things and mounts nothing: agent tools generated from a model's catalog, a verifier
for satisfactory's webhook, and a fake for tests. The shape is fixed: **the agent decides and submits,
a workflow owns the wait, the webhook resumes the workflow.**

## Rules

1. **Tools come from the catalog.** `SatisfactoryTools.forModel(client, "employee-scheduling", "v1",
   tags)` returns `solve_<model>`, `get_best_solution_<model>` and `terminate_<model>` as
   `FunctionTool`s; give them to the agent effect with `.tools(...*)`. The `dataset` parameter's
   schema is the model's input schema, and arguments are checked against it before anything is sent.
2. **Tag every submit with the workflow.** Pass `List(SatisfactoryTools.workflowTag(workflowId))` as
   the tools' tags. The webhook event carries the dataset's tags, and
   `SatisfactoryWebhook.workflowOf(event)` reads the id back, so no mapping entity is needed.
3. **The wait is a workflow pause.** The step that consults the agent ends with
   `stepEffects...thenPause(after, timeoutStep.ref)`. A `solutionReady(datasetId)` command transitions
   to a step that fetches with the client. A duplicate delivery must find the work done and change
   nothing: check the state before transitioning.
4. **You write the endpoint.** `HttpEndpoint` with `acl = Acl.AllowIf(SatisfactoryWebhook.looksSigned())`
   and `postBody(path) { (body: Array[Byte]) => ... }` with `import
   satisfactory.ankka.SatisfactoryWebhook.given` for the byte-array body. Call
   `SatisfactoryWebhook.verify(secret, request, body)`; on `Right(Event.Dataset(event))` hand the
   dataset id to the workflow and return; on `Left(refusal)` throw `HttpProblem.unauthorized`.
   `looksSigned` checks headers and freshness only; `verify` checks the HMAC over the exact bytes.
5. **Registering the webhook is an operator's act.** The application never registers its own URL at
   startup; an admin creates the subscription on the platform API pointing at the exposed service.
6. **Test with `FakeSatisfactory`.** It answers the catalog always and everything else from a script
   in order (`expectSubmit`, `expectGet`, `expect`); an unscripted call is a loud 500 and
   `verifyScript()` fails on leftovers. `fire(event, url, secret)` posts a signed event to the
   endpoint under `AnkkaTestKit`. `FakeSatisfactory.describe(model)` builds a descriptor from a
   `SolverModel`; without a model on the classpath, construct a `ModelDescriptor`.
7. **The agent is built by the runtime and cannot be handed a client.** Hold the client where the
   agent can reach it (a module-level reference in the sample), or build the tools once at startup.

## Before writing

- Which workflow step consults the agent, which pauses, which fetches, and which handles the timeout?
- What does the endpoint answer, and how fast? It must return before doing work; the sender waits
  five seconds.
- Where does the subscription secret live? It must reach `verify` without being in the code.
- Is a duplicate delivery a no-op in the workflow?

## Mistakes to check for

- A tool call that polls or waits for the solution.
- `Acl.AllowAll` on the webhook endpoint, or reading the body as a `String` before verifying.
- Forgetting the `given` import for `FromBody[Array[Byte]]`.
- Registering the webhook from application code.
- A test that passes without `verifyScript()`.

## Reference files

Open the one a task needs; each is one topic and stands alone.

### Concepts

- `references/concepts/lineage.md` — Why a changed problem is a new dataset derived from the old one, the three ways to derive one, and what happens when the parent is still solving.
- `references/concepts/receiving-results.md` — The three ways a result reaches you — webhooks for production, server-sent events for development, polling as the fallback — and what each carries.

### Build

- `references/build/webhooks.md` — Register a webhook subscription on a tenant, verify a signed delivery, handle at-least-once delivery, filter what you receive, and read and retry the delivery log.
- `references/build/client.md` — Call satisfactory from Scala or any JVM language with satisfactory-client — submit a dataset, wait for or stream the result, derive a new dataset, and handle a refusal — with no ankka dependency.
- `references/build/ankka-applications.md` — Give an ankka agent tools generated from a model's catalog, let a workflow own the wait, receive satisfactory's webhook on an endpoint you write, and test all of it against a fake with nothing running.

### Reference

- `references/reference/model-api.md` — Every route of a model's API — datasets, demo data, the catalog, lineage, analysis and the event stream — with its query parameters, bodies, statuses and error codes.
- `references/reference/webhook-events.md` — The event types a subscription can receive, the envelope and the data of each, the headers on every delivery, and how the signature is computed.
- `references/reference/employee-scheduling.md` — The employee-scheduling model, version v1 — its input and output, constraints and weight fields, KPIs, validation issues, patch paths and demo datasets.
- `references/reference/vehicle-routing.md` — The vehicle-routing model, version v1 — its input and output, constraints and weight fields, KPIs, validation issues, patch paths and demo datasets.
