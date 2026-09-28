# Feature Specification: Constraint Solving as a Service (satisfactory)

**Feature Branch**: `001-constraint-solving-service`

**Created**: 2026-09-26

**Status**: Draft

**Input**: User description: "satisfactory: constraint solving as a service built on Timefold Solver and deployed as ankka services. Full specification derived from DESIGN.md and API.md in this repo — model catalog with an SPI, immutable datasets with lineage (from-input, from-patch, supersede), the ten Timefold solverStatus values, an event-sourced entity per dataset with lease fencing and monotonic seq, a pull-based runner pool with warm-start recovery, results by webhook / SSE / polling, tenants with API keys, quotas, concurrency slots and configuration profiles, score analysis and validation catalogs, retention/purge/restore, and the satisfactory-client and ankka-satisfactory libraries. Phases 1–4 of the build order; phase 5 (dedicated pods, BYO models) is out of scope."

**Source documents**: [`DESIGN.md`](../../DESIGN.md) (system shape, dataset lifecycle rules, worker protocol, hosting, artefacts, build order) and [`API.md`](../../API.md) (the public contract, adopted from the Timefold Platform model API with listed divergences). Where this specification and those documents disagree, this specification states the intended behaviour and the documents should be updated.

## Overview & Scope

**satisfactory** lets a caller submit a planning problem (a roster to fill, a set of deliveries to route) and get back a solution, or follow a stream of ever-better solutions as the optimiser works. Solving is done by a catalog of ready-made **models**; the caller supplies only the data, how long to solve, and how much each constraint matters. The service is multi-tenant, runs on the ankka platform, and is consumed either directly over its HTTP API or from an ankka application through a published integration library.

**In scope** (build-order phases 1–4):

- Submitting, validating, scoring and solving datasets against catalog models
- Following progress live, stopping early, and keeping the best solution found
- Changing a problem by deriving a new dataset from an existing one (lineage), including while the parent is still solving
- A pool of solving workers that survives worker failure and rolling deployments without losing recorded progress
- Tenants: API keys, quotas, concurrency limits, priority queueing, configuration profiles, rate limits
- Result delivery by webhook, live stream and polling
- Validation issue catalogs, score analysis, KPIs, input metrics, demo datasets
- Dataset listing, metadata editing, retention, purge and restore
- Operator visibility and control: exported metrics, a cross-tenant read view for platform operators, force-terminate, worker drain, and per-tenant queue pause
- Two published libraries: a plain client, and an ankka integration library with catalog-generated agent tools, a webhook verifier and a test double

**Out of scope** (phase 5 and beyond): dedicated worker pods per solve, bring-your-own-model (uploading model code), multi-threaded solving, model-specific extra routes (recommendations, assignability analysis), a user interface, and an operator CLI.

## Clarifications

### Session 2026-09-26

- Q: Which roles can a tenant API key carry? → A: Two roles: read-only and read-write.
- Q: How does a tenant come to exist and who administers it? → A: A platform-level operator creates tenants and names the first administrator; tenants have members (admin or member) keyed by identity-provider user id; tenant administrators manage membership; platform API calls are authorised by membership.
- Q: How are tenant administrators notified of persistent webhook failure? → A: No email. A `webhook.failing` system event is emitted to any of the tenant's other subscriptions that opt into it, at most once per two hours per failing subscription, and the failure is recorded in the delivery log.
- Q: What visibility and control do platform operators get? → A: Exported operational metrics, a platform-operator-only read API across tenants (datasets with status, worker, lease and attempt; the worker pool with slots), and operator actions: force-terminate any dataset, drain a worker, pause and resume a tenant's queue.
- Q: How sensitive are submitted inputs and solutions? → A: Confidential by default: never written to logs or shown in cross-tenant operator views (operators see metadata only); purge and retention expiry physically delete input and solution bodies; encryption at rest is a deployment concern.
- Q (planning, 2026-09-26): Timefold Solver 2.7 Community has no score analysis API (Enterprise-only). → A: Stay on Community. Score analysis reports total score and per-constraint contribution computed by ablation; match counts and justifications are out of scope until an Enterprise licence is a deliberate purchase.
- Q (planning, 2026-09-26): ankka's tool builder cannot carry a raw JSON schema as a parameter schema. → A: The generated solve tool validates its dataset argument against the model's input schema inside the tool and describes the schema to the model; exposing the schema as the parameter schema follows when ankka supports it. **Resolved at implementation (2026-09-28):** ankka's `SchemaType` is an open type class, so the solve tool's parameter schema is the model's input schema (references inlined), and arguments are also validated against it before anything is sent.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Submit a planning problem and get a solution (Priority: P1)

An integrator holds a tenant API key. They pick a model from the catalog, read its input schema and constraint list, and submit a dataset: the model's input plus, optionally, how long to solve and which constraint weights to change. They receive a dataset id immediately. They poll the dataset's status until it reaches a final state, then fetch the best solution together with its score, KPIs and input metrics. They can also submit a dataset for validation and scoring only, and solve it later.

**Why this priority**: This is the product. Everything else refines how results arrive or how the problem changes. A caller who can only submit, poll and fetch already has a usable optimisation service.

**Independent Test**: Submit the employee scheduling model's demo dataset with a 30-second solve limit, poll until final, fetch the solution. Passes if a feasible schedule with a score, KPIs and input metrics is returned. Needs no streaming, webhooks, lineage or worker pool.

**Acceptance Scenarios**:

1. **Given** a valid dataset for a catalog model, **When** it is submitted with the solve operation, **Then** the caller receives an acceptance response within seconds containing the dataset's metadata (id, status, submit time), and the dataset subsequently passes through scheduled, validated, computed, started and active statuses before reaching completed.
2. **Given** a submission whose body does not match the model's input schema, **When** it is submitted, **Then** it is rejected immediately with the specific schema errors and no dataset is created.
3. **Given** a submission that matches the schema but fails the model's own validation (for example a shift referencing an employee that does not exist), **When** it is submitted, **Then** a dataset is created, ends in the invalid status, and its validation result lists each issue with its catalog code, severity and the offending entities.
4. **Given** a dataset submitted with the "no solve" operation, **When** it is processed, **Then** it is validated and scored as submitted, stops at the computed status, and a later solve request on that dataset starts solving from there.
5. **Given** a dataset that is still solving, **When** the caller fetches it, **Then** the response contains the best solution found so far, its score, and the current status.
6. **Given** a dataset submitted with a maximum solve duration, **When** that duration elapses, **Then** solving ends, the dataset is completed, and the completion timestamp is recorded.
7. **Given** a submission carrying an idempotency key that was already used by this tenant, **When** it is submitted again, **Then** the original dataset's metadata is returned and no second dataset is created or solved.
8. **Given** a caller with no key, a wrong key, or a key lacking the right to submit, **When** they submit, **Then** they receive an unauthenticated or forbidden response respectively and nothing is created.
9. **Given** the model catalog, **When** the caller asks for a model, **Then** they receive its input schema, output schema, constraint list with default weights, patch paths, KPI descriptors, validation issue types, maturity level and version.
10. **Given** a model with demo datasets, **When** the caller lists them, **Then** each has an id, short and long description, tags and suggested configuration, and can be retrieved as a full request or as input only.

