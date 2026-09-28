# satisfactory — design

Constraint solving as a service: submit a planning problem, get back a solution — or a stream of
ever-better ones. Timefold Solver does the solving; [ankka](../ankka) supplies the application
runtime and the deployment.

Status: design, nothing built. Written 2026-09-21 against ankka `a5ca669` and Timefold Solver 2.6.

> **2026-09-22 — the public API is now defined in [`API.md`](API.md)**, as a reimplementation of the
> Timefold Platform model API. It supersedes §3.1, §3.2 and §4 here: the job → *dataset* vocabulary,
> their ten statuses, numeric constraint weights, configuration profiles, webhook/SSE/polling rules —
> and it **removes `mode: continuous`, daemon mode, `Idle` and `/changes`** in favour of lineage
> (`from-patch`, `from-input` → a new dataset with `parentId`). The entity, fold rules, runner
> protocol, pool and the ankka hosting story (§2, §3.3–§12) stand.

---

## 1. Two facts that shape everything

**A Timefold "problem" is not data, it is code plus data.** Timefold solves an instance of a
`@PlanningSolution` class graph, scored by a `ConstraintProvider` — both compiled Java. There is no
generic wire format for "a constraint satisfaction problem". So a request cannot carry the whole
problem; it carries a *reference to a model* (the code) plus a *dataset* (the instance) plus
*configuration* (how long, which weights). This is also how Timefold's own hosted platform works:
one API per model, a `modelInput` dataset, and a `config.run.termination` block.

→ **Decision: a model catalog.** Models are versioned modules behind a small SPI, compiled into the
solver image. A request names `model.id` + `model.version`. Bring-your-own-model (a jar or an image)
is a later phase and the SPI is shaped so it can arrive without changing the job model — but it
means running untrusted code, which forces per-job isolation (§8), so it is not v1.

**ankka has services, not jobs.** A deployed ankka workload is a long-running Deployment that must
join a Pekko cluster to become `Ready`. There is no run-to-completion workload, a service's
Kubernetes identity can list its own pods and nothing else, and autoscaling is declared but not
acted on. So "spin up a new job per solve" is not something an ankka *application* can do today; it
is a *platform* feature ankka does not have yet.

→ **Decision: one runner protocol, two ways to host a runner.** The solver runner is a process that
claims a job lease, solves, reports improving solutions, heartbeats, and obeys control messages. It
does not care whether it is a member of a **warm pool** (a second ankka service — deployable today)
or a **dedicated pod** launched for one job (needs a new ankka workload kind). Build the pool first;
add dedicated pods as an ankka feature afterwards. Both stay: a pool answers a 10-second solve
without paying pod-schedule + JVM start; a dedicated pod suits hour-long, continuous, large-memory
or untrusted jobs.

---

## 2. Architecture

```
                         ┌──────────────────────── ankka project: satisfactory ───────────────────────┐
                         │                                                                            │
  client ──HTTPS──▶ gateway ──▶  api  (ankka service, exposed, 3 instances)                           │
   submit / poll /       │       ├─ JobsEndpoint        public REST + SSE                             │
   SSE / terminate       │       ├─ RunnerEndpoint      /internal/*  (runner token ACL)               │
                         │       ├─ SolveJobEntity      event sourced — THE source of truth per job   │
   webhook ◀─────────────┼────── ├─ TenantEntity        keys, quotas, concurrency slots               │
                         │       ├─ JobsView            list / queue / ops queries                    │
                         │       ├─ WebhookConsumer     job events → tenant callback                  │
                         │       ├─ lease + lifetime Timers                                           │
                         │       └─ BlobStore           problems & solutions (Postgres v1)            │
                         │              ▲                                                             │
                         │              │ HTTP, pull only: claim · report · heartbeat(+control)       │
                         │              │                                                             │
                         │       solver (ankka service, http:false, N instances)   ← phase 3          │
                         │       └─ SolverRuntime extension: Timefold SolverManager per model,        │
                         │          one solve per slot, slots = cores − 1                             │
                         │                                                                            │
                         │       dedicated runner pod per job (same image, same protocol) ← phase 5   │
                         └────────────────────────────────────────────────────────────────────────────┘
```

Why two services rather than solving inside `api`:

- Solving is CPU-bound for minutes to hours. In the same JVM as cluster heartbeats and the journal,
  a saturated node or a long GC pause gets the node marked unreachable, and ankka's Kubernetes
  overlay runs `keep-majority` with `exit-jvm = on` — a busy solver would get its own control plane
  downed.
- The two roll independently. Deploying an API fix must not interrupt a two-hour solve.
- They size differently: `api` is small and odd-counted; `solver` is large and as many as you can afford.

Why runners **pull** over HTTP instead of being called:

- A `ClusterIP` cannot address one specific pod, and the runner is a separate Pekko cluster, so
  `api` has no way to reach "the runner holding job X". Pull needs no addressing at all.
- The runner serves nothing → `"http": false`, no inbound surface.
- Pull is backpressure for free: a runner asks when it has a free slot. There is no scheduler to
  get wrong, and a fixed-size pool (all ankka offers today) degrades to a queue, not to overload.
- A dedicated per-job pod uses the identical calls; it just claims one named job instead of "next".

For local development and phase 1, the same runner code runs **inside** `api` behind a
`ControlChannel` trait — `Local` (calls the component client directly) vs `Http`. `sbt api/run` is
then the whole system in one process.

---

