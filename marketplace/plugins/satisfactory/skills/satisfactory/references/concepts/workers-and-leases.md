# Workers and leases

> How a worker claims a dataset, why every report carries a lease epoch, what happens when a worker dies mid-solve, and how a rolling deploy drains without losing a solution.

Source: https://satisfactory.ankka.cloud/concepts/workers-and-leases/
A worker is a process that pulls datasets from the API, solves them with Timefold, and reports every
improvement back. Workers are never called; they ask. The API therefore needs no way to address a
particular worker, a busy worker simply does not ask, and a fixed-size pool degrades into a queue
rather than into overload.

The `solver` service is a pool of workers. Each instance runs one claim loop per slot, on its own
virtual thread, and a slot is one concurrent solve. Timefold Community solves single-threaded, so one
slot per core is an honest capacity model; a worker keeps one core spare so the instance's cluster
heartbeats stay on time. `SAT_SLOTS` overrides the count.

## The claim loop

Each slot asks the API for work with a long poll, `POST /internal/leases`, naming the models this worker
can solve. The API answers with a claim or with nothing after `claim-wait`; an empty answer makes the
slot wait `claimBackoff` (one second) and ask again. A claim carries the dataset's specification, the
lease `epoch`, the lease's time to live and a `mode`:

| Mode | The worker |
|---|---|
| `full` | validates the input, scores it, records the input's metrics and analysis, then solves unless the operation was `NONE` |
| `solve` | solves a dataset that was already validated and computed |
| `warm` | decodes the dataset's best solution so far and continues from it, skipping construction |

The runner's tests drive a worker against a scripted channel, which is the API in memory:

```scala
test("one slot works through a queue one dataset at a time") {
  val channel = ScriptedChannel()
  channel.enqueue(ScriptedChannel.claim("ds_a", spentLimit = "PT1S"), small)
  channel.enqueue(ScriptedChannel.claim("ds_b", spentLimit = "PT1S"), small)
  val worker = Worker(
    WorkerConfig("w1", slots = 1, claimBackoff = 100.millis, heartbeat = 500.millis),
    ModelCatalog.of(EmployeeScheduling.V1),
    channel
  )
  worker.start()
  try eventually(60.seconds)(channel.completes.size == 2)
  finally worker.stop()
  assertEquals(channel.completes.asScala.map(_._1).toSet, Set("ds_a", "ds_b"))
  assert(channel.calls.asScala.contains("register w1"))
  assert(channel.calls.asScala.contains("deregister w1"))
}
```

## The lease epoch is a fencing token

When the API grants a claim it records a lease: the worker's id, an epoch, and an expiry. Every request
the worker makes for that dataset carries the epoch. If the lease expires and the dataset is re-queued,
the next claim gets a higher epoch, and a report from the old epoch is refused with `409` before anything
is persisted. The worker that receives a `409` stops its solve, discards it and frees the slot.

This rule exists because a worker that was partitioned, presumed dead and replaced *will* come back and
report. Without fencing it would overwrite its successor's progress. The rule is enforced in the dataset
entity's fold, so replaying the journal agrees with what happened live.

## Heartbeats carry control

Every five seconds per held dataset the worker sends a heartbeat with the epoch, the score calculation
speed, the move count and its recent log lines. The heartbeat renews the lease, and the reply is how the
API talks to a worker: it may say `terminate`, which stops the solve early and completes the dataset with
its best solution, or `drain`, which makes the worker release the dataset back to the queue. Terminate
latency is therefore the heartbeat interval.

Log lines are scrubbed before they leave the worker: a line longer than 512 characters, or one containing
a brace, a bracket or a quote, is replaced with a note of its length, so a dataset's contents cannot leak
through the logs.

## Reports are throttled, and the final one always arrives

Timefold finds hundreds of new best solutions a second at the start of a solve. The worker keeps only the
latest in a throttle and reports at most one per `report-interval` (one second by default, never under
250 milliseconds), and always reports the final one. The solver hands the throttle a planning clone on
its own thread, so encoding the solution does not race the solver.

A report uploads the solution as a blob and then tells the API its score. The API records it only if the
score is strictly better than the dataset's best, assigns the sequence number, and answers whether it was
recorded. After a warm restart the new worker's first reports may equal the old best, and refusing them
is what keeps the stream monotonic.

## When a worker dies

A lease that is not renewed within `lease-ttl` (20 seconds) expires. The dataset is re-queued with a
warm-start reference to its best solution, and the next worker continues from there. A crashed worker
costs at most one report interval of progress plus the lease's time to live. After `max-attempts` lost
leases (three) the dataset ends `SOLVING_FAILED` with its best solution still readable.

Timefold has no checkpoint of solver state, and none is needed: a solver given an already-initialised
solution skips construction and goes straight to local search.

## Rolling deploys drain

On `SIGTERM` a worker stops taking claims, terminates every solve it holds early, reports each one's
final best, releases each dataset back to the queue with reason `shutdown`, and deregisters. This
completes well inside the platform's grace period. An operator can do the same to one worker without
stopping it, through the drain action described in [Operate the service](../deploy/operate.md); the
worker then releases with reason `drained` and keeps running without claiming.

## Where the runner runs

The claim loop, the throttle and the solve session are one library, `satisfactory-runner`, with no
Pekko and no HTTP types in it. The `solver` service wraps it in an ankka runtime extension and talks to
the API over HTTP. For development the same runner runs inside `api`, talking to the dataset entities
directly, and `sbt api/run` is then the whole system in one process. The seam is kept so that a
dedicated pod could one day run the runner's own `main` with no actor system at all.
