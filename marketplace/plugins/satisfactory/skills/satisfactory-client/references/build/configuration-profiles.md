# Configuration profiles

> Create a named bundle of termination and constraint weights for a model on a tenant, refer to it from a submit by id or name, and know how it layers under the request's own configuration.

Source: https://satisfactory.ankka.cloud/build/configuration-profiles/
A configuration profile is a named bundle of run configuration and constraint weights, owned by a
tenant, for one model. A submit refers to it with `configurationId`, by id or by name, instead of
repeating the same termination and weights on every request. Every tenant has a read-only `standard`
profile per model, which is the model's defaults, and may create up to fifty of its own.

## Create one

Profiles are managed on the platform API by a tenant admin:

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  $SAT/api/platform/v1/tenants/$TENANT/configurations \
  -d '{
    "model": "employee-scheduling",
    "name": "fast",
    "description": "A quick answer for what-if questions.",
    "defaultConfigProfileId": "standard",
    "runConfiguration": { "termination": { "spentLimit": "PT30S", "unimprovedSpentLimit": "PT10S" } },
    "modelConfiguration": { "undesiredDayForEmployeeWeight": 3, "balanceEmployeeShiftAssignmentsWeight": 2 }
  }'
```

```json
{"id":"cfg_01K6…","model":"employee-scheduling","name":"fast","description":"A quick answer for what-if questions.",
 "defaultConfigProfileId":"standard","runConfiguration":{"termination":{"spentLimit":"PT30S","unimprovedSpentLimit":"PT10S"}},
 "modelConfiguration":{"undesiredDayForEmployeeWeight":3,"balanceEmployeeShiftAssignmentsWeight":2},
 "resourcesConfiguration":null,"updatedAt":"2026-09-28T10:00:00Z","readOnly":false}
```

| Field | Rule |
|---|---|
| `model` | the registration key the profile is for |
| `name` | up to 60 characters, unique per model on the tenant |
| `description` | up to 1000 characters |
| `defaultConfigProfileId` | the profile this one derives from; `standard` for the model's defaults |
| `runConfiguration` | `maxThreadCount` and `termination`, the same fields as a submit's `config.run` |
| `modelConfiguration` | the weights, `<key>Weight` per constraint, checked against the model's schema |

A weight that is not one of the model's constraints, or is negative, is a `400` naming it. A second
profile with the same name, or a fifty-first, is a `409`. `PUT` replaces a profile and `DELETE` removes
it; both answer `405` for `standard`.

## Use one

```bash
curl -s -X POST -H "X-API-KEY: $KEY" -H 'Content-Type: application/json' \
  "$API/schedules?configurationId=fast" -d '{"modelInput": { … }}'
```

The profile's configuration applies under the request's own: a field the request sets wins, a field it
leaves out comes from the profile, and what neither sets comes from the model's defaults. For a derived
dataset the parent's configuration sits between the profile and the defaults. `GET /{id}/config`
returns the resolved configuration a dataset was solved with.

## List them

`GET /tenants/{tenantId}/configurations?model=employee-scheduling` lists a model's profiles, `standard`
included, and any member of the tenant may read them.