## 3. The job model

### 3.1 Lifecycle

The public lifecycle is Timefold's ten `solverStatus` values — see [`API.md` §2.2](API.md). The
entity's *internal* state is richer (it knows about leases and attempts) and folds down to them:

| Entity state | Public `solverStatus` |
|---|---|
| created, schema-valid, not yet claimed | `DATASET_CREATED` → `SOLVING_SCHEDULED` (the queue) |
| claimed; runner validating and scoring the input | `DATASET_VALIDATED` → `DATASET_COMPUTED` (or `DATASET_INVALID`) |
| runner building the planning problem | `SOLVING_STARTED` |
| solving; best-so-far updates arriving | `SOLVING_ACTIVE` |
| terminated by client / superseded / termination reached, a solution exists | `SOLVING_COMPLETED` |
| terminated with no solution yet | `SOLVING_INCOMPLETE` |
| deterministic error, or `maxAttempts` (3) lease losses | `SOLVING_FAILED` + `failureMessage` |
| lease lost, attempts remain | stays `SOLVING_ACTIVE`; internally re-queued with a warm start |

A terminate while solving is a **success that keeps its best solution** — for a metaheuristic,
"stop now and give me what you have" is normal. `operation=NONE` stops at `DATASET_COMPUTED` and
`POST /{id}` resumes the chain later.

### 3.2 One mode, and lineage

There is no "continuous" mode (removed 2026-09-22, see `API.md` §7). A long-running plan is a long
`spentLimit` plus the `/events` stream; a *changed* plan is a new dataset derived from the old one
(`from-input`, `from-patch`) with `parentId`/`originId`. Deriving from a dataset that is still
solving **supersedes** it (`API.md` §2.6): the parent completes with its best so far, the child
warm-starts from it. Streaming is not a mode: any dataset's progress can be streamed.

### 3.3 `SolveJobEntity` (event sourced, id = job id)

State: the immutable spec (tenant, model ref, mode, config, problem ref), status, attempt, current
lease `(runnerId, epoch, expiresAt)`, best `(seq, score, feasible, solutionRef, at)`, a bounded ring
of the last ~50 updates (what SSE reads, §5), pending control (terminate flag, queued problem
changes), timestamps, error.

Events: `DatasetCreated(parentId?, originId?, patchRef?)`, `Scheduled`, `Leased(runnerId, epoch)`,
`Validated(result)` / `Invalid(result)`, `Computed(inputScore, kpisRef)`, `SolvingStarted`,
`SolvingActive`, `BestSolutionRecorded(seq, score, feasible, solutionRef, kpisRef)`,
`TerminationRequested(by, force)`, `Superseded(childId)`, `LeaseLost(epoch, reason)` (→ re-queued,
warm start from best), `Completed(reason, finalSeq, analysisRef)`, `Incomplete(reason)`,
`Failed(message)`, `Purged`, `Restored`, `MetadataUpdated(name, tags)`.

Three rules, all enforced **in the fold**, so replay agrees with the live run:

1. **The lease epoch is a fencing token.** Every runner report carries the epoch it was granted; a
   report from a superseded epoch is dropped. This is ankka's own `generation` rule for service
   observations, for the same reason: a runner that was partitioned, presumed dead and replaced
   *will* come back and report, and must not overwrite its successor.
2. **`seq` is assigned by the entity, and only a strictly better score is recorded.** After a
   requeue, the new runner starts from the old best, so its first reports may equal it. Refusing
   non-improvements keeps the stream monotonic and — like ankka's "identical observation is refused"
   — keeps the journal from growing on noise.
3. **Blobs never enter the journal.** A solution can be megabytes and a job can emit thousands.
   Events carry a `solutionRef`, the score and a small model-defined `summary` (KPIs). Bodies live
   in the `BlobStore`.

```scala
object SolveJobEntity
    extends EventSourcedEntity.Companion[SolveJobEntity, SolveJob, SolveJobEvent](
      componentId = ComponentId("solve-job"), /* serializers */ ):
  def create(context: EventSourcedEntityContext) = new SolveJobEntity(context)

  // client-facing
  val create          = command("create")(_.create)              // POST, from-input, from-patch all land here
  val solve           = command("solve")(_.solve)                // POST /{id} after operation=NONE
  val requestTerminate = command("request-terminate")(_.requestTerminate)   // DELETE /{id}
  val supersede       = command("supersede")(_.supersede)        // a child was derived while active
  val updateMetadata  = command("update-metadata")(_.updateMetadata)
  val purge           = command("purge")(_.purge)
  val restore         = command("restore")(_.restore)
  val get             = query("get")(_.get)
  val metadata        = query("metadata")(_.metadata)
  val updatesSince    = query("updates-since")(_.updatesSince)   // SSE reads this

  // runner-facing — every one carries the lease epoch
  val lease           = command("lease")(_.lease)                // the arbiter: one claimant wins
  val recordSolution  = command("record-solution")(_.recordSolution)
  val heartbeat       = command("heartbeat")(_.heartbeat)        // replies with pending control
  val release         = command("release")(_.release)            // graceful drain → Queued
  val complete        = command("complete")(_.complete)
  val fail            = command("fail")(_.fail)

  // timer-facing
  val expireLease     = command("expire-lease")(_.expireLease)   // no-op if epoch moved on or heartbeat is fresh
  val expireLifetime  = command("expire-lifetime")(_.expireLifetime)
```

