# Employee scheduling

> The employee-scheduling model, version v1 — its input and output, constraints and weight fields, KPIs, validation issues, patch paths and demo datasets.

Source: https://satisfactory.ankka.cloud/reference/employee-scheduling/
Employee shift scheduling assigns employees to shifts: every shift gets an employee with the skill it
needs, nobody works overlapping shifts, on unavailable days, or without ten hours' rest, and the rest is
balanced against preferences. It is a `STABLE` model.

| | |
|---|---|
| Registration key | `employee-scheduling` |
| Version | `v1` |
| Base path | `/api/models/employee-scheduling/v1` |
| Entity | `schedules`, so a dataset is `/v1/schedules/{id}` |
| Score | `HardSoftBigDecimalScore`, written `0hard/-3603soft`; hard and soft levels only |
| Features | pinning, real-time planning, weights |

## Input

The input is an `EmployeeScheduleInput`: a list of employees and a list of shifts. Times are ISO-8601.

```json
{
  "employees": [
    { "name": "Amy Cole", "skills": ["Doctor"],
      "unavailableDates": ["2026-10-07"], "undesiredDates": ["2026-10-11"], "desiredDates": ["2026-10-09"] }
  ],
  "shifts": [
    { "id": "2026-10-05-Ambulatory-0", "start": "2026-10-05T06:00:00", "end": "2026-10-05T14:00:00",
      "location": "Ambulatory care", "requiredSkill": "Doctor", "employee": null, "pinned": false }
  ]
}
```

| Field | Required | Meaning |
|---|---|---|
| `employees[].name` | yes | Unique; what a shift's `employee` refers to |
| `employees[].skills` | yes | The skills a shift may require |
| `employees[].unavailableDates`, `undesiredDates`, `desiredDates` | no | Dates the employee cannot, would rather not, or would like to work |
| `shifts[].id` | yes | Unique |
| `shifts[].start`, `end` | yes | The shift's window |
| `shifts[].location` | yes | Reported in the input metrics |
| `shifts[].requiredSkill` | yes | An employee without it is a hard constraint violation |
| `shifts[].employee` | no | An existing assignment; kept as the starting point |
| `shifts[].pinned` | no | `true` keeps the assignment fixed while solving |

## Output

The output is an `EmployeeSchedule`: the input with every shift's `employee` filled in. It is accepted
unchanged as an input, which is how a solved schedule is continued or patched.

## Constraints

Each constraint has a weight field in `config.model.overrides`, an integer of zero or more; the default
is 1 and 0 disables the constraint.

| Constraint | Weight field | Level | Penalised |
|---|---|---|---|
| Missing required skill | `missingRequiredSkillWeight` | hard | once per shift |
| Overlapping shift | `overlappingShiftWeight` | hard | per overlapping minute |
| At least 10 hours between 2 shifts | `atLeast10HoursBetweenTwoShiftsWeight` | hard | per missing minute |
| Max one shift per day | `maxOneShiftPerDayWeight` | hard | per extra shift |
| Unavailable employee | `unavailableEmployeeWeight` | hard | per minute |
| Undesired day for employee | `undesiredDayForEmployeeWeight` | soft | per minute |
| Desired day for employee | `desiredDayForEmployeeWeight` | soft | rewarded per minute |
| Balance employee shift assignments | `balanceEmployeeShiftAssignmentsWeight` | soft | by unfairness |

A score analysis names constraints by the first column.

## KPIs and input metrics

| KPI | Type | Meaning |
|---|---|---|
| `assignedShifts` | integer | Shifts with an employee |
| `unassignedShifts` | integer | Shifts without one; a feasible schedule has none |
| `workingTimeFairnessPercentage` | number | How evenly working time is spread, as a percentage |
| `disruptionPercentage` | number | How many assignments changed from the solution this one continued from |

| Input metric | Meaning |
|---|---|
| `employees`, `shifts`, `pinnedShifts`, `locations` | Counts from the input |

## Validation issues

Model-level validation runs on a worker after the schema has passed. An error stops the dataset at
`DATASET_INVALID`; a warning is reported and solving continues.

| Code | Severity |
|---|---|
| `EmptySchedule` | error |
| `NoEmployees` | error |
| `DuplicateEmployeeName` | error |
| `DuplicateShiftId` | error |
| `ShiftUnknownEmployee` | error |
| `ShiftEndsBeforeStart` | error |
| `RequiredSkillUnknown` | warning |

## Patch paths

A `from-patch` request may address `/employees` by `name` and `/shifts` by `id`:
`/employees/[name=Amy Cole]/unavailableDates`, `/shifts/[id=2026-10-05-Ambulatory-0]/pinned`, and
`/shifts/-` to append.

## Demo datasets

| Id | Description | Recommended `spentLimit` |
|---|---|---|
| `SMALL` | 15 employees, 2 weeks, 3 locations | `PT30S` |
| `LARGE` | 50 employees, 4 weeks, 7 locations | `PT5M` |

Both are generated from a fixed seed and start on 2026-10-05, so a solve of the same demo is comparable
across runs. `GET /v1/demo-data/SMALL` returns a whole request with the recommended configuration, and
`GET /v1/demo-data/SMALL/input` the input alone.