---

### User Story 2 - Follow progress live and stop when it is good enough (Priority: P2)

A developer watches a solve as it runs. They open a stream of status-and-score updates for a dataset and see the score improve. When the score is good enough, or they are out of time, they terminate the solve and immediately get the best solution found. If their connection drops, they reconnect from the last update they saw and miss nothing.

**Why this priority**: Real-time feedback is what makes this more than a batch job queue, and terminate-and-keep-best is the normal way to use a metaheuristic. Both the streaming and the terminate behaviour are visible to every later story.

**Independent Test**: Submit a dataset with a five-minute limit, open the stream, observe several improving score frames, disconnect, reconnect after the last seen sequence number, confirm continuity, then terminate and fetch the solution. Passes without webhooks, lineage or a worker pool.

**Acceptance Scenarios**:

1. **Given** a dataset that is solving, **When** the caller opens its event stream, **Then** they receive one frame per recorded improvement containing the dataset's metadata (status and score, never the solution body), at most one frame per second, and the stream closes after the frame that carries a final status.
2. **Given** an open stream on a solve that is quiet for a long time, **When** no improvement is found for more than the gateway's idle timeout, **Then** the connection stays open (the stream sends keep-alives).
3. **Given** a caller who disconnected after seeing update N, **When** they reopen the stream asking for updates after N, **Then** they receive exactly the updates recorded after N, with no gaps and no repeats.
4. **Given** a reconnect asking for updates older than the retained window, **When** the stream opens, **Then** it begins with the current best and states how many updates were skipped.
5. **Given** a dataset already in a final state, **When** a stream is requested, **Then** the request is refused with a "gone" response and the caller is expected to fetch the dataset instead.
6. **Given** a stream with a status filter, **When** updates occur, **Then** only frames whose status matches the filter are sent.
7. **Given** a dataset that is solving and has at least one recorded solution, **When** the caller terminates it, **Then** solving stops within the heartbeat interval, the dataset becomes completed, the best solution is kept, and the terminate response carries the dataset.
8. **Given** a dataset that is solving and has no recorded solution yet, **When** it is terminated, **Then** the dataset becomes incomplete.
9. **Given** a dataset already in a final state, **When** it is terminated, **Then** the dataset is returned unchanged.
10. **Given** any sequence of frames for one dataset, **When** compared in order, **Then** every score is strictly better than the previous one (the stream is monotonic).

---

### User Story 3 - Change the problem without starting over (Priority: P2)

A planner's world changes: an employee calls in sick, a delivery is added. The integrator derives a **new** dataset from the existing one, either by resubmitting its input or solved output with a new configuration, or by applying a patch (add, remove, replace at a path). The new dataset keeps the previous assignments where it can, so the plan changes as little as possible. If the previous dataset was still solving, deriving from it supersedes it: the parent finishes with its best so far and the child continues from there. Every derived dataset records its parent and the root of its chain.

**Why this priority**: This is the real-time planning story and the reason there is no mutable job. It is the headline capability after basic solving and streaming.

**Independent Test**: Solve a demo dataset to completion; derive a child by patching one shift; confirm the child starts from the parent's assignments and reports a disruption KPI; then derive from a still-active parent and confirm supersession. Needs Story 1 only.

**Acceptance Scenarios**:

1. **Given** a completed dataset, **When** the caller derives a new dataset from its input with a different configuration, **Then** a new dataset is created with the parent's id and the chain's origin id, solves from scratch, and the parent is unchanged.
2. **Given** a completed dataset, **When** the caller derives from its solved output, **Then** the new dataset starts from the parent's assignments and continues improving rather than starting from scratch.
3. **Given** a completed dataset, **When** the caller applies a patch selecting the solved state, **Then** the patch is validated against the schema before creation and by the model afterwards, existing assignments are preserved where still valid, and the disruption KPI reports how much of the plan changed.
4. **Given** a patch with a path that resolves to nothing, or an operation that produces schema-invalid input, **When** it is submitted, **Then** it is rejected with the offending operation identified and no dataset is created.
5. **Given** a dataset that is still solving, **When** a child is derived from it, **Then** the parent completes with its best solution so far and records the child's id as its successor, and the child starts from that best solution (with the patch applied, if any).
6. **Given** a stream opened on the parent with lineage following enabled, **When** the parent is superseded, **Then** the stream emits the parent's final frame and continues with the child's frames instead of closing.
7. **Given** a dataset in the invalid status, **When** the caller patches its input (unsolved selection) to fix the problem, **Then** a new dataset is created and validated afresh.
8. **Given** a dataset with no solution (invalid, or never solved), **When** the caller asks to derive from its solved state, **Then** the request is rejected with an explanation.
9. **Given** a derived dataset, **When** its resolved configuration is fetched, **Then** it reflects, in priority order, the derivation's own configuration, the named profile, the parent's configuration, model defaults, and plan maximums.

---

### User Story 4 - Solving survives worker failure and deployments (Priority: P3)

An operator runs a pool of solving workers. A worker crashes, is partitioned from the network, or is replaced during a rolling deployment while holding a solve. The solve continues on another worker from the best solution already recorded. A worker that was presumed dead and comes back cannot overwrite its replacement's progress. An API deployment never interrupts a running solve.

**Why this priority**: Without this, a two-hour solve is lost to a routine deploy. It is what makes the service trustworthy in production, and it must be proven by killing things mid-solve.

**Independent Test**: Start ten solves, kill one worker mid-solve, redeploy the API service, then drain and replace all workers. Passes if every solve completes, no dataset's recorded score ever regresses, and no report from a replaced worker is accepted.

**Acceptance Scenarios**:

1. **Given** a worker with a free slot, **When** it asks for work, **Then** it is handed the highest-priority eligible dataset for a model it supports, together with a lease with a time limit, and no other worker can be handed the same dataset while that lease is valid.
2. **Given** a worker holding a lease, **When** it stops sending heartbeats for longer than the lease limit, **Then** the lease expires, the dataset is re-queued with its best solution as a warm start, and another worker may claim it.
3. **Given** a dataset re-queued after a lost lease, **When** the new worker begins, **Then** it starts from the recorded best solution rather than from an empty plan, and reports that merely equal the best are not recorded.
4. **Given** a worker whose lease has been replaced, **When** it reports a solution, heartbeat or completion, **Then** the report is ignored and the worker is told to stop that solve.
5. **Given** a dataset that has lost its lease three times, **When** the third loss occurs, **Then** the dataset is marked failed with a message, and its best solution so far remains readable.
6. **Given** a worker that raises a deterministic error while solving (for example the model cannot build the problem), **When** it reports the failure, **Then** the dataset is marked failed with the message and is not retried.
7. **Given** workers being replaced in a rolling deployment, **When** a worker receives its stop signal, **Then** it stops its solves, records their latest best solutions, releases their leases so they are re-queued, and exits well inside the platform's grace period.
8. **Given** solves in progress, **When** the API service is redeployed, **Then** no solve is interrupted and streams resume where they left off once the API is back.
9. **Given** more eligible datasets than free worker slots, **When** workers are all busy, **Then** datasets wait in the scheduled status and start as slots free up, in priority order within each tenant.
10. **Given** any dataset, **When** its solve exceeds the hard lifetime ceiling regardless of requested limits, **Then** solving is stopped and the dataset is completed (or incomplete if no solution exists).

---

### User Story 5 - Administer a tenant: keys, limits, profiles (Priority: P4)

A tenant administrator, authenticated with the platform's identity, creates and revokes API keys with roles, sets the tenant's concurrency limit and quotas, and defines named configuration profiles (termination settings, constraint weights, resources) so integrators can reference "fast" or "overnight" instead of repeating configuration. Submissions beyond the concurrency limit queue rather than fail; submissions beyond rate limits or quotas are refused with a clear response.

**Why this priority**: Turns a working service into something several teams can share safely. It is required before any external party is given access.

**Independent Test**: Create a tenant, two keys with different roles, a profile with a custom weight, and a concurrency limit of one. Submit two datasets with the profile; confirm the second queues, the profile's weight appears in the resolved configuration, and the read-only key cannot submit.

**Acceptance Scenarios**:

1. **Given** a tenant administrator, **When** they create an API key with a role (read-only or read-write), **Then** the key is shown once, stored only in hashed form, and thereafter authenticates callers with exactly that role's permissions; a read-only key is refused as forbidden on every operation that creates, changes, terminates, purges or restores a dataset.
2. **Given** a revoked key, **When** it is used, **Then** requests are refused as unauthenticated.
3. **Given** a tenant with a concurrency limit of N, **When** it has N datasets solving and submits another, **Then** the new one waits in the scheduled status and starts when one of the N finishes.
4. **Given** datasets waiting in a tenant's queue with different priorities (0–10), **When** a slot frees, **Then** the highest priority starts first; ties go to the earliest submission.
5. **Given** a tenant that exceeds its rate limit or quota, **When** it submits, **Then** it receives a "too many requests" response with the reason, and nothing is created.
6. **Given** a model, **When** an administrator creates a configuration profile, **Then** it has a name, description, the profile it derives from, run configuration, model weights and resource settings; up to fifty profiles per model are allowed; a read-only Standard profile is always present and is the default.
7. **Given** a submission referencing a profile by id or by name, **When** its resolved configuration is read, **Then** the submission's own settings override the profile's, which override the parent's (if derived), model defaults, and finally plan maximums are enforced.
8. **Given** a submission requesting more threads than the plan allows, **When** it is accepted, **Then** the resolved configuration shows the capped value.
9. **Given** a caller with a key, **When** they ask who they are, **Then** they receive their tenant and roles.
10. **Given** a platform operator, **When** they create a tenant naming its first administrator, **Then** the tenant exists with that one admin member; a principal who is not a platform operator is refused.
11. **Given** a tenant administrator, **When** they add a member by identity-provider user id with the member role, **Then** that principal can read the tenant's configuration and delivery log but is refused when trying to change keys, limits, profiles, webhooks or membership.
12. **Given** a principal who is not a member of a tenant, **When** they call any platform API operation on that tenant, **Then** they are refused and learn nothing about it.
13. **Given** a tenant, **When** any caller with a different tenant's key tries to read, list, stream, derive from, terminate or purge its datasets, **Then** the request is refused as not found or forbidden and nothing leaks.

---

### User Story 6 - Receive results by webhook (Priority: P4)

A production integrator does not want to hold connections open or poll. They register a webhook subscription for their tenant, filtered by event type, status, dataset name pattern, tags or model. When a dataset reaches a final state, the service posts a signed notification carrying metadata and links, never the solution. The receiver verifies the signature and timestamp, acknowledges immediately, and fetches the result with its own key. Delivery is at-least-once with retries; the receiver can dedupe. Administrators can see a delivery log and retry entries.

**Why this priority**: Webhooks are the recommended production delivery method and the mechanism the ankka integration (Story 9) relies on to resume a workflow.

**Independent Test**: Register a subscription pointing at a test receiver, solve a dataset, confirm one signed notification arrives with the final status and links. Then take the receiver down for a minute and confirm the notification still arrives afterwards.

**Acceptance Scenarios**:

1. **Given** a subscription for dataset completion events, **When** a dataset reaches completed, **Then** the receiver gets one notification containing an event id, type, occurrence time, tenant, the dataset's metadata, the per-dataset sequence number, and links to the dataset and its output.
2. **Given** a subscription with filters, **When** events occur that do not match, **Then** nothing is sent.
3. **Given** any notification, **When** the receiver inspects it, **Then** it carries a signature over the body (or the path, per the subscription's setting) and a timestamp header, and the receiver can verify both with the subscription's secret.
4. **Given** a receiver that does not answer with success within the delivery timeout, **When** delivery fails, **Then** the service retries up to ten times at ten-second intervals, including when the failure was a read timeout.
5. **Given** a notification delivered twice, **When** the receiver compares event ids or sequence numbers, **Then** it can identify the duplicate.
6. **Given** persistent delivery failure on subscription S, **When** all retries are exhausted, **Then** the failure is visible in the delivery log and a `webhook.failing` event naming S is delivered to each of the tenant's *other* subscriptions that opted into that event type, at most once per two hours for S; no email is sent, and a failing subscription never receives its own failure event.
7. **Given** the delivery log, **When** an administrator views it, **Then** each entry shows the event, target, attempts, outcomes and timestamps for the last thirty days, and can be retried individually.
8. **Given** the five final statuses (computed via the no-solve operation, invalid, completed, incomplete, failed), **When** each occurs, **Then** the corresponding event type is emitted.

---

### User Story 7 - Understand and trust the result (Priority: P4)

An analyst wants to know why a plan scores the way it does and how good the plan they already have is. They fetch a dataset's score analysis, broken down by constraint with weights, match counts and (on request) per-match justifications. They score a plan of their own without creating a dataset. They read the model's catalog of validation issue types to build their own error handling, and the dataset's typed KPIs and input metrics to build a dashboard.

**Why this priority**: Trust in an optimiser depends on explainability. This is required for a product, not for a demo.

**Independent Test**: Score a demo dataset's input via the stateless scoring call, solve it, fetch score analysis with justifications, and compare the two scores. Passes if every constraint appears with weight and score contribution, and a request for justifications is answered as unsupported rather than failing.

**Acceptance Scenarios**:

1. **Given** a dataset with a solution, **When** score analysis is requested, **Then** the response lists the total score and, per constraint, its name, weight and contribution to the score.
2. **Given** score analysis requested with justifications, **When** it is returned, **Then** the request is accepted and the response states that justifications are unsupported in this edition; no error is raised.
3. **Given** a plan the caller already has, **When** they request stateless score analysis against a profile, **Then** they receive the same analysis structure and no dataset is created.
4. **Given** a dataset submitted with the no-solve operation, **When** it reaches computed, **Then** its score, KPIs and input metrics reflect the plan as submitted, so optimisation gain can be measured against the later solved result.
5. **Given** a model, **When** its validation issue types are listed, **Then** each has a code, severity, message metadata and its own schema of fields; each can also be fetched by code.
6. **Given** a dataset, **When** its KPIs and input metrics are read, **Then** each field is typed and carries a title, description, priority and example as declared by the model.
7. **Given** a dataset, **When** its logs are requested, **Then** the caller (a key of the owning tenant) receives the solve's diagnostic details; service-side logs never contain input or solution content.

---

### User Story 8 - Manage datasets over their lifetime (Priority: P4)

An integrator lists their tenant's datasets filtered by status and tag, renames and re-tags datasets after submission, inspects the exact request and resolved configuration that were used, purges datasets they no longer need, and restores one purged by mistake while retention allows.

**Why this priority**: Housekeeping and auditability. Needed by anyone operating against the service for more than a day.

**Independent Test**: Submit three datasets with distinct tags, list by tag, rename one, purge one, restore it, then confirm purge is refused while a fourth is solving.

**Acceptance Scenarios**:

1. **Given** a tenant's datasets, **When** listed with status, tag and paging parameters, **Then** only that tenant's matching datasets are returned, paged.
2. **Given** a dataset, **When** its name or tags are updated, **Then** the change is reflected in subsequent metadata; names over 255 characters, more than 100 tags, or duplicate tags are rejected.
3. **Given** a dataset, **When** the caller fetches its input, its full original request, or its resolved configuration, **Then** each is returned exactly as submitted or resolved at the time.
4. **Given** a dataset in a final state, **When** it is purged, **Then** its input and solutions are no longer retrievable and the dataset reports as purged.
5. **Given** a dataset that is solving, **When** purge is requested, **Then** it is refused.
6. **Given** a purged dataset within the retention window, **When** restore is requested, **Then** it becomes retrievable again.
7. **Given** a purged dataset whose retention has expired, **When** restore is requested, **Then** it is refused as no longer available.
8. **Given** any dataset, **When** the retention period after its final state elapses, **Then** its stored input, patches and solutions are physically deleted; its metadata remains listable.

---

### User Story 9 - Integrate from an ankka application (Priority: P5)

An ankka application developer adds one library dependency. From the model catalog they obtain agent tools whose parameters are the model's own input schema, so an agent can submit a solve and be validated by the same rules the service applies. Their workflow submits through the agent, pauses, and is resumed by a webhook that the library verifies and decodes; the developer writes the receiving endpoint themselves. In tests, a scripted in-memory fake stands in for the service and fires webhooks locally, so nothing needs to be running.

**Why this priority**: This is the reason the product exists inside the ankka ecosystem. It depends on Stories 1, 5 and 6 being complete.

**Independent Test**: In a fresh ankka project, add the dependency, generate tools for the employee scheduling model, write a workflow that submits via the agent and awaits the webhook, and run the test suite against the fake. Passes if the workflow reaches its result step with the solution, with no satisfactory deployment running.

**Acceptance Scenarios**:

1. **Given** a model id and version, **When** the developer requests tools, **Then** they receive a submit tool whose parameter schema is the model's input schema, a get-best-solution tool and a terminate tool.
2. **Given** an agent that calls the submit tool, **When** the arguments do not match the input schema, **Then** the call is rejected before anything is sent.
3. **Given** a submit made through the tools inside a workflow or session, **When** the dataset is created, **Then** it is tagged with the workflow or session id so the eventual webhook can route itself back.
4. **Given** a webhook request arriving at the developer's endpoint, **When** the library's check is used as the endpoint's access rule, **Then** unsigned or stale requests are refused before the body is processed.
5. **Given** a signed webhook, **When** the library verifies it, **Then** it returns a typed event on success and refuses on a bad signature.
6. **Given** a duplicate webhook, **When** the workflow receives it, **Then** it can treat it as a no-op.
7. **Given** the fake service in a test, **When** the scripted responses run out, **Then** the test fails loudly rather than silently returning defaults.
8. **Given** the plain client library, **When** it is used outside ankka, **Then** it depends on nothing from the ankka platform and supports submit, poll, fetch, stream and terminate.

---

### User Story 10 - Operate the service (Priority: P3)

A platform operator watches the whole installation, not one tenant. They see queue depth per model, how many worker slots are free and busy, how often leases are lost and solves re-queued, and which webhooks are failing, through metrics the hosting platform's monitoring can scrape. They can list datasets across all tenants with their status, the worker holding them, the lease and the attempt count, and list the worker pool with its slots. When something is wrong they can force-terminate any dataset, drain a worker ahead of maintenance so it takes no new work and releases its solves gracefully, and pause a tenant's queue so nothing new of theirs starts until it is resumed.

**Why this priority**: Story 4's resilience guarantees cannot be verified in production, or acted on, without this. It sits with the worker pool in priority because it is how the pool is proven and run.

**Independent Test**: With two tenants solving, read the metrics and the cross-tenant list, drain one worker and confirm its solves re-queue and complete elsewhere, pause one tenant and confirm its queued datasets do not start while the other tenant's do, then force-terminate a dataset and confirm it finalises with its best.

**Acceptance Scenarios**:

1. **Given** the service is running, **When** the platform's monitoring scrapes it, **Then** it obtains at least: queued datasets per model, free and busy worker slots, lease losses and re-queues, solves completed and failed, and webhook delivery failures, each labelled by tenant where applicable.
2. **Given** a platform operator, **When** they list datasets across tenants, **Then** each row shows tenant, model, status, priority, submit time, best score, holding worker, lease epoch and attempt count, filterable by tenant, model and status; no input or solution content is ever shown; a non-operator is refused.
3. **Given** a platform operator, **When** they list workers, **Then** each shows its id, supported models, slot count, busy slots and the datasets it holds.
4. **Given** a dataset in any tenant, **When** an operator force-terminates it, **Then** it finalises immediately with its best solution (completed or incomplete), the tenant sees it as terminated, and the audit trail records the operator.
5. **Given** a worker, **When** an operator drains it, **Then** it claims no new work, stops its solves, records their best, releases their leases for re-queueing, and reports itself drained; the operator can undrain it.
6. **Given** a tenant, **When** an operator pauses its queue, **Then** the tenant's running solves continue, none of its scheduled datasets start, new submissions are still accepted and queue, and the tenant's who-am-i and metadata responses show the queue as paused; resume lifts it.
7. **Given** any operator action, **When** it is performed, **Then** it is authorised by the platform operator role only and recorded with who did it and when.

---

### Edge Cases

- A submission exceeds the size limit (100 MB compressed or 2 GB uncompressed): refused with a clear error before any processing.
- A submission names a model or version not in the catalog: refused as not found.
- A submission is compressed: accepted and decompressed transparently.
- A stream reconnect asks for a sequence number the service no longer retains: the stream opens with the current best and reports the number skipped.
- A worker's heartbeat arrives just after its lease expired but before anyone else claimed the dataset: the lease is renewed, no re-queue occurs.
- A worker's report and a lease expiry race: whichever the dataset record applies first wins; a report from the losing lease is dropped.
- A solve finds hundreds of improvements per second: the caller sees at most one update per second, the last one is never lost, and only strictly better scores are recorded.
- A derived child is created from a parent that completes between the caller's check and the derivation: the child still derives from the parent's final best; no supersession is recorded.
- A dataset is terminated with the force flag while a worker is unreachable: the dataset finalises immediately with its recorded best; the worker's later reports are dropped.
- A tenant's concurrency limit is lowered below its current active count: running solves continue; no new ones start until under the limit.
- A configuration profile referenced by a queued dataset is deleted: the dataset's configuration was resolved at submission and is unaffected.
- A patch is applied to a solved parent but makes a pinned assignment invalid: the model's validation reports the issue; the child is invalid.
- Metadata editing is attempted on a purged dataset: refused.
- An operator inspects a failing solve: they can see its metadata, worker, lease and logs but never the roster or route data itself; reproducing a model bug needs the tenant to share the input deliberately.
- Two submissions arrive with the same idempotency key from different tenants: treated as unrelated.
- A tenant has only one webhook subscription and it fails persistently: no `webhook.failing` event can be delivered; the delivery log is the only record, and the tenant's members can read it.
- A webhook subscription's target returns success after the delivery timeout: the delivery is retried and the receiver may see a duplicate; it dedupes by event id.
- The hard lifetime ceiling is lower than the requested solve duration: the ceiling wins and the resolved configuration shows the capped value.
- All workers are down: submissions are accepted and queue in the scheduled status; nothing fails; solving resumes when a worker returns.

## Requirements *(mandatory)*

### Functional Requirements

**Model catalog**

- **FR-001**: The service MUST expose a catalog of models, each with an id, version, maturity level, entity name, input schema, output schema, constraint list with descriptions and default weights, KPI and input-metric descriptors, validation issue types, patch paths and demo datasets.
- **FR-002**: The initial catalog MUST contain an employee scheduling model and a vehicle routing model.
- **FR-003**: A model's public API version MUST be part of its path, and a breaking change to a stable model MUST be a new version.
- **FR-004**: The service MUST serve each model's demo datasets by id, as a full request or as input only.

**Submission and validation**

- **FR-005**: Callers MUST be able to submit a dataset naming a model, with the model's input and optional run configuration (name, tags, thread count, termination settings), model configuration (one integer weight per constraint, zero disabling it), a configuration profile reference, a priority from 0 to 10, and an operation of solve or no-solve.
- **FR-006**: The service MUST validate the submission against the model's input schema synchronously and refuse it with the specific errors if it fails, creating nothing.
- **FR-007**: The service MUST run the model's own validation before solving and record the outcome as validated or invalid, with each issue carrying its catalog code, severity and typed fields.
- **FR-008**: The service MUST answer an accepted submission immediately with the dataset's metadata.
- **FR-009**: The service MUST honour an idempotency key per tenant on submission so that a retried submission returns the original dataset.
- **FR-010**: The service MUST accept compressed request bodies up to 100 MB compressed and 2 GB uncompressed, and refuse larger ones with a clear error.
- **FR-011**: Termination settings MUST include maximum solve duration, unimproved duration, diminished-returns window and ratio, step count limit and move count limit; when none is given, the diminished-returns default applies.
- **FR-012**: The service MUST resolve configuration in this priority order, highest first: the submission's own configuration, the named profile, the parent dataset's configuration (for derived datasets), model defaults, plan and model maximums enforced last; and MUST expose the resolved result per dataset.

**Lifecycle and status**

- **FR-013**: Every dataset MUST report exactly one of ten statuses: created, validated, invalid, computed, scheduled, started, active, incomplete, completed, failed, with the meanings and transitions defined in API.md §2.2.
- **FR-014**: Final statuses MUST be computed (for the no-solve operation), invalid, completed, incomplete and failed; streams close and webhooks fire on these.
- **FR-015**: A dataset submitted with the no-solve operation MUST stop at computed with the submitted plan scored, and a later solve request MUST continue it into solving.
- **FR-016**: Every dataset MUST record timestamps for submission, start of initialisation, start of solving, completion of solving and end of post-processing.
- **FR-017**: A failed dataset MUST carry a failure message, and any solution recorded before the failure MUST remain readable.

**Retrieving results**

- **FR-018**: Callers MUST be able to fetch a dataset's metadata alone, or the dataset with its best solution so far, KPIs and input metrics, at any time after creation.
- **FR-019**: Callers MUST be able to fetch a dataset's input, its full original request, its resolved configuration, its validation result and its logs.
- **FR-020**: KPIs and input metrics MUST be typed, titled and described fields declared by the model, not free-form.

**Streaming**

- **FR-021**: Callers MUST be able to open a live stream of a dataset's metadata updates that emits one frame per recorded improvement, throttled to at most one per second, optionally filtered by status, and closes after the final frame.
- **FR-022**: Each stream frame MUST carry the dataset's per-dataset sequence number, and callers MUST be able to resume from any sequence number without gaps or repeats while it is retained.
- **FR-023**: When a requested sequence number is no longer retained, the stream MUST open with the current best and report how many updates were skipped.
- **FR-024**: A stream requested on a final dataset MUST be refused as gone.
- **FR-025**: Streams MUST send periodic keep-alives so that idle connections are not closed by intermediaries.
- **FR-026**: A stream opened with lineage following MUST, on supersession, emit the parent's final frame and continue with the child's frames.

**Termination**

- **FR-027**: Callers MUST be able to terminate a solving dataset; the dataset becomes completed if any solution was recorded, otherwise incomplete, and the best solution is kept.
- **FR-028**: Termination MUST take effect within one heartbeat interval; with the force flag, the dataset MUST finalise immediately regardless of worker reachability.
- **FR-029**: The service MUST enforce a hard lifetime ceiling on every solve above whatever the caller requested.

**Lineage**

- **FR-030**: Datasets MUST be immutable once created; changes MUST be expressed as new datasets carrying the parent's id and the chain's origin id.
- **FR-031**: Callers MUST be able to derive a new dataset from a parent's input (unsolved) or solved output, with optional new configuration, name, tags, priority, profile and operation.
- **FR-032**: Callers MUST be able to derive a new dataset by applying a patch of add, remove and replace operations at dataset paths (including selection of array items by field value or index) to the parent's solved state (default) or input, with schema validation before creation and model validation after.
- **FR-033**: Deriving from a solved state MUST preserve existing assignments where valid so that the solver minimises disruption, and the model MUST report a disruption KPI.
- **FR-034**: Deriving from a parent that is still solving MUST supersede it: the parent completes with its best so far and records its successor; the child starts from that best.
- **FR-035**: Deriving from the solved state of a dataset that has no solution MUST be refused.

**Workers and resilience**

- **FR-036**: Solving MUST be performed by a pool of workers that pull work when they have a free slot; workers MUST NOT need to be addressed by the service.
- **FR-037**: Each claim MUST grant a lease with a time limit and a unique, increasing epoch; the service MUST accept a worker's reports only while its epoch is current.
- **FR-038**: Workers MUST heartbeat each held solve at a fixed interval; control instructions (terminate) MUST ride on the heartbeat reply.
- **FR-039**: When a lease expires without heartbeat, the service MUST re-queue the dataset with its best solution as a warm start, up to three attempts, after which the dataset is failed with a message.
- **FR-040**: The service MUST assign sequence numbers to recorded solutions and MUST record only strictly better scores.
- **FR-041**: Workers MUST throttle solution reports to at most one per configured interval (no less than 250 ms), keeping the latest, and MUST always send the final one.
- **FR-042**: On a stop signal, workers MUST stop their solves, report their latest best, release their leases for re-queueing and exit within the platform's grace period.
- **FR-043**: Solution bodies MUST be stored separately from the dataset's history; the history MUST hold references, scores and small summaries only.
- **FR-044**: Deploying the API service MUST NOT interrupt running solves.
- **FR-045**: The worker's internal endpoints MUST be protected by a separate credential from tenant API keys.

**Tenancy, access and capacity**

- **FR-046**: Every model API request MUST be authenticated by a tenant API key; keys MUST be stored hashed, be revocable, and carry exactly one of two roles: **read-only** (fetch, list, stream, score analysis, validation results, who-am-i) or **read-write** (everything a model API key can do: read-only plus submit, solve, derive, terminate, metadata edit, purge and restore).
- **FR-047**: Datasets, profiles, webhooks and keys MUST be isolated per tenant; a key MUST never reveal or act on another tenant's resources.
- **FR-048**: Each tenant MUST have a concurrency limit; submissions beyond it MUST queue in the scheduled status and start in priority order (highest first, earliest submission on ties) as slots free.
- **FR-049**: Each tenant MUST have rate limits and quotas; requests beyond them MUST be refused with a "too many requests" response.
- **FR-050**: Callers MUST be able to ask who they are and receive their tenant and roles.
- **FR-051**: Tenant administration (keys, limits, profiles, webhooks, membership) MUST be performed through a platform API authenticated by the hosting platform's identity provider, not by model API keys, and every platform API call MUST be authorised by the caller's membership of the tenant it addresses.
- **FR-051a**: A platform-level operator role MUST be able to create a tenant and name its first administrator; only platform operators create tenants.
- **FR-051b**: Each tenant MUST hold a membership list keyed by identity-provider user id, each member having the role admin or member; tenant administrators MUST be able to add, remove and change the role of members. Admins administer keys, limits, profiles, webhooks and membership; members may read tenant configuration and the delivery log but not change it.

**Configuration profiles**

- **FR-052**: Administrators MUST be able to create, read, update and delete up to fifty configuration profiles per model, each with a name (up to 60 characters), description (up to 1000), the profile it derives from, run configuration, model weights and resource settings.
- **FR-053**: A read-only Standard profile MUST exist for every model and MUST be the default when none is named.
- **FR-054**: Profiles MUST be referenceable by id or by name.
- **FR-055**: Thread count MUST be validated and capped at one.

**Webhooks**

- **FR-056**: Administrators MUST be able to create, list, update and delete webhook subscriptions per tenant with a target, event types, filters (status, dataset-name pattern, tags, model), signing method and scope (body or path), and custom headers.
- **FR-057**: The service MUST emit dataset events for the five final statuses and the `webhook.failing` system event, and MUST NOT include solution bodies in any notification.
- **FR-058**: Each notification MUST carry an event id, type, occurrence time, tenant, the dataset's metadata and sequence number, and links to the dataset and its output.
- **FR-059**: Each notification MUST be signed with the subscription's secret and carry a timestamp header.
- **FR-060**: Delivery MUST expect success within five seconds, retry up to ten times at ten-second intervals including after read timeouts, and be at-least-once.
- **FR-061**: The service MUST keep a per-tenant delivery log for thirty days with per-entry retry.
- **FR-061a**: When a subscription's delivery attempts are exhausted, the service MUST emit a `webhook.failing` system event (carrying the subscription id, target, event id that failed, attempt count and last error) to every other subscription of the same tenant that has opted into that event type, at most once per two hours per failing subscription, and MUST NOT deliver it to the failing subscription itself. No email or other out-of-band notification is sent in this feature.

**Analysis and explainability**

- **FR-062**: Callers MUST be able to fetch a dataset's score analysis: total score and per-constraint name, weight and score contribution. Match counts and per-match justifications are out of scope (they require the solver's Enterprise edition); a request for justifications MUST be accepted and answered as unsupported.
- **FR-063**: Callers MUST be able to score a supplied plan against a profile without creating a dataset.
- **FR-064**: Each model MUST publish its validation issue catalog, and each issue type MUST be retrievable by code with its severity, message and schema.

