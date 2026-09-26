# Data Model: Constraint Solving as a Service (satisfactory)

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Research**: [research.md](./research.md)

Everything durable is an ankka component in the `api` service. Entities are the consistency boundaries; views are the listings; blobs live in the service's own database beside the journal. Wire names (component ids, handler names, manifests) are protocol and are fixed here. Field names on the public API follow API.md; internal names follow the spec's vocabulary (dataset, worker, lease).

## Identifiers

| Id | Form | Issued by |
|---|---|---|
| dataset id | `ds_<ULID>` | `DatasetsEndpoint` on submit / derive |
| tenant id | `t_<ULID>` | `PlatformEndpoint` on tenant create |
| API key id | `k_<ULID>`; the key itself is `sk_<32 random bytes, base64url>` shown once; stored as SHA-256 hex | `PlatformEndpoint` |
| profile id | `cp_<ULID>`; `standard` is reserved per model | `PlatformEndpoint` |
| subscription id | `wh_<ULID>` | `PlatformEndpoint` |
| event id | `evt_<ULID>` | `DatasetEventFanout` |
| worker id | pod name in a cluster, `local-<ULID>` otherwise | the runner |
| seq | `Long`, per dataset, from 1 | `DatasetEntity` |
| epoch | `Long`, per dataset, from 1, incremented on every `lease` | `DatasetEntity` |
| blob ref | `<datasetId>/<kind>/<seq or 'input' or 'patch'>` | the caller of `BlobStore.put` |

## `DatasetEntity` — event sourced, component id `dataset`

The source of truth for one dataset. Id = dataset id. State manifest `dataset`, event manifest `dataset-event`, `snapshotEvery = Some(50)`.

### State

```text
Dataset
  spec:            DatasetSpec        immutable after creation
    tenantId, modelId, modelVersion, entity (schedules | route-plans)
    operation:     SOLVE | NONE
    priority:      0..10
    inputRef:      blob ref            the submitted or derived modelInput
    patchRef:      Option[blob ref]    from-patch only
    parentId, originId: Option[datasetId]
    select:        Option[UNSOLVED | SOLVED]   how it was derived
    config:        ResolvedConfig      run + model overrides, resolved at creation (R7 of API.md §2.1)
    profileId:     Option[profileId]
    tags:          List[String] ≤ 100 unique; name: Option[String] ≤ 255   (mutable via MetadataUpdated)
    idempotencyKey: Option[String]
  status:          one of the ten SolvingStatus values
  attempt:         Int (0..3)
  lease:           Option[Lease(workerId, epoch, expiresAt)]
  best:            Option[Best(seq, score: String, scoreParsed: ScoreKey, feasible, solutionRef, kpis: JSON)]
  firstFeasibleSeq: Option[Long]
  retainedSolutionRefs: List[blob ref]   final + first feasible + last 10 (others evicted, returned to the caller to delete)
  ring:            Vector[(seq, Metadata)]  last 50 metadata snapshots, oldest first
  nextSeq:         Long
  pendingTerminate: Option[TerminateRequest(by, force)]
  supersededBy:    Option[datasetId]
  validation:      Option[ValidationResult(status, issues)]
  inputScore:      Option[String]; inputMetrics: Option[JSON]; analysisRef: Option[blob ref]
  timestamps:      submit, start, active, complete, shutdown: Option[Instant]
  failureMessage:  Option[String]
  purged:          Boolean; purgedAt: Option[Instant]; expired: Boolean
  logs:            Vector[String]   last 200 lines the worker reported
```

`ScoreKey` is a comparable form of the score (a list of `BigDecimal` levels) so the fold can decide "strictly better" without the model on the classpath.

### Events (manifest `dataset-event`, discriminator `type`)

| Event | Fields | Persisted by |
|---|---|---|
| `DatasetCreated` | spec, submitDateTime | `create` |
| `Scheduled` | at | `create` (operation SOLVE), `solve` |
| `Leased` | workerId, epoch, expiresAt, attempt, warmStartRef? | `lease` |
| `Validated` | result | `validated` |
| `Invalid` | result | `validated` |
| `Computed` | inputScore, inputMetrics, analysisRef | `computed` |
| `SolvingStarted` | at | `started` |
| `SolvingActive` | at | `active` |
| `SolutionRecorded` | seq, score, scoreKey, feasible, solutionRef, kpis, at, evictedRefs | `record-solution` |
| `HeartbeatSeen` | *not an event* — heartbeats update the lease expiry through the timer, not the journal |  |
| `TerminationRequested` | by (tenant key id / operator subject), force, at | `request-terminate` |
| `Superseded` | childId, at | `supersede` |
| `LeaseLost` | epoch, reason (expired \| released \| drained), at | `expire-lease`, `release` |
| `Completed` | reason (termination \| terminated \| superseded \| lifetime), finalSeq, at | `complete`, `request-terminate(force)`, `expire-lifetime`, `supersede` |
| `Incomplete` | reason, at | same, when no solution exists |
| `Failed` | message, at | `fail`, `expire-lease` on the 3rd loss |
| `MetadataUpdated` | name?, tags? | `update-metadata` |
| `LogsAppended` | lines | `heartbeat` (when the worker attaches log lines) |
| `Purged` | at | `purge` |
| `Restored` | at | `restore` |
| `Expired` | at | `expire-retention` |

