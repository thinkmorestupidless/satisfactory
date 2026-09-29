---
title: How satisfactory works
description: The two services, why workers pull rather than being called, what the dataset entity guarantees, and where each piece of the system lives in the repository.
kind: concept
related: [concepts/workers-and-leases.md, concepts/models-and-datasets.md, deploy/deploy-on-ankka.md]
---

# How satisfactory works

satisfactory is two ankka services. `api` is exposed and holds the state: an event sourced entity per
dataset, the tenants with their keys and limits, the views that list and queue, the timers that expire
leases, and the consumers that deliver webhooks. `solver` is not exposed at all: it is a pool of workers
that pull datasets from `api` over HTTP, solve them with Timefold, and report back.

![satisfactory on an ankka cluster: applications, ankka applications and tenant administrators reach the api service over HTTPS through the installation's gateway, and administrators sign in with Keycloak. api runs three small instances as one Pekko cluster and holds the state: the model, platform, ops and runner endpoints, an entity per dataset and per tenant, views, lease timers and webhook consumers, in its own Postgres database with the blob store. solver runs three large instances, serves no HTTP, and pulls work from api over mutual TLS. The ankka control plane and operator create and own both services, and api delivers signed webhooks to the tenant's endpoint.](../assets/diagrams/architecture.svg)

## Why two services

Solving is CPU-bound for minutes to hours. In the same JVM as cluster heartbeats and a journal, a
saturated node or a long garbage collection pause gets the node marked unreachable, and ankka's
Kubernetes overlay would then down it. The two services also roll independently, so an API fix does not
interrupt a two-hour solve, and they size differently: `api` is small and odd-counted, `solver` is large
and as many instances as the budget allows.

## Why workers pull

A worker asks for work when it has a free slot, with a long poll, and is never called. Three things
follow. `api` needs no way to address a particular worker, which it could not have anyway: a
Kubernetes service address reaches some pod, not one. The solver serves nothing, so it has no inbound
surface at all. And backpressure is free: a busy worker does not ask, so a fixed-size pool degrades into
a queue rather than into overload. [Workers and leases](workers-and-leases.md) has the protocol.

## What the dataset entity guarantees

Every dataset is an event sourced entity, and three rules are enforced in its fold, so that replaying
the journal agrees with what happened live:

1. **The lease epoch is a fencing token.** Every worker command carries the epoch its lease was
   granted with, and a command from a superseded epoch is refused with a conflict before anything is
   persisted. A worker that was partitioned, presumed dead and replaced *will* come back and report.
2. **Only a strictly better score is recorded, and the entity assigns the sequence number.** After a
   warm restart the new worker's first reports may equal the old best; refusing them keeps the stream
   monotonic and keeps the journal from growing on noise.
3. **No event carries a body.** A solution can be megabytes and a dataset can produce thousands.
   Events carry a reference, the score and the model's KPIs; bodies live in the blob store, which is a
   table in the service's own Postgres.

Claiming follows ankka's rule that cross-entity checks happen at the edge: the runner endpoint asks a
view for queue-head candidates, which may be stale, then sends `lease` to each candidate's entity until
one accepts. The view proposes; the entity decides. A tenant's concurrency slot is acquired in the same
step from the tenant entity, which counts exactly because it is a single writer.

## What runs where

| Module | Published as | Depends on ankka |
|---|---|---|
| `protocol` | `satisfactory-protocol` | no: wire types and codecs, the webhook signature |
| `client` | `satisfactory-client` | no: HTTP and server-sent events over the JDK |
| `model-spi` | `satisfactory-model-spi` | no: the Java interface a model implements, and `ModelRuntime` |
| `models/employee-scheduling`, `models/vehicle-routing` | in the images | no |
| `runner` | in the images | no, and no Pekko, and no HTTP types |
| `api` | the `satisfactory-api` image | yes |
| `solver` | the `satisfactory-solver` image | yes |
| `ankka-satisfactory` | `ankka-satisfactory` | yes: tools, the webhook verifier, the fake |

The seams are deliberate. ankka never depends on satisfactory. The public surface, the protocol and the
client, never depends on ankka. The runner has no actor system in it, so a dedicated pod could run it
alone. A model sees only the interface, so a model author never meets the service.

## Development mode

`api` can host the worker itself. With `SAT_LOCAL_RUNNER` on, which is the default outside a
deployment, the same runner code runs in-process against the entities directly, and `sbt api/run` is
the whole system. [Run it on your machine](../get-started/run-locally.md) starts it.