### 3.4 Everything else in `api`

| Component | Kind | Job |
|---|---|---|
| `JobsView` | View over `SolveJobEntity` | one row per job `{jobId, tenant, model, mode, status, priority, submittedAt, bestScore, runnerId}`; SQL serves "my jobs", "queue head for models M", "who holds what" |
| `TenantEntity` | Event sourced | hashed API keys, quotas, and **concurrency slots** (`acquire`/`release`) — a count from a view is eventually consistent; a single-writer entity is exact |
| Lease timer | Timer | armed on `lease`, fires `expire-lease`; the entity ignores it if a heartbeat has arrived since |
| Lifetime timer | Timer | the hard ceiling on any solve, above whatever `spentLimit` asked for |
| `WebhookConsumer` | Consumer over job events | POSTs `{jobId, seq, status, score}` with an HMAC signature; at-least-once, `seq` lets receivers dedupe |
| `BlobStore` | SPI, not a component | `put/get/delete` by ref. v1: a table in the service's own provisioned Postgres. Later: S3/MinIO. Retention: final + first feasible + last N per job |

Claiming is ankka's "cross-entity checks are edge checks" idiom: `RunnerEndpoint` asks `JobsView`
for queue-head *candidates* (eventually consistent, may be stale), then sends `lease` to each
candidate entity until one accepts. The view proposes; the entity decides. Tenant slot acquisition
happens in the same edge step, and is released by a consumer on any terminal event.

Workflows: not needed for the pool — the entity plus two timers *is* the orchestration, and a
workflow on top would be a second source of truth for the same state. A workflow earns its place in
phase 5, where launching a dedicated pod is a genuine multi-step process with compensation
(`launch → await claim (timeout) → watch → clean up`).

---

## 4. The request

**Defined in [`API.md`](API.md)** — Timefold Platform's model API, adopted with listed divergences.
In one line: `POST /v1/{entity}?operation=SOLVE&configurationId=&priority=&tags=` with
`{ modelInput, config: { run: { termination, maxThreadCount }, model: { overrides: { <constraint>Weight } } } }`
→ `202 Metadata`; then `/metadata`, `/events`, `GET /{id}`, `DELETE /{id}` (terminate),
`from-input`, `from-patch`.

What is deliberately **not** in the request, and this survives the change: solver phases, move
selectors, acceptors, the `ConstraintProvider`. Those belong to the model — they are what makes a
model good, they are not safely tunable by someone who has not read its code, and exposing them
freezes the model's internals into a public API. Termination and constraint weights are the two
things a caller legitimately owns; a configuration profile is how a tenant names a bundle of them.

Mapping to Timefold Solver: `config.run.termination` → `SolverConfigOverride.withTerminationConfig`;
`config.model.overrides` → `ConstraintWeightOverrides` on the solution instance.

Auth: `X-API-KEY` → tenant, checked in each endpoint's `acl`. `/internal/*` has its own ACL on a
runner token from a secret. Note `api` is *exposed*, so `/internal/*` is reachable from the internet
and that ACL is the only thing in front of it — ankka's gateway does routing and nothing else.

---

## 5. Streaming

```
GET /v1/schedules/{id}/events?after=17
  id: 18
  data: {"id":"…","solverStatus":"SOLVING_ACTIVE","score":"0hard/0medium/-412soft", …Metadata…}
  id: 19
  data: {"id":"…","solverStatus":"SOLVING_ACTIVE","score":"0hard/0medium/-380soft", …}
  : heartbeat
  id: 23
  data: {"id":"…","solverStatus":"SOLVING_COMPLETED","score":"0hard/0medium/-311soft","completeDateTime":"…"}   ← then closes
```

Frames are `Metadata` — status and score, never the solution (Timefold's choice; `GET /{id}` for
the body). `id:` carries `seq`, so both `?after=` and `Last-Event-ID` resume exactly.

**How:** ankka's `sse` takes any `Source[String, ?]`, but entities cannot stream — only agents do.
So the source is a poll: every 500 ms, `SolveJobEntity.updatesSince(lastSeq)`, emit what is new,
complete on a terminal status. That is cheap (an in-memory read of a sharded entity), works whichever
node the request lands on, and half a second of latency is invisible under a one-second throttle.
Pekko pub-sub via a `RuntimeExtension` would shave the latency and is not worth its moving parts yet.

**Resumable by construction.** `seq` is journaled, so `?after=17` is exact after a disconnect, a
gateway timeout or an `api` redeploy. If the ring no longer holds `after+1`, the stream opens with
the current best and says `"skipped": n`. A query parameter rather than `Last-Event-ID` because
ankka's `sse` emits `data:` frames only — adding `id:` support to ankka is a small, worthwhile
follow-up, since it makes browser `EventSource` reconnects automatic.

**Throttling happens at the runner, not the API.** Timefold finds hundreds of new bests per second
in the first moments of a solve. The runner keeps the latest and sends at most one per `minInterval`
(floor: 250 ms), latest-wins, and **always** sends the final one. (Timefold's own
`ThrottlingBestSolutionEventConsumer` is Enterprise-only; this is twenty lines.) The consumer runs
off the solver thread, and the solution it is handed is a planning clone, so serialising it does not
race the solver.

A dashboard wants the curve, not 4 MB a second — which is why the frames are metadata and the
client fetches `GET /{id}` when the score says it is worth it.

---

## 6. The runner