**Dataset management and retention**

- **FR-065**: Callers MUST be able to list their tenant's datasets filtered by status and tag, with paging.
- **FR-066**: Callers MUST be able to update a dataset's name (up to 255 characters) and tags (up to 100, unique) after submission, except on purged datasets.
- **FR-067**: Callers MUST be able to purge a final dataset's stored input and solutions; purge MUST be refused while solving.
- **FR-068**: A purged dataset MUST be restorable until its retention period expires, after which restore MUST be refused because the bodies have been physically deleted (FR-069b).
- **FR-069**: Stored inputs and solutions MUST be removed automatically when retention expires; per dataset, the service MUST retain at least the final solution, the first feasible solution and the most recent N intermediate solutions until then.

**Data protection**

- **FR-069a**: Dataset inputs, patches and solution bodies MUST be treated as confidential: they MUST NOT be written to service logs, diagnostic output, webhook payloads, metrics or the cross-tenant operator views; operators see metadata (ids, names, tags, statuses, scores, timings) only.
- **FR-069b**: Purge, and retention expiry, MUST physically delete the dataset's input, patches and all solution bodies, not merely hide them; after the restore window closes the data MUST be unrecoverable by the service. Metadata (ids, statuses, scores, timestamps, lineage) is retained for listing and audit.
- **FR-069c**: Encryption of data at rest and in transit inside the hosting platform is the deploying organisation's responsibility; the service MUST NOT assume plaintext storage is prohibited but MUST NOT itself weaken whatever the platform provides.

