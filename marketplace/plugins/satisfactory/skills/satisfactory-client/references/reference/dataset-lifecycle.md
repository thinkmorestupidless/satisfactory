# Dataset lifecycle

> The ten statuses a dataset moves through, in which order, which are final, and what a terminate, a failure, a lost lease and a supersession do to them.

Source: https://satisfactory.ankka.cloud/reference/dataset-lifecycle/
A dataset's `solverStatus` is one of ten values, and it only moves forward. They are the Timefold
Platform's statuses, adopted exactly.

| `solverStatus` | Meaning |
|---|---|
| `DATASET_CREATED` | Created from a new input or a patch to an existing dataset |
| `SOLVING_SCHEDULED` | In the tenant's queue, waiting for a worker and a free slot |
| `DATASET_VALIDATED` | A worker ran the model's validation and found no error |
| `DATASET_INVALID` | The model's validation found an error; the issues are at `/validation-result` |
| `DATASET_COMPUTED` | The input's own score, metrics and score analysis have been computed |
| `SOLVING_STARTED` | The input is being turned into a planning problem |
| `SOLVING_ACTIVE` | Solving; improving solutions are being recorded |
| `SOLVING_COMPLETED` | Solving has ended and a solution exists; nothing more will be generated |
| `SOLVING_INCOMPLETE` | Solving was stopped before any solution existed |
| `SOLVING_FAILED` | An error stopped solving; `failureMessage` says what, and the best solution so far stays readable |

## The order

```text
operation=SOLVE   CREATED → SCHEDULED → VALIDATED → COMPUTED → STARTED → ACTIVE → COMPLETED
operation=NONE    CREATED → VALIDATED → COMPUTED                (then POST /{id} → SCHEDULED → …)
invalid input     … → VALIDATED? → INVALID
terminate         any active status → COMPLETED if a solution exists, otherwise INCOMPLETE
failure           any status → FAILED
```

Validation and computing happen on a worker after the dataset is claimed, so a dataset submitted with
`operation=SOLVE` is `SOLVING_SCHEDULED` before it is `DATASET_VALIDATED`.

## Final statuses

`DATASET_INVALID`, `SOLVING_COMPLETED`, `SOLVING_INCOMPLETE` and `SOLVING_FAILED` are final, and so is
`DATASET_COMPUTED` while no solve has been requested. The event stream closes on a final status, the
webhook fires on it, and the client library's `awaitFinal` returns on it.

## What moves a dataset

| Cause | Effect |
|---|---|
| a worker's phase reports | `DATASET_VALIDATED`, `DATASET_INVALID`, `DATASET_COMPUTED`, `SOLVING_STARTED`, `SOLVING_ACTIVE` in turn; the first recorded solution also makes it `SOLVING_ACTIVE` |
| the solve's termination | `SOLVING_COMPLETED` |
| `DELETE /{id}` by the client, or an operator's terminate | `SOLVING_COMPLETED` with the best solution, or `SOLVING_INCOMPLETE` if there is none |
| a child derived while active | `SOLVING_COMPLETED` with `supersededBy` set; the child warm-starts from the best solution |
| the tenant's lifetime ceiling | `SOLVING_COMPLETED` or `SOLVING_INCOMPLETE`, as a terminate |
| a lost lease | no status change; the dataset is re-queued and warm-starts from its best solution on another worker |
| the third lost lease, or a worker's failure report | `SOLVING_FAILED` |

A lease lost because a worker was drained or shut down gracefully does not count towards the three;
only an expired lease does.

## Flags that are not statuses

Purging, restoring and retention expiry change what is stored, not the status. A purged dataset keeps
its status and answers `410` for its bodies; a restored one answers them again; an expired one has had
its bodies deleted and is left out of listings unless `includeExpired` is asked for.