One codebase, packaged as the `solver` image. `SolverRuntime` is an ankka `RuntimeExtension`.

```
claim loop (virtual thread, one per free slot)
  POST /internal/leases {runnerId, models:[…], jobId?}      long-poll; 204 → retry
    ← {jobId, epoch, spec, problemRef, warmStartRef?, leaseTtl}
  problem = model.decodeProblem(blob)      — or decodeSolution(warmStart) on a retry
  solverManager(model).solveBuilder()
      .withProblemId(jobId).withProblem(problem)
      .withConfigOverride(termination from spec)
      .withBestSolutionEventConsumer(throttled → PUT blob, POST /internal/jobs/{id}/solutions {epoch,score,…})
      .withFinalBestSolutionEventConsumer(→ analyze, POST …/complete)
      .withExceptionHandler(→ POST …/fail)
      .run()

heartbeat (every 5 s per held job)
  POST /internal/jobs/{id}/heartbeat {epoch, status, scoreCalcSpeed, moveCount}
    ← {terminate?: bool, changes?: [ref…], leaseTtl}     ← control rides the reply
  terminate → solverManager.terminateEarly(jobId)
  changes   → solverJob.addProblemChange(model.decodeChange(…)); ack on next beat
  409 (epoch superseded) → terminateEarly, discard, free the slot

SIGTERM (rolling deploy)
  terminateEarly all → flush best → POST …/release → exit   (well inside the 30 s grace period)
```

- **Recovery is a warm start, not a resume.** Timefold has no checkpoint of solver state, but it
  does not need one: a job re-queued after a lost lease carries `warmStartRef` = its best solution,
  and a solver given an already-initialised solution skips construction and goes straight to local
  search. A crashed runner costs at most `minInterval` of progress plus the lease TTL (~20 s).
- **Slots = cores − 1**, solves on a dedicated platform-thread pool, never on Pekko's dispatchers.
  Timefold Community is single-threaded per solve, so one slot = one core is an honest capacity
  model. The spare core is what keeps cluster heartbeats on time.