### Commands and queries (wire names)

| Handler | Kind | Caller | Precondition / refusal |
|---|---|---|---|
| `create` | command | submit, from-input, from-patch | entity must be empty, else `Conflict` |
| `solve` | command | `POST /{id}` | status `DATASET_COMPUTED` (operation NONE), else `BadRequest` |
| `request-terminate` | command | `DELETE /{id}`, operator | active statuses; final → reply current metadata, persist nothing; `force` finalises immediately |
| `supersede` | command | derive on an active parent | status in {SCHEDULED, STARTED, ACTIVE, VALIDATED, COMPUTED}; persists `Superseded` + `Completed`/`Incomplete` |
| `update-metadata` | command | `PATCH /metadata` | not purged; name ≤ 255, tags ≤ 100 unique |
| `purge` | command | `DELETE /purge` | final status and not solving; else `BadRequest` |
| `restore` | command | `PUT /{id}` | purged and not expired; expired → `NotFound` |
| `get`, `metadata`, `updates-since(seq)`, `resolved-config`, `validation-result`, `logs` | query | endpoints | purged → `NotFound` for bodies, metadata still answers |
| `lease(workerId, ttl)` | command | `RunnerEndpoint.claim` | status SCHEDULED, no live lease, `attempt < 3`; else `Conflict` |
| `validated(epoch, result)`, `computed(epoch, …)`, `started(epoch)`, `active(epoch)` | command | worker via `RunnerEndpoint` | epoch current, else `Conflict` |
| `record-solution(epoch, score, scoreKey, feasible, solutionRef, kpis)` | command | worker | epoch current; strictly better → persist and reply `Recorded(seq, evictedRefs)`; else reply `NotBetter(currentSeq)` and persist nothing |
| `heartbeat(epoch, stats, logLines?)` | command | worker every 5 s | epoch current; replies `Control(terminate: Boolean, force: Boolean, leaseTtl)`; persists `LogsAppended` only when lines are attached |
| `release(epoch, reason)` | command | worker on drain | epoch current; persists `LeaseLost(released)`, status back to SCHEDULED with a warm start |
| `complete(epoch, reason)`, `fail(epoch, message)` | command | worker | epoch current |
| `expire-lease(epoch)` | command | `DatasetTimers` | no-op reply when epoch moved on or lease still fresh; else `LeaseLost(expired)` and, on the 3rd loss, `Failed` |
| `expire-lifetime` | command | `DatasetTimers` | active → `Completed(lifetime)` / `Incomplete(lifetime)`; else no-op |
| `expire-retention` | command | `DatasetTimers` | final → `Expired`; else no-op |

### Status transitions (the public `solverStatus`)

```text
create(SOLVE):  DATASET_CREATED → SOLVING_SCHEDULED ─lease→ (worker validates) → DATASET_VALIDATED → DATASET_COMPUTED
                → SOLVING_STARTED → SOLVING_ACTIVE → SOLVING_COMPLETED
create(NONE):   DATASET_CREATED ─lease→ DATASET_VALIDATED → DATASET_COMPUTED   (final; `solve` → SOLVING_SCHEDULED …)
invalid:        … → DATASET_INVALID   (final)
terminate:      any active → SOLVING_COMPLETED (best exists) | SOLVING_INCOMPLETE
supersede:      any active → SOLVING_COMPLETED | SOLVING_INCOMPLETE, supersededBy = child
lease lost:     status unchanged (stays what it was, e.g. SOLVING_ACTIVE) while internally SCHEDULED for re-lease with warmStartRef = best.solutionRef
3rd lease loss, deterministic error: → SOLVING_FAILED (best stays readable)
lifetime:       any active → SOLVING_COMPLETED | SOLVING_INCOMPLETE
purge/restore/expire: orthogonal flags on a final dataset
```

Note the divergence from a naive reading of API.md: for `operation=NONE`, validation and scoring also run on a worker (they need the model and can be slow), so a NONE dataset is leased too; it simply has no solving phase. Its public status never shows `SOLVING_SCHEDULED`.