**Integration libraries**

- **FR-070**: A client library MUST be published that supports submit, poll, fetch, stream, terminate and lineage operations, and depends on nothing from the hosting platform.
- **FR-071**: An ankka integration library MUST be published that provides: the client; agent tools generated from a model's catalog entry (submit, whose parameter schema is the model's input schema and whose arguments are validated against it before anything is sent; get best solution; terminate); a webhook verifier consisting of an access-rule predicate (signature header present, timestamp fresh) and a verify-and-decode function returning a typed event; and an in-memory scripted fake that answers the client and fires webhooks locally.
- **FR-072**: Submissions made through the generated tools MUST be tagged with the calling workflow or session id.
- **FR-073**: The integration library MUST NOT mount any endpoint itself; the developer writes the receiving endpoint and its access rule.
- **FR-074**: The fake MUST fail loudly when its script is exhausted.

**Operator visibility and control**

- **FR-074a**: The service MUST export operational metrics for the hosting platform's monitoring: queued datasets per model, free and busy worker slots, lease losses, re-queues, completed and failed solves, and webhook delivery failures, labelled by tenant where applicable.
- **FR-074b**: Platform operators MUST be able to list datasets across all tenants (tenant, model, status, priority, submit time, best score, holding worker, lease epoch, attempt count) filtered by tenant, model and status, and to list workers (id, supported models, slot count, busy slots, held datasets). No other principal may use these views.
- **FR-074c**: Platform operators MUST be able to force-terminate any dataset in any tenant, which finalises it immediately with its best solution as in FR-028.
- **FR-074d**: Platform operators MUST be able to drain and undrain a worker; a draining worker MUST claim no new work and MUST release its solves as in FR-042 without exiting.
- **FR-074e**: Platform operators MUST be able to pause and resume a tenant's queue; while paused, the tenant's running solves continue, its scheduled datasets do not start, and its submissions are still accepted and queued; the paused state MUST be visible to the tenant.
- **FR-074f**: Every operator action MUST be authorised by the platform operator role only and recorded with the acting principal and time.