- **Runner pool size: odd, ≥ 3** (ankka's `keep-majority`). Runners never talk to each other; they
  are a cluster only because that is what `Ready` means on ankka.
- Terminate latency = heartbeat interval. 5 s is fine; it is a knob.

### The model SPI — in Java

> **As built (2026-09-28):** `SolverModel<S, Score_>` in `modules/model-spi` (contracts/model-spi.md),
> with `ModelRuntime` holding the solver factory, solution manager and (on a worker) solver manager
> built from it once; score analysis is `ModelRuntime.analyze`, by ablation, because
> `SolutionManager.analyze` is Enterprise-only in Timefold 2.7. Weights reach Timefold through a
> `ConstraintWeightOverrides` field on each solution. Dataset JSON is Jackson 2. On ankka's largest
> instance type (`large`, 2 vCPU) slots = cores − 1 = 1 per worker.

```java
public interface SolverModel<S> {
  ModelId id();  String version();
  JsonSchema inputSchema();  JsonSchema outputSchema();  JsonSchema changeSchema();
  List<ConstraintInfo> constraints();                 // name, description, default weight
  SolverConfig baseConfig(String profile);            // solution+entity classes, provider, phases
  S        decodeProblem(JsonNode dataset, WeightOverrides w) throws InvalidDataset;
  JsonNode encodeSolution(S solution);                // also the warm-start format
  S        decodeSolution(JsonNode solution);
  ProblemChange<S> decodeChange(JsonNode change) throws InvalidChange;
  JsonNode summarize(S solution);                     // the KPIs carried in events
}
```

Models and the SPI are **Java** (or Kotlin) modules; `api`, `solver` and the protocol are Scala 3.
Timefold reads annotations off mutable bean-style classes, and Scala 3 needs `@BeanProperty var`s
and meta-annotation targets on every field to satisfy it. That friction would be paid per model,
forever, against the two languages Timefold actually supports and documents. sbt builds mixed
projects without ceremony. Registration is explicit, ankka-style — `ModelCatalog.of(EmployeeScheduling.v1, …)`,
no classpath scanning — and the same jar is on `api`'s classpath so `POST /v1/{entity}` can run
`decodeProblem` and answer `400` with the model's own validation errors instead of accepting a job
that will fail in the queue.

Start with two models from Timefold's quickstarts (Apache 2.0): **employee scheduling** and
**vehicle routing**. Between them they exercise basic and list variables, hard/soft and
hard/medium/soft scores, and meaningful problem changes.

---

## 7. Repository and deployment

```
satisfactory/
  build.sbt
  modules/
    protocol/        public + runner wire types and codecs (Scala, ankka-core only)
    model-spi/       SolverModel and friends (Java, depends on timefold-solver-core)
    models/
      employee-scheduling/   (Java)
      vehicle-routing/       (Java)
    runner/          claim loop, throttle, ControlChannel{Local,Http}, SolverRuntime extension (Scala)
    api/             the ankka service: entities, view, endpoints, consumers  [image]
    solver/          the ankka service: runner + models, nothing else         [image]
  deploy/api.json  deploy/solver.json
  specs/
```

```jsonc
// deploy/api.json                                   // deploy/solver.json
{ "name": "api",                                     { "name": "solver",
  "service": {                                         "service": {
    "image": "…/satisfactory-api:0.1.0",                 "image": "…/satisfactory-solver:0.1.0",
    "env": [{ "name": "SAT_RUNNER_TOKEN",                "http": false,
              "secretKeyRef": {…} }],                    "env": [{ "name": "SAT_RUNNER_TOKEN", "secretKeyRef": {…} }],
    "resources": { "instanceType": "small",                "resources": { "instanceType": "large",
      "autoscaling": { "minInstances": 3 } } } }             "autoscaling": { "minInstances": 3 } } } }
```

```bash
ankka projects create satisfactory -O <org>
ankka services apply -f deploy/api.json && ankka services apply -f deploy/solver.json
ankka services expose api          # https://api-satisfactory.<base domain>
```

Both start from `ankka.g8`. `solver` gets a provisioned database it barely uses; that is ankka's
one-database-per-service rule and not worth fighting.

---

## 8. What this asks of ankka

In the order it will hurt:

1. **Run-to-completion workloads** (the "spin up a job" you asked for). A new workload kind beside
   `AnkkaService`: CRD → operator renders a `batch/v1 Job` → control plane API + `ankka jobs run`.
   Spec: image, env, resources, `activeDeadlineSeconds`, TTL after finish. It must *not* require
   cluster membership for readiness. satisfactory then gains a `DedicatedDispatcher` — a workflow
   that asks the ankka control plane for a job with `SAT_JOB_ID=<id>`, waits for the claim, and
   cleans up — selected by `resources.profile`. The application should call ankka's control plane
   for this, never hold a Kubernetes credential of its own: that would punch through the exact
   boundary ankka's RBAC is built to keep. **This is also the precondition for bring-your-own-model**
   — untrusted code needs its own pod, a `NetworkPolicy`, and no runner token worth stealing.
2. **Autoscaling on a signal that means something.** CPU is useless here — a solver is at 100% by
   design. The right signal is queue depth ÷ free slots, which `JobsView` already knows. Until
   ankka acts on autoscaling at all, the pool is a fixed size and the queue absorbs the rest.
3. **SSE `id:` frames**, so `Last-Event-ID` resumption works with a plain `EventSource`.
   Now tracked with the rest in [`notes/ankka-requests.md`](notes/ankka-requests.md).
4. **To verify before building:** the HTTP request-body limit (pekko-http's default is 8 MB;
   Timefold accepts 100 MB gzip / 2 GB raw, and `Content-Encoding: gzip` must be honoured), the
   gateway's idle timeout against a quiet SSE stream (hence heartbeats),
   how a `secretKeyRef` secret gets created in a project namespace, and what `instanceType`s exist.
5. Already documented ankka gaps that matter more here than for most apps: no `NetworkPolicy`
   between projects, unencrypted remoting, no gateway rate limiting (so rate limits are ours, in
   `TenantEntity`).

---

## 9. Licensing, briefly

Timefold Solver **Community** is Apache 2.0 and fine to offer as a service. **Enterprise** — multi-threaded
solving, nearby selection, the throttling consumer — is commercially licensed; `resources.moveThreads`
does not appear in the request until that is a deliberate purchase. Timefold sells a hosted platform
that does what this does, and "Timefold" is their trademark: describe the product as *built on*
Timefold Solver, do not name it after it.

---

## 10. Build order

| Phase | What exists at the end | Proves |
|---|---|---|
| **0 · Spike** | One Java model + a Scala `main` driving `SolverManager`, throttle included | Scala↔Timefold interop, the SPI's shape, serialising a planning clone |
| **1 · Walking skeleton** | `api` alone, runner in-process via `ControlChannel.Local`. Submit → poll → solution. Batch only | The entity, the fold rules, the request format |
| **2 · Streaming & lineage** | SSE with `?after` / `id:`, `DELETE` terminate, `operation=NONE` + `POST /{id}`, `from-input`, `from-patch`, supersede | The thing that makes this more than a job queue |
| **3 · The pool** | `solver` service, `/internal/*`, leases, fencing, warm-start requeue, graceful drain. Deployed on ankka | Kill a runner mid-solve; the stream stays monotonic |
| **4 · A product** | Tenants, keys, profiles, quotas, webhooks (§API.md 2.7), score analysis, validation catalog, retention/purge/restore, second model, `satisfactory-client` + `ankka-satisfactory` (§12) | Someone else can use it, from an ankka app in particular |
| **5 · Dedicated pods** | ankka run-to-completion workloads; `DedicatedDispatcher` workflow; then BYO models | The original "spin up a job per solve" |

Each phase is a candidate spec under `specs/`, in the same style ankka uses.

## 11. Ways to run it, and where it meets ankka

Added 2026-09-22. Three hosting shapes, and one deeper integration. They are not exclusive: the
runner protocol (§6) is the same in all of them, which is the point of having it.

### 11.1 The shapes

| | **A · ankka application** (§2) | **B · `SolveJob` CRD + own operator** (the cloudflow shape) | **C · `AnkkaJob`: a run-to-completion workload in ankka** |
|---|---|---|---|
| Job record | `SolveJobEntity` | the CR itself | `SolveJobEntity`; the pod is a detail |
| Who makes pods | nobody — a fixed pool | satisfactory's operator | ankka's operator |
| Submit | `POST /v1/{entity}` | `kubectl apply -f solve.yaml` | `POST /v1/{entity}` → `api` asks ankka's control plane for a job |
| Streaming | SSE off the entity | **nothing to stream from** — see below | SSE off the entity |
| Isolation | none (shared JVM) | one pod per job | one pod per job |
| Multi-tenant API | yes | no — `kubectl` is the API | yes |
| Cold start | none | pod schedule + JVM | pod schedule + JVM |
| Exists today | yes | no; ~operator-sized | no; an ankka feature |

**B is the cloudflow model** — `kubectl cloudflow deploy app.json`, an operator that renders one
Deployment per streamlet and folds status back into the CR — and ankka's operator is the same
pattern one level up. A `SolveJob` CR would carry `{image, model, config, problemRef}`, the operator
would render a `batch/v1 Job` with the CR as owner, and `kubectl get solvejobs` would print phase and
best score. It is genuinely small: ankka's `Rendering → Action → Executor` split and `LifecycleRules`
port almost line for line. Where it breaks is the product, not the plumbing:

- **A CR is a poor event stream.** `status` can hold *the* best score and a solution ref, not a
  sequence of them — and every improvement is a status patch through the API server, which is the
  wrong place to put 5 updates a second. Clients who want the stream need something listening
  that is not `kubectl`, so the operator alone never delivers the headline feature.
- **`kubectl` is not a tenant boundary.** A user who can apply a `SolveJob` can read every other
  one in the namespace. That is fine for an internal platform team; it is not a service.
- **The job body has to go somewhere.** A dataset in a ConfigMap caps at 1 MiB; anything real
  needs object storage the operator provisions — which is a store *and* an API, i.e. half of A.

So B on its own is the right answer for one audience: a team that already runs Kubernetes, wants
solving as an in-cluster primitive, and does not want a control plane. Keep it as an *adapter*
(§11.3), never as the engine.

**C is what §8 already asked for**, made concrete. It is a generic ankka feature — nothing in it
knows about solving — and satisfactory is its first consumer:

```jsonc
// ankka jobs run -f solve-job.json         (mirrors `services apply`, one-shot)
{ "name": "solve-job-01J…",
  "job": {
    "image": "…/satisfactory-solver:0.1.0",
    "command": ["runner", "--job", "job_01J…"],           // claim *this* job, not "next"
    "env": [{ "name": "SAT_API_URL", "value": "http://api" },
            { "name": "SAT_RUNNER_TOKEN", "secretKeyRef": {…} }],
    "resources": { "instanceType": "xlarge" },
    "activeDeadlineSeconds": 43200,                       // = the job's maxLifetime
    "ttlSecondsAfterFinished": 3600,
    "backoffLimit": 0 } }                                  // retries are satisfactory's (§3.3)
```

What ankka gains, in its own terms:

| Piece | Shape |
|---|---|
| `crd` | `AnkkaJob`: spec `{projectId, jobName, image, command, env, cpuMillis, memoryMiB, activeDeadlineSeconds, ttlSecondsAfterFinished, backoffLimit}`; status `{phase, startedAt, finishedAt, exitCode, detail, observedGeneration}` |
| `operator` | a `JobReconciler` beside `ServiceReconciler`: renders a `batch/v1 Job` (owner-referenced, `restartPolicy: Never`), the same ServiceAccount pattern **minus** the pod-list Role — a job is not a cluster and must not be able to see one; **no CNPG database** — a job has no journal; `JobLifecycleRules` derived every pass from the Job's conditions, six words: `Pending · Running · Succeeded · Failed · Stopped · DeadlineExceeded` |
| `controlplane` | `JobEntity` (event sourced, like `ServiceEntity`), a `JobsView`, `/projects/{p}/jobs` endpoints. Stop = delete the Job with `propagationPolicy: Foreground`; the entity keeps the record. Job names are unique per project and **never reused** — a job is a fact that happened, closer to a tenancy id than a service name |
| `cli` | `ankka jobs run / list / get / stop / logs` — `logs` already exists for services and reads the same `pods/log` |
| **new concept** | **A machine credential.** Today every control-plane call is a Keycloak user. `api` calling `POST /jobs` is a service, not a person: ankka needs project-scoped service accounts or API tokens, with a role that can create jobs and nothing else. Without this, satisfactory would have to hold a human's token — or a Kubernetes credential of its own, which is the boundary ankka's RBAC exists to keep |

And a **readiness rule that is not membership**: an `AnkkaService` is `Ready` when the pod has
joined its cluster. An `AnkkaJob` has no cluster; it is `Running` when the container is, and the
image must not be required to form one. Which means:

**The runner must run without an actor system.** `modules/runner` depends on `protocol` +
`model-spi` and nothing Pekko. The `solver` *service* wraps it in a `RuntimeExtension` for the pool;
the *job* image runs `runner`'s own `main`. One jar, two entrypoints — and the dedicated pod is
faster to start and cannot be downed by `keep-majority`, because it is not in anything.

Satisfactory's side: a `DedicatedDispatcher` **workflow** (this is where a workflow finally earns
its place — §3.4): `acquire tenant slot → POST ankka /jobs → await claim, timeout 5 min → watch
status → on ankka `Failed` with no claim: requeue to the pool → on terminal: `DELETE /jobs/{name}`
(or let TTL do it)`. Selected by `resources.profile`; `standard` goes to the pool, `dedicated` and
`large` go here.