### Fold rules (enforced in `applyEvent` and the handlers, tested as properties)

1. A worker command whose `epoch ≠ lease.epoch` is refused with `Conflict` before any event is persisted.
2. `SolutionRecorded` is persisted only when `scoreKey > best.scoreKey` (or `best` is empty); `seq = nextSeq`; the ring is appended and trimmed to 50; `retainedSolutionRefs` is recomputed and evicted refs are put on the event so replay agrees with the deletion the endpoint performed.
3. No event carries a solution, input or patch body.
4. `LeaseLost` increments `attempt` and sets `warmStartRef = best.solutionRef` for the next `lease`.

## `TenantEntity` — event sourced, component id `tenant`

Id = tenant id. Manifests `tenant`, `tenant-event`.

```text
Tenant
  name, createdAt, createdBy (operator subject)
  members:        Map[subject, Member(role: admin | member, addedAt, addedBy)]
  keys:           Map[keyId, KeyRecord(label, role: read-only | read-write, createdAt, revokedAt?)]   (hashes live in ApiKeyEntity)
  limits:         Limits(concurrency = 2, submitPerMinute = 60, lifetimeCeiling = PT12H, retention = P30D)
  activeSlots:    Set[datasetId]        exact concurrency accounting
  queuePaused:    Boolean; pausedBy, pausedAt
  profiles:       Map[modelKey, Map[profileId, ConfigurationProfile]]    ≤ 50 per model; `standard` is synthesised, never stored
  subscriptions:  Map[subscriptionId, Subscription(url, events, filters, signing, headers, secretHash, secretEncrypted, lastFailingNotifiedAt?)]
  rate:           token bucket state for submitPerMinute
```

Events: `TenantCreated`, `MemberAdded`, `MemberRemoved`, `MemberRoleChanged`, `KeyCreated(keyId, label, role)`, `KeyRevoked`, `LimitsChanged`, `SlotAcquired(datasetId)`, `SlotReleased(datasetId)`, `QueuePaused`, `QueueResumed`, `ProfileCreated/Updated/Deleted`, `SubscriptionCreated/Updated/Deleted`, `FailingNotified(subscriptionId, at)`.

Commands: `create`, `add-member`, `remove-member`, `change-member-role`, `create-key` (returns the plaintext key once), `revoke-key`, `set-limits`, `acquire-slot(datasetId)` (refuses at `limits.concurrency` or when paused), `release-slot(datasetId)` (idempotent), `pause-queue`, `resume-queue`, `create/update/delete-profile`, `create/update/delete-subscription`, `note-failing-notified`, `take-submit-token`; queries `get`, `membership(subject)`, `profile(modelKey, idOrName)`, `subscriptions-for(eventType)`.

## `ApiKeyEntity` — key value, component id `api-key`

Id = SHA-256 hex of the key. State `ApiKey(tenantId, keyId, role, revoked: Boolean)`. Commands `create`, `revoke`; query `get`. Written by `PlatformEndpoint` together with `TenantEntity.create-key`/`revoke-key` (two writes; the endpoint writes `ApiKeyEntity` first on create and first on revoke, so a crash between them leaves a key that cannot authenticate rather than one that can).

## `IdempotencyEntity` — key value, component id `idempotency`

Id = `<tenantId>:<sha256(Idempotency-Key)>`. State `{datasetId, createdAt}`, `expireAfter(24h)`. Commands `put`, query `get`.

## `WebhookDeliveryWorkflow` — workflow, component id `webhook-delivery`

Id = `<eventId>:<subscriptionId>`. State `{tenantId, subscriptionId, event: DatasetEvent, attempts: Int, lastStatus?, lastError?, done: Boolean}`. Steps `deliver` → pause 10 s → `deliver` … (≤ 10) → `exhausted`. Settings: step timeout 15 s, `defaultRecovery = maxRetries(0).failoverTo(recordFailure)`. Commands `start(event, subscription)` (idempotent: a running workflow ignores it), `retry` (from the delivery log).

## `WebhookDeliveryEntity` — key value, component id `webhook-delivery-log`

Id = workflow id. State `Delivery(tenantId, subscriptionId, eventId, eventType, datasetId, target, attempts: List[Attempt(at, status?, error?, durationMs)], outcome: delivered | exhausted | pending, createdAt)`, `expireAfter(30 days)`. Written by the workflow after every attempt.

## `WorkerEntity` — key value, component id `worker`

