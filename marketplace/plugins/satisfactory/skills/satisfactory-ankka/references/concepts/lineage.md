# Lineage instead of mutation

> Why a changed problem is a new dataset derived from the old one, the three ways to derive one, and what happens when the parent is still solving.

Source: https://satisfactory.ankka.cloud/concepts/lineage/
A dataset is never changed in place. A changed problem is a new dataset derived from the old one, with
`parentId` naming its source and `originId` naming the root of the chain. The parent keeps its solution,
the child starts from it, and the history of a plan is a chain of datasets rather than a log of edits to
one.

There is no continuous mode. A plan that should keep improving is a long `spentLimit` plus the event
stream, and a plan that has changed is a new dataset. Streaming is not a mode either: any dataset's
progress can be streamed.

## Three ways to derive a dataset

| Route | Starts from | Use it for |
|---|---|---|
| `POST /{id}/from-input?select=UNSOLVED` | the parent's input, unsolved | re-solving the same problem with different termination or weights |
| `POST /{id}/from-input?select=SOLVED` | the parent's output as input | continuing from where the parent got to |
| `POST /{id}/from-patch` | the parent's output (`select=SOLVED`, the default) or input, with a patch applied | a change to the problem: a dropped shift, a new visit, an employee now unavailable |
| terminate and `POST` afresh | nothing | a short horizon, infrequent or large changes, or when previous assignments need not survive |

Both derivations accept an optional `config`, and the child's configuration is resolved in layers: the
child's own, then the profile it names, then the parent's, then the model's defaults.

## Patches

A patch is a list of operations, each an `op` of `add`, `remove` or `replace`, a `path` into the
dataset, and a `value` for `add` and `replace`:

```json
{ "patch": [
    { "op": "replace", "path": "/employees/[name=Amy Cole]/unavailableDates", "value": ["2026-10-07", "2026-10-08"] },
    { "op": "add",     "path": "/shifts/-", "value": { "id": "2026-10-08-Ambulatory-3", "start": "2026-10-08T14:00:00",
                                                        "end": "2026-10-08T22:00:00", "location": "Ambulatory care", "requiredSkill": "Nurse" } },
    { "op": "remove",  "path": "/shifts/[id=2026-10-06-Inpatient-1]" } ],
  "config": { "run": { "termination": { "spentLimit": "PT1M" } } } }
```

A path may select an array item by `[field=value]` or by index, and `-` appends. Which collections and
which key fields a model allows are its patch paths, listed in its descriptor: employee scheduling
allows `/employees` by `name` and `/shifts` by `id`. A path outside them, or a patch that makes the
input violate the model's schema, is a `400` naming the operation.

Patching the solved dataset keeps every assignment the patch did not touch, so the solver starts from
a plan that is already mostly right and changes as little as it must. The `disruptionPercentage` KPI
measures how much it changed. Patching the input instead, with `select=UNSOLVED`, plans afresh.

## Deriving from a dataset that is still solving

Deriving from a `SOLVING_ACTIVE` parent supersedes it. The parent ends `SOLVING_COMPLETED` with its
best solution so far, its metadata names the child in `supersededBy`, and the child warm-starts from
that best solution with the patch applied. A client need not terminate a solve to change its problem.

A stream opened on the parent with `follow=lineage` sends one final frame for the parent and continues
with the child's frames instead of closing. [Stream progress](../build/streaming.md) shows it.

## What lineage gives you

- **A history.** Every plan you ever had is a dataset with a score, a solution and a parent.
- **Warm starts for free.** A child begins from the parent's solution, so a small change costs a small
  solve.
- **Comparisons.** Two children of one parent with different weights are two datasets to compare.
- **No lost work.** Superseding a solve keeps what it found.