### 11.2 The deeper integration: a Solver component in ankka

Everything above treats satisfactory as an ankka *application*. There is a reading of "integrate
with ankka itself" that goes one level further, and the existing `Agent` is the precedent.

An agent is a component sharded per session, serialized per conversation, whose handler returns an
effect that the runtime carries out by talking to something slow and external, streaming output as
it goes — `thenStream()` → `Source[String, NotUsed]` → `sse`. Replace *model* with *solver* and
*session* with *job* and every line of that describes solving:

```scala
final class RosterSolver extends Solver[Roster]:            // hypothetical ankka-solver module
  def solve(problem: Roster): SolveEffect[Roster] =
    effects
      .model(EmployeeScheduling.v1)
      .terminate(spentLimit = 5.minutes, unimprovedSpentLimit = 30.seconds)
      .weights("Undesired day" -> "0hard/5soft")
      .thenStream()                                         // Source[Solution[Roster], NotUsed]

sse("/{jobId}/solutions") { (jobId: String) =>
  client.forSolver(JobId(jobId)).stream(RosterSolver.solve)(problem).map(Codecs.encode)
}
```

What it would give: solving becomes a component *any* ankka application can register — an ops app
that re-plans a roster when a shift is dropped calls its own solver, in-process, journaled, with no
satisfactory service in between. `SolveJobEntity` becomes the runtime's job memory the way session
memory is the agent's. Satisfactory-the-product then becomes what `samples/shopping-cart` is to
entities: the thing that proves the module, plus tenancy and an API.

