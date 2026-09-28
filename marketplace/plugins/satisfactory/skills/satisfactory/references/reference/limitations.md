# Limitations

> What satisfactory does not do yet, stated plainly, so a reader who hits one of them knows it is the system and not their code.

Source: https://satisfactory.ankka.cloud/reference/limitations/
## Solving

- **One thread per solve.** `maxThreadCount` is capped at 1, because the Community edition of Timefold
  Solver solves single-threaded.
- **No match counts or justifications in a score analysis.** Each constraint's contribution is computed
  by switching it off and scoring again. `includeJustifications` is answered with `"unsupported"`.
- **A dataset's contents may not be changed while it solves.** Deriving a new dataset supersedes the
  old one; the running solver is not changed in place.

## Models

- **Two models, compiled in.** Employee scheduling and vehicle routing are the catalog. A model is
  added by implementing the interface and rebuilding the solver image; uploading a model as a jar to a
  running installation is not supported, because it would mean running untrusted code in the pool.
- **Employee scheduling's extra routes are not implemented.** The Timefold model's recommendation and
  assignability routes have no counterpart.
- **No maps.** Vehicle routing computes travel time from coordinates; there is no road network or map
  provider.

## Capacity

- **The pool is a fixed size.** Its capacity is the number of solver instances times their slots, and
  an ankka `large` instance has one solving slot. Work beyond it waits in the queue. There is no
  autoscaling on queue depth and no dedicated pod per dataset.
- **Rate limits are the tenant's.** Submits per minute and concurrency are enforced per tenant; there is
  no limit at the gateway.

## Streaming

- **No `id:` frames.** A browser's `EventSource` cannot resume with `Last-Event-ID`; pass `after` on
  reconnect. Each `data:` payload is a JSON string that must be parsed before the frame inside it.
- **A bounded history.** A stream resumed from a sequence number older than the entity keeps opens with
  the current best and says how many frames were skipped.

## Storage

- **Solutions live in Postgres.** The blob store is a table in the api service's own database, which is
  fine to tens of megabytes per solution. Object storage is not supported.
- **Retention is per tenant and bodies only.** After the retention period a dataset's bodies are
  deleted; its metadata remains.

## Deployment

- **Two settings the descriptors leave unset.** `SAT_PUBLIC_URL` and `SAT_METRICS_TOKEN` are not in
  `deploy/api.json`; without them webhook links point at `http://localhost:9000` and `/metrics` refuses
  everyone. [Deploy on ankka](../deploy/deploy-on-ankka.md) says to set them.
- **The runner endpoint is exposed with the API.** `/internal` is reachable wherever the api is, and
  the runner token is the only thing in front of it.
