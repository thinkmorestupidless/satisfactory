# Your first solve

> Submit the employee scheduling demo dataset with curl, watch its score improve over server-sent events, fetch the solution and its KPIs, and read the per-constraint score.

Source: https://satisfactory.ankka.cloud/get-started/first-solve/
This page solves a two-week roster for fifteen employees, using the demo dataset the employee
scheduling model ships with. It needs a running service with a key, which
[Run it on your machine](run-locally.md) gives you as `sk_dev` on port 9000. Set two variables once:

```bash
export KEY=sk_dev
export API=http://localhost:9000/api/models/employee-scheduling/v1
```

Every model lives under `/api/models/{model}/{version}`, and every call carries the key in the
`X-API-KEY` header.

## Look at the model

The model describes itself: its name, its constraints and their default weights, the KPIs it reports,
and the JSON schemas of its input and output.

```bash
curl -s -H "X-API-KEY: $KEY" $API/model | head -c 600
```

```json
{"id":"employee-scheduling","version":"v1","entity":"schedules","name":"Employee Shift Scheduling",
 "description":"Assign employees to shifts: every shift gets an employee with the skill it needs, …",
 "maturity":"STABLE","features":["pinning","real-time planning","weights"], …
```

The `entity` is the name of the thing a request submits, so this model's datasets live under
`$API/schedules`.

## Submit the demo dataset

`demo-data/SMALL` is a complete request: the input plus a configuration with the recommended thirty
second limit. Pipe it straight into a submit:

```bash
curl -s -H "X-API-KEY: $KEY" $API/demo-data/SMALL \
  | curl -s -X POST -H "X-API-KEY: $KEY" -H 'Content-Type: application/json' \
      --data-binary @- "$API/schedules?name=first"
```

```json
{"id":"ds_01K6…","parentId":null,"originId":null,"name":"first","tags":[],
 "solverStatus":"DATASET_CREATED","score":null,"submitDateTime":"2026-09-28T10:15:02Z", … "seq":1}
```

The answer is `202` with the dataset's metadata. Nothing has been solved yet: the dataset is in the
queue, and a worker will pick it up within a second. Keep the id:

```bash
export ID=ds_01K6…
```

## Watch it improve

The dataset's event stream sends one frame per improvement, carrying the status and the score, and
closes when the solve ends:

```bash
curl -sN -H "X-API-KEY: $KEY" $API/schedules/$ID/events
```

```text
data: "{\"seq\":2,\"metadata\":{\"id\":\"ds_01K6…\",\"solverStatus\":\"SOLVING_SCHEDULED\", …}}"
data: "{\"seq\":4,\"metadata\":{\"id\":\"ds_01K6…\",\"solverStatus\":\"DATASET_COMPUTED\",\"score\":\"0hard/0soft\", …}}"
data: "{\"seq\":7,\"metadata\":{\"id\":\"ds_01K6…\",\"solverStatus\":\"SOLVING_ACTIVE\",\"score\":\"-3hard/-2310soft\", …}}"
data: "{\"seq\":8,\"metadata\":{\"id\":\"ds_01K6…\",\"solverStatus\":\"SOLVING_ACTIVE\",\"score\":\"0hard/-2085soft\", …}}"
data: "{\"heartbeat\":true}"
data: "{\"seq\":19,\"metadata\":{\"id\":\"ds_01K6…\",\"solverStatus\":\"SOLVING_COMPLETED\",\"score\":\"0hard/-1830soft\", …}}"
```

Each `data:` payload is a JSON string holding the frame; a client parses it twice. The
`DATASET_COMPUTED` frame carries the input's own score, before any solving. From `SOLVING_ACTIVE` on,
each frame is a strictly better score than the one before. A score whose hard part is `0` is feasible:
no hard constraint is broken, and the soft part then measures how well preferences are met. The
stream ends after `SOLVING_COMPLETED`, and opening it again on a finished dataset answers `410`.

## Fetch the solution

```bash
curl -s -H "X-API-KEY: $KEY" $API/schedules/$ID | head -c 500
```

```json
{"metadata":{"id":"ds_01K6…","solverStatus":"SOLVING_COMPLETED","score":"0hard/-1830soft", …},
 "modelOutput":{"employees":[…],"shifts":[{"id":"2026-10-05-Ambulatory-0","start":"2026-10-05T06:00:00",
   "end":"2026-10-05T14:00:00","location":"Ambulatory care","requiredSkill":"Doctor","employee":"Amy Cole","pinned":false}, …]},
 "inputMetrics":{"employees":15,"shifts":…,"pinnedShifts":0,"locations":3},
 "kpis":{"assignedShifts":…,"unassignedShifts":0,"workingTimeFairnessPercentage":97.4,"disruptionPercentage":0.0}}
```

`modelOutput` is the input with every shift's `employee` filled in. It is also valid input, which is how
a solved roster is continued or patched. The metrics are the model's: what it measured about the
problem, and what it measured about the solution.

## Read the score

The score analysis says what each constraint contributed:

```bash
curl -s -H "X-API-KEY: $KEY" $API/schedules/$ID/score-analysis
```

```json
{"score":"0hard/-1830soft","constraints":[
  {"name":"Missing required skill","weight":"1hard/0soft","score":"0hard/0soft"},
  {"name":"Undesired day for employee","weight":"0hard/1soft","score":"0hard/-960soft"},
  {"name":"Balance employee shift assignments","weight":"0hard/1soft","score":"0hard/-870soft"}, …]}
```

Each constraint's contribution is what the score changes by when that constraint is switched off, and
the contributions sum to the total.

## Solve it again with different weights

A dataset is never changed in place. To weigh undesired days more heavily, derive a new dataset from
this one's input with a different configuration:

```bash
curl -s -X POST -H "X-API-KEY: $KEY" -H 'Content-Type: application/json' \
  "$API/schedules/$ID/from-input?name=second" \
  -d '{"config":{"run":{"termination":{"spentLimit":"PT20S"}},"model":{"overrides":{"undesiredDayForEmployeeWeight":5}}}}'
```

The reply is a new dataset whose `parentId` is the first. [Lineage instead of mutation](../concepts/lineage.md)
explains the three ways to derive one, and [Submit and poll](../build/submit-and-poll.md) every field
of the request.