What it costs, and why it is not first: **the CPU problem from §2 moves inside ankka.** A solver
shard on a cluster node saturates that node for minutes; ankka's Kubernetes overlay would then down
it. The honest fix is **node roles** — Pekko cluster roles, so a service's descriptor can say
`"roles": {"solver": {"instances": 3, "instanceType": "xlarge"}}` and the runtime hosts solver
shards only on those nodes, on their own dispatcher — and ankka has no notion of a heterogeneous
service today. That is a bigger ankka feature than `AnkkaJob`, and it is the prerequisite for this
shape being safe. It is the right long-term seam; it is not the right first one. Build A, add C, and
let the `SolverModel` SPI and the runner protocol be designed so that an `ankka-solver` module can
wrap them later without a rewrite — which mostly means: no HTTP types in `runner`, and the throttle,
the fencing epoch and the warm-start logic living in `runner`, not in `api`.

### 11.2b Declined: satisfactory stays a separate product (2026-09-22)

§11.2 and §11.2a are kept for the reasoning; the outcome is that **there is no `Solver` component
in ankka**. Satisfactory is a product with an HTTP API, built as an ankka application, and an ankka
agent reaches it through tools. Why this wins over embedding:

- The CPU-on-cluster-nodes problem (§2, §11.2) leaves ankka entirely — no node roles needed.
- ankka never depends on Timefold or carries a model catalog.
- Typing at the boundary buys nothing an agent can use: it sees JSON schemas either way.
- It is the ecosystem's proven shape (Timefold's own platform is a REST API; Kestra is a client).
- `SolveJobEntity`, leases, fencing and the runner protocol are built as *application* code here.
  Extract a job substrate into ankka when a second user exists, not before.

What ankka still needs: `AnkkaJob` and machine credentials (§11.1), unchanged.

**Agent integration.** A solve takes minutes, and an ankka tool is a synchronous call, so:

- **Tools are generated from the catalog.** `GET /v1/models/{id}/{version}` returns the input
  schema, constraints and defaults — that *is* a tool description.
  `SatisfactoryTools.forModel(client, "employee-scheduling", "1")` yields `solve_employee_scheduling`
  (parameter schema = the model's input schema, so the agent's arguments are validated by the same
  code the job runs), `get_best_solution(jobId)` and `terminate(jobId)`.
- **The agent decides and submits; a workflow owns the wait.** `multi-agent-planner`'s pattern:
  a workflow step consults the agent, the agent's tool submits and returns `jobId`, the step
  transitions to `awaitSolution`, and satisfactory's **webhook** (§3.4) hits an endpoint in the
  ankka app that resumes the workflow with the result. Journaled, so a crash mid-solve resumes at
  the wait rather than re-submitting. A blocking tool is acceptable for a short batch solve and a
  demo; LLM-driven polling is not a design.
- Modules: `satisfactory-client` (plain HTTP + SSE, no ankka dependency) and `satisfactory-ankka`
  (the generated tools + a webhook endpoint), both published from this repository.

### 11.2a Component or work item? (superseded by 11.2b; kept for the reasoning)

Agent and Solver look like specialisations of a "work item": both keep track of something running
somewhere else. Decision: **no `WorkItem` supertype; `Solver` is its own component**, and the reuse
happens one layer down.

- What they share is a *pattern* ankka already has — component → effect algebra → runtime → external
  executor (`ModelProvider` ↔ `SolverModel`) — not a supertype. The effect algebra is the substance of
  a component and the two differ entirely; a common one reduces to `submit → stream`, which is a
  `Future` and testable of nothing. Precedent: entity and workflow share `EventSourcedBehavior` as a
  host and remain two components.
- What *is* generic is **durable long-running work done elsewhere**: leases, fencing epoch, sequenced
  progress ring, warm restart, pull-runner protocol, `updatesSince`. That becomes a job runtime
  (`JobEntity[Progress]` + runner protocol) that `SolverRuntime` uses first; `SolveJobEntity` is the
  solver's job memory as `SessionMemoryEntity` is the agent's. Agents move onto it later
  (`thenRunAsJob()`), if and when a long agent run needs it — not designed up front.
