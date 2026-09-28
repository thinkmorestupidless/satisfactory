---
title: Vehicle routing
description: The vehicle-routing model, version v1 — its input and output, constraints and weight fields, KPIs, validation issues, patch paths and demo datasets.
kind: reference
models: [vehicle-routing]
related: [build/submit-and-poll.md, build/models.md, reference/model-api.md, reference/employee-scheduling.md]
---

# Vehicle routing

Vehicle routing plans the visits each vehicle makes: every visit is served within its time window by a
vehicle with enough capacity, and the total driving time is minimised. It is a `PREVIEW` model: it works
as documented, and its API may still change within `v1`.

| | |
|---|---|
| Registration key | `vehicle-routing` |
| Version | `v1` |
| Base path | `/api/models/vehicle-routing/v1` |
| Entity | `route-plans`, so a dataset is `/v1/route-plans/{id}` |
| Score | `HardMediumSoftScore`, written `0hard/0medium/-41220soft` |
| Features | time windows, capacities, real-time planning, weights |

## Input

The input is a `VehicleRoutePlanInput`: vehicles and visits. A location is a latitude and longitude
pair, and times are ISO-8601.

```json
{
  "vehicles": [
    { "id": "1", "capacity": 25, "homeLocation": [39.95, -75.16], "departureTime": "2026-10-06T07:30:00" }
  ],
  "visits": [
    { "id": "12", "name": "Sam Lee", "location": [39.97, -75.13], "demand": 2,
      "minStartTime": "2026-10-06T08:00:00", "maxEndTime": "2026-10-06T12:00:00", "serviceDuration": "PT20M" }
  ]
}
```

| Field | Required | Meaning |
|---|---|---|
| `vehicles[].id` | yes | Unique |
| `vehicles[].capacity` | yes | The total demand the vehicle can carry |
| `vehicles[].homeLocation`, `departureTime` | yes | Where and when its route starts |
| `vehicles[].visits` | no | An existing route, as visit ids in order; kept as the starting point |
| `visits[].id` | yes | Unique |
| `visits[].location`, `demand` | yes | Where it is and how much capacity it takes |
| `visits[].minStartTime`, `maxEndTime`, `serviceDuration` | yes | The time window and how long the visit takes |
| `visits[].vehicle`, `arrivalTime` | no | An existing assignment |

## Output

The output is a `VehicleRoutePlan`: the input with each vehicle's `visits` in route order and each
visit's `vehicle` and `arrivalTime`. It is accepted unchanged as an input.

## Constraints

| Constraint | Weight field | Level |
|---|---|---|
| `vehicleCapacity` | `vehicleCapacityWeight` | hard |
| `serviceFinishedAfterMaxEndTime` | `serviceFinishedAfterMaxEndTimeWeight` | hard |
| `maximizeVisitsAssigned` | `maximizeVisitsAssignedWeight` | medium |
| `minimizeTravelTime` | `minimizeTravelTimeWeight` | soft |

Every default weight is 1, and 0 disables the constraint. The medium level is what makes an unassigned
visit worse than any amount of driving and better than any hard violation.

## KPIs and input metrics

| KPI | Type | Meaning |
|---|---|---|
| `assignedVisits`, `unassignedVisits` | integer | Visits on a route, and visits on none |
| `totalDrivingTimeSeconds` | integer | Driving time over every route |
| `lateVisits` | integer | Visits finishing after their window |
| `disruptionPercentage` | number | How many assignments changed from the solution this one continued from |

| Input metric | Meaning |
|---|---|
| `vehicles`, `visits`, `totalDemand`, `totalCapacity` | Counts and sums from the input |

## Validation issues

| Code | Severity |
|---|---|
| `EmptyPlan` | error |
| `NoVehicles` | error |
| `DuplicateVehicleId` | error |
| `DuplicateVisitId` | error |
| `VisitUnknownInRoute` | error |
| `VisitRoutedTwice` | error |
| `TimeWindowInverted` | error |
| `DemandOverAnyCapacity` | warning |

## Patch paths

A `from-patch` request may address `/vehicles` by `id` and `/visits` by `id`.

## Demo datasets

| Id | Description | Recommended `spentLimit` | Tags |
|---|---|---|---|
| `PHILADELPHIA` | 55 visits, 6 vehicles | `PT30S` | `small`, `usa` |
| `FIRENZE` | 77 visits, 6 vehicles | `PT1M` | `medium`, `italy` |

Both are generated deterministically for 2026-10-06.