Id = worker id. State `Worker(models, slots, busy: Set[datasetId], draining: Boolean, lastSeenAt)`. Commands `register`, `heartbeat` (every claim-loop tick, 5 s), `drain`, `undrain`, `deregister`. A worker unseen for 60 s is shown as `stale` by the view. `drain` is read by the runner on its next claim/heartbeat (the runner endpoint returns `drain: true` in every reply for that worker).

## `AuditEntity` — event sourced, component id `audit`

Id = `ops` (one stream for operator actions; low volume). Events `OperatorActed(subject, action, target, at)`. Query `recent(limit)`.

## Views

| View | Source | Row |
|---|---|---|
| `DatasetRows` (`dataset-rows`) | `eventsOf(DatasetEntity)` | `{datasetId, tenantId, modelKey, entity, status, internalQueued: Boolean, priority, submittedAt, name, tags, bestScore, workerId, epoch, attempt, parentId, originId, purged, expired, tenantPaused}` — `tenantPaused` is copied in by `RunnerEndpoint` at claim time, not by the view (a view reads one source) |
| `TenantRows` (`tenant-rows`) | `eventsOf(TenantEntity)` | `{tenantId, name, memberSubjects, queuePaused, concurrency, activeSlots}` |
| `WebhookDeliveryRows` (`webhook-delivery-rows`) | `stateOf(WebhookDeliveryEntity)` | the delivery log, queried by tenant, subscription, outcome, time |
| `WorkerRows` (`worker-rows`) | `stateOf(WorkerEntity)` | `{workerId, models, slots, busyCount, draining, lastSeenAt}` |

Queries the endpoints run: "my datasets" (tenant, status, tag, page), "queue head candidates" (status SCHEDULED, models ∈ worker's, order priority desc / submittedAt asc, limit 10), "who holds what" (operator), "delivery log" (tenant), "workers" (operator), metric counts.

## Blob store (JDBC table in the `api` database)

`satisfactory_blobs(ref TEXT PRIMARY KEY, dataset_id TEXT NOT NULL, kind TEXT NOT NULL, content_type TEXT NOT NULL, size BIGINT NOT NULL, body BYTEA NOT NULL, created_at TIMESTAMPTZ NOT NULL)`, index `(dataset_id)`. Kinds: `input`, `patch`, `solution`, `analysis`. Rows for a dataset are deleted on `purge` (after the restore window) and on `expire-retention`; evicted solution refs are deleted as `record-solution` reports them. Blobs are stored as submitted; compression is the wire's concern (R9).

## Configuration resolution (`ResolvedConfig`)

Computed once at `create`, in this order, later layers overriding earlier ones, and then clamped:

1. model defaults (`SolverModel.constraints().defaultWeight`, termination default `slidingWindowDuration = PT30S`, `minimumImprovementRatio = 0.0001`)
2. the parent dataset's resolved config (derivations only)
3. the profile named by `configurationId` (id or name; `standard` = model defaults)
4. the request's own `config`
5. clamps: `maxThreadCount = 1`; `spentLimit ≤ tenant.limits.lifetimeCeiling`; weights ≥ 0

Stored on the dataset spec and answered verbatim by `GET /{id}/config`.

## Protocol types (module `protocol`, jsoniter codecs, no ankka dependency)

Public: `Metadata`, `SolvingStatus`, `DatasetResponse`, `SubmitRequest`, `ModelConfiguration`, `RunConfiguration`, `TerminationConfig`, `ValidationResult`, `Issue`, `ScoreAnalysis` (Community shape, R2), `DemoDataSummary`, `ErrorInfo`, `ValidationErrorInfo`, `ModelDescriptor`, `ConfigurationProfile`, `WebhookSubscription`, `DatasetEvent` (envelope), `WebhookFailingEvent`, `DeliveryLogEntry`, `Tenant`, `Member`, `ApiKeySummary`, `ApiKeyCreated`, `WhoAmI`, platform `ops` rows.

Runner: `ClaimRequest(workerId, models, datasetId?)`, `ClaimResponse(datasetId, epoch, spec, inputRef, patchRef?, warmStartRef?, leaseTtl, drain)`, `ReportRequest(epoch, score, feasible, solutionRef, kpis)`, `ReportResponse(seq?, notBetter: Boolean, evictedRefs)`, `HeartbeatRequest(epoch, status, scoreCalcSpeed, moveCount, logLines?)`, `HeartbeatResponse(terminate, force, leaseTtl, drain)`, `CompleteRequest(epoch, reason, analysisRef?)`, `FailRequest(epoch, message)`, `ReleaseRequest(epoch, reason)`, `PhaseRequest(epoch, phase, payload)` (validated / computed / started / active). Blob transfer: `PUT/GET /internal/blobs/{ref}` with raw bytes.