- **Routing is the caller's concern.** Static: the developer calls the right component. Dynamic: an
  agent reading tool descriptions (as `multi-agent-planner`'s selector does) or a workflow rule. The
  component's obligations are to *describe itself* (`SolverModel`'s schemas and constraints already
  are a tool description) and to be *reachable as a tool*: `SolverTool.of(RosterSolver.solve)` →
  submit returns a job handle, a second tool reads the best solution. The job handle — id, status,
  progress, result-when-done — is the one type that legitimately crosses both worlds.

### 11.3 What to do

1. **A stays the core** — job entity, pool, streaming. Nothing here changes it.
2. **C is the "spin up a pod per job" answer, and it is an ankka feature: `AnkkaJob` + machine
   credentials.** It follows ankka's own conventions rather than putting a Kubernetes client in an
   application, and it is reusable for anything that runs to completion (migrations, batch
   imports, an agent's long task). Write it as an ankka spec (`specs/008-jobs`), build it after
   phase 3 here.
3. **B becomes an adapter, if anyone wants `kubectl`:** a small controller watching `SolveJob` CRs
   that is a *client* of `POST /v1/{entity}` and mirrors status back into the CR. One source of truth,
   and it can be written in an afternoon once A exists.
4. ~~11.2 is the destination, gated on node roles in ankka.~~ **Declined — see 11.2b.** Satisfactory
   is a separate product; agents reach it through generated tools, with a workflow owning the wait.
   The seam rules stand for their own sake: the runner has no Pekko and no HTTP types, and the SPI
   is the only thing a model sees.

## 12. Three artefacts (decided 2026-09-22)

```
ankka                          platform. Depends on nothing here.
satisfactory                   the product, in this repo:
  modules/protocol               wire types + codecs                       (no ankka)
  modules/client                 satisfactory-client: HTTP + SSE           (no ankka)      [published]
  modules/model-spi, models/*    Java                                      (no ankka)
  modules/runner                 claim loop, throttle                      (no ankka, no HTTP types)
  modules/api, modules/solver    the two ankka services                    (ankka-sdk/http/runtime)  [images]
  modules/ankka-satisfactory     the extension library                     (ankka-sdk/http/agent + client) [published]
```

- ankka never depends on satisfactory. Satisfactory's *public surface* (`protocol`, `client`)
  never depends on ankka. The *services* are ankka applications and depend on ankka's libraries —
  by design.
- `ankka-satisfactory` depends on both, lives here, is versioned with satisfactory's API, and
  declares the ankka version it was built against (ankka's one-minor-lag rule applies).

What `ankka-satisfactory` provides, so that a fresh `ankka init` project can add one dependency and
write an agent that submits a job and a workflow that receives the result:

1. **Client** — the typed client, re-exported.
2. **Tools from the catalog** — `SatisfactoryTools.forModel(client, id, version)` → `FunctionTool`s
   whose parameter schema is the model's input schema (API.md §5, §6).
3. **`SatisfactoryWebhook`** — verify and decode, nothing more: `looksSigned` (an `Acl` predicate:
   header present, timestamp fresh) and `verify(request, body): Event` (HMAC-SHA256 over the body;
   typed `DatasetEvent` out). **The developer writes the endpoint** — its path and its `acl` — as
   they would any other; a library that mounted a route with an ACL nobody declared would break
   ankka's rule. `expose` gives the hostname; registering `https://<service>-<project>.<domain>/…`
   as a webhook is an operator act (`satisfactory webhooks create`, or the Platform API), not
   something the app does at startup. Submits made through the tools are tagged
   `ankka-workflow:<id>` / `ankka-session:<id>`; the payload carries tags, so an event routes itself
   back with no mapping entity. Body signing, not path signing: an ACL cannot see the body, but a
   path signature does not authenticate the payload. Duplicates (at-least-once) are answered by the
   workflow with a no-op effect, as ordinary workflow hygiene.
   *Verified against ankka (2026-09-22):* the server reads the body strictly to `Array[Byte]`
   (`HttpServer.scala:262`) and `FromBody.read` gets those exact bytes; `FromBody.contentType` is
   not enforced on requests. The library ships `given FromBody[Array[Byte]]` (declared
   `application/json`, since that is what the webhook sends and what enforcement would one day
   check), so `postBody[Array[Byte], _]` + ambient `request.header(...)` is HMAC-over-wire-bytes
   with no change to ankka.
4. **`FakeSatisfactory`** — in-memory, answers the client and fires webhooks locally; scripted and
   loud when the script runs out, as `TestModelProvider` is. `sbt test` needs nothing running.

It asks nothing of ankka. The "await the webhook" step is `thenPause(after, onTimeout)` — already
in `WorkflowEffect` — and the receiver resumes the workflow with a command whose effect
`transitionTo`s the next step. The timeout variant is what handles a webhook that never arrives:
`onTimeout` transitions to a step that polls `/metadata` once and decides.

## 13. Decisions I would want confirmed

1. **Model catalog first, BYO-model later** (§1). The alternative — a generic declarative CSP
   language interpreted onto one universal Timefold model — is possible but gives up incremental
   score calculation on real domain objects, which is where Timefold's performance comes from.
2. **Pool first, per-job pods as an ankka feature after** (§1, §8). The alternative is building the
   ankka feature first; it delays everything else by one platform feature and the runner protocol
   is identical either way.
3. **Models in Java** (§6).
4. **Postgres blob store for v1** (§3.4) — fine to ~10s of MB per solution; object storage when
   retention or size says so.