**Hosting and operations**

- **FR-075**: The service MUST be deployable as two ankka services in one ankka project: an exposed API service and a non-exposed worker service, each rolling independently.
- **FR-076**: The whole system MUST be runnable in a single process for local development, with the worker in-process.
- **FR-077**: The product MUST be described as built on Timefold Solver and MUST NOT be named after it.

### Key Entities

- **Model**: A planning problem type in the catalog. Has an id, version, maturity level, entity name, schemas (input, output, weight overrides, patch paths), constraint list, KPI and input-metric descriptors, validation issue catalog and demo datasets. Versioned independently; a stable model's API is backward compatible within a version.
- **Dataset**: One submitted planning problem and everything that happens to it. Immutable input; status; resolved configuration; priority; name and tags; timestamps per phase boundary; validation result; best solution so far with score and sequence number; lineage (parent id, origin id, successor id); failure message; purge state. Belongs to exactly one tenant and one model version.
- **Solution record**: A recorded improvement for a dataset: sequence number, score, feasibility, a reference to the stored solution body, and a small model-defined summary (KPIs). Only strictly better scores are recorded.
- **Lease**: A worker's exclusive, time-limited right to solve a dataset, with an epoch that fences stale reports. A dataset has at most one current lease; up to three attempts.
- **Worker**: A member of the solving pool with a number of slots, each able to run one solve; identified by a worker id; supports a set of models; may be marked draining by an operator.
- **Platform operator**: A principal holding the platform-level operator role; creates tenants, reads cross-tenant views, and performs force-terminate, drain and queue pause actions, each of which is recorded with the acting principal and time.
- **Tenant**: An isolated workspace created by a platform operator, with a membership list (identity-provider user id plus role admin or member), API keys, concurrency limit, quotas, rate limits, configuration profiles, webhook subscriptions, datasets, and a queue-paused flag set by operators.
- **API key**: A tenant-scoped credential stored hashed and revocable, with one of two roles: read-only or read-write.
- **Configuration profile**: A named, tenant-owned bundle of run configuration, model weights and resource settings for one model, deriving from another profile; up to fifty per model; a read-only Standard profile per model.
- **Webhook subscription**: A tenant's registration of a target with event types, filters, signing settings and custom headers, plus its secret.
- **Webhook delivery**: One attempt sequence to deliver one event to one subscription: attempts, outcomes, timestamps; retained thirty days; retryable.
- **Event**: A notification delivered to a subscription: id, type, occurrence time, tenant and type-specific data. Dataset events carry the dataset's metadata, sequence number and links; the `webhook.failing` system event carries the failing subscription's id, target, failed event id, attempt count and last error.
- **Validation result**: The outcome of model validation for a dataset: overall status and a list of typed issues, each with a catalog code, severity and entity fields.
- **Score analysis**: A breakdown of a plan's score by constraint with weight, score, match count and optional justifications; available per dataset or statelessly for a supplied plan.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An integrator with an API key can submit a model's demo dataset and have a feasible solution retrievable within 60 seconds of submission using the default profile.
- **SC-002**: Acceptance of a valid submission, including schema validation, is answered within 2 seconds for inputs up to 10 MB.
- **SC-003**: A newly found improvement is visible to a stream consumer within 2 seconds of the worker finding it, and no consumer ever receives more than one update per second per dataset.
- **SC-004**: Across every dataset's stream and metadata history, no recorded score is ever worse than the one before it (zero regressions in a monotonicity audit).
- **SC-005**: A termination request is reflected in a final status within 10 seconds.
- **SC-006**: Killing a worker mid-solve loses no recorded solution, and the affected solve resumes on another worker within 60 seconds and still reaches a final status.
- **SC-007**: In a test that replays reports from replaced workers, 100% are rejected and none alter a dataset's recorded state.
- **SC-008**: Redeploying the API service during ten concurrent solves interrupts none of them; redeploying all workers costs each solve no more than 30 seconds of progress.
- **SC-009**: With a healthy receiver, a final-status webhook arrives within 30 seconds of the dataset reaching that status; with the receiver unavailable for 60 seconds, the notification still arrives; receivers can identify 100% of duplicates by event id.
- **SC-010**: A tenant at its concurrency limit sees excess submissions queued rather than refused, and a queued dataset starts within 5 seconds of a slot freeing, in priority order.
- **SC-011**: An authorisation test suite exercising every dataset, profile, key and webhook operation across two tenants records zero cross-tenant reads or actions.
- **SC-012**: Submissions of 100 MB compressed are accepted and anything larger is refused with an explanatory error; the 2 GB uncompressed limit is enforced as a cap and exercised only once the hosting platform offers an instance type able to hold it.
- **SC-013**: Fetching a dataset with a solution body of up to 10 MB completes within 1 second while the dataset is still solving.
- **SC-014**: A derived dataset created from a solved parent by a small patch reports a disruption KPI and reaches a feasible solution in less time than solving the same input from scratch.
- **SC-015**: An ankka application's test suite can run submit, await webhook, and read result end to end using only the integration library's fake, with no satisfactory deployment running.
- **SC-016**: A purged dataset is restorable at any point within its retention window and unretrievable after it, in 100% of retention tests.
- **SC-017**: Draining a worker while it holds solves causes every one of them to resume on another worker within 60 seconds with no recorded solution lost; pausing a tenant's queue starts none of its scheduled datasets for the duration of the pause while other tenants' datasets keep starting.
- **SC-018**: A scan of service logs, metrics output, webhook payloads and operator views after a full test run finds zero occurrences of input or solution content; after purge plus retention expiry, zero bytes of a dataset's input or solutions remain in storage.

