# Glossary

> The words this documentation uses in a specific sense, each defined once.

Source: https://satisfactory.ankka.cloud/reference/glossary/
**API key.** A tenant-scoped credential for the model API, sent as `X-API-KEY`, with a role of
`read-only` or `read-write`. Shown once when created, stored hashed.

**Claim.** A worker's request for work: a long poll that answers with a dataset, its lease epoch and a
mode, or with nothing.

**Configuration profile.** A named, tenant-owned bundle of run configuration and constraint weights
for one model, referenced by id or name in a submit's `configurationId`. `standard` is the model's
defaults.

**Constraint.** One rule of a model, hard, medium or soft, with a name, a weight field and a default
weight. A weight of zero disables it.

**Dataset.** One submitted planning problem and everything that happens to it: input, validation,
solving and output. Immutable after submission, with a place in a lineage.

**Entity.** The name of what a model's requests submit, which is the path segment its datasets live
under: `schedules`, `route-plans`.

**Epoch.** The number a lease is granted with. Every worker command carries it, and a command from a
superseded epoch is refused. A fencing token.

**Feasible.** A solution whose hard score is zero: no hard constraint is violated.

**Final status.** A status after which nothing more happens to a dataset: `SOLVING_COMPLETED`,
`SOLVING_INCOMPLETE`, `SOLVING_FAILED`, `DATASET_INVALID`, and `DATASET_COMPUTED` when no solve was
requested.

**KPI.** A key performance indicator: a typed, titled field the model computes about a solution, such
as `unassignedShifts`.

**Lease.** A worker's exclusive right to solve a dataset for a time, renewed by heartbeat, expiring
without one.

**Lineage.** The chain of datasets derived from one another: `parentId` names the source, `originId`
the root.

**Model.** A planning problem type with its own API, schemas and constraints, versioned in its path.
Compiled Java behind the `SolverModel` interface.

**Operator.** A person whose platform token carries the operator role; creates tenants, sees every
tenant, and uses the `/ops` routes.

**Patch.** A list of `add`, `remove` and `replace` operations on a dataset, addressing collection
items by `[field=value]`, that derives a new dataset.

**Score.** Timefold's measure of a solution, one number per level, formatted like `0hard/-1830soft`.
Higher is better, and a solution is compared level by level, most significant first.

**Sequence number (`seq`).** The dataset entity's counter, incremented on every recorded change.
Event frames and webhook events carry it; `after` resumes from it.

**Slot.** One concurrent solve on a worker. A tenant's `concurrency` limit is in slots too.

**Supersede.** What deriving a new dataset from one that is still solving does to the parent: it
completes with its best solution and names the child in `supersededBy`.

**Tenant.** An isolated workspace: members, keys, limits, profiles, subscriptions and datasets.

**Termination.** When a solve stops: a time limit, a time without improvement, a step or move count, or
diminishing returns.

**Warm start.** Solving from an existing solution rather than from nothing: construction is skipped
and local search starts from the assignments given. How a child continues from its parent and how a
lost lease resumes.

**Worker.** A process that claims datasets, solves them and reports improvements: the `solver`
service's pool, or the api's in-process worker in development.