## Assumptions

- **Solver edition**: Timefold Solver 2.7 Community edition only (score analysis, recommendations and solution diff are Enterprise-only in this line; see FR-062). Solves are single-threaded; the thread count is capped at one; nearby selection and the vendor's throttling consumer are not used. Enterprise features arrive only as a deliberate purchase.
- **Catalog**: The two initial models are ported from Timefold's Apache-2.0 quickstarts (employee scheduling, entity name "schedules"; vehicle routing, entity name "route-plans"). Model code is compiled into the worker image; uploading model packages is out of scope. Models may declare extra routes in a later version; neither initial model ships one.
- **Hosting**: ankka v0.5.0 or later. One ankka project holding an exposed API service (small, odd instance count, three by default) and a non-exposed worker service (large, odd instance count of at least three; one solve slot per instance while the largest instance type is 2 vCPU). The worker pool is fixed-size; queueing absorbs excess demand until the platform acts on autoscaling. Dedicated per-solve pods are phase 5.
- **Identity**: Tenant administrators authenticate to the platform API with the same identity provider ankka's control plane uses (Keycloak); no second user directory. Model API keys are satisfactory's own. Tenants are created by a platform-level operator, who names the first administrator; membership is stored by satisfactory per tenant.
- **Retention defaults**: Dataset inputs and solutions are retained 30 days after the final status; purge hides the dataset immediately and is restorable within that window, after which bodies are physically deleted; per dataset the final, first feasible and last 10 intermediate solutions are retained until then. All adjustable per deployment.
- **Capacity defaults**: Concurrency limit 2 solves per tenant, submission rate limit 60 per minute per tenant, hard lifetime ceiling 12 hours per solve. All adjustable per tenant or deployment.
- **Timing defaults**: Heartbeat every 5 seconds; lease limit about 20 seconds; three lease attempts; worker report throttle 1 second (floor 250 ms); stream throttle one frame per second; stream keep-alive every 15 seconds; retained stream window about the last 50 updates per dataset.
- **Webhook timing**: Adopted from Timefold: 5-second delivery timeout, up to 10 retries 10 seconds apart, 30-day delivery log, failure notification at most once per two hours per subscription. Timefold emails administrators; this feature instead emits a `webhook.failing` event to the tenant's other subscriptions, so no mail integration is needed. Read timeouts are retried (a divergence), relying on event ids for deduplication.
- **Request limits**: 100 MB compressed / 2 GB uncompressed, adopted from Timefold; whether the hosting platform's ingress and HTTP server honour these limits and compressed bodies is an open verification item, as is the gateway's idle timeout for streams.
- **Storage**: Solution bodies are stored in the API service's own provisioned database for v1, adequate to tens of megabytes per solution; object storage is a later change that does not alter behaviour.
- **Stream resumption**: The hosting platform's stream support does not yet emit frame ids, so resumption is by query parameter; when frame ids become available, standard reconnect resumption will work too.
- **Rate limiting** is enforced by the service itself because the hosting platform's gateway does not rate-limit.
- **Ordering of work**: Phase 0 (a spike proving the solver interop and the model interface) precedes Story 1 and is engineering groundwork, not a user-facing story. Stories map to build phases: 1 → phase 1; 2 and 3 → phase 2; 4 and 10 → phase 3; 5 through 9 → phase 4.
- **Data protection**: Employee rosters and similar inputs are personal data; the service keeps them out of logs, metrics, webhooks and operator views, and deletes them physically on purge or expiry. Encryption at rest and in transit is provided by the hosting platform and deployment, not by the service.
- **No UI and no operator CLI** in this feature; webhooks and profiles are managed through the platform API, and operator views and actions are platform API operations restricted to the operator role.
- **Naming**: The product is described as built on Timefold Solver; "Timefold" is the vendor's trademark and is not used in the product name or headers.
