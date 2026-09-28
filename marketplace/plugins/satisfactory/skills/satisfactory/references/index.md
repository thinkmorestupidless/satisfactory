# satisfactory

> What satisfactory is, how a planning problem becomes a solution, the ways to receive results, and where in this documentation to start.

Source: https://satisfactory.ankka.cloud/
satisfactory is constraint solving as a service. You submit a planning problem, a roster of shifts to
staff or a day of deliveries to route, and get back a solution, or follow the stream of improving ones
while it solves. The solving is done by [Timefold Solver](https://github.com/TimefoldAI/timefold-solver),
and the service runs as two [ankka](https://docs.ankka.cloud/) services on any ankka installation.

The API is a reimplementation of the Timefold Platform's model API: the same vocabulary, paths, statuses
and delivery rules, diverging only with a reason, and every divergence is
[written down](reference/divergences.md). A client written against Timefold's platform will find the
same shape here.

## The shape, in five lines

- A **model** is a planning problem type, compiled Java behind a small interface. A request names a
  model and carries a **dataset**: the problem's input and how long to solve it.
- Datasets are **immutable, with lineage**. Changing the problem creates a new dataset derived from the
  old one; the old one keeps its solution.
- Every dataset is an **event sourced entity** that is the source of truth for its status and best
  solution. **Workers** pull datasets from a queue, solve them, and report each improvement with a
  fenced lease, so a worker that dies mid-solve costs seconds, not the solve.
- Results arrive by **webhook** in production, by **server-sent events** during development, or by
  polling.
- An ankka application uses it through **ankka-satisfactory**: agent tools generated from the model's
  catalog, a verifier for the webhook, and a fake for tests.

## A dataset's life

```text
POST /api/models/employee-scheduling/v1/schedules      202 { "id": "…", "solverStatus": "DATASET_CREATED" }
                                                        SOLVING_SCHEDULED     queued for a worker
                                                        DATASET_VALIDATED     the model checked the input
                                                        DATASET_COMPUTED      the input's own score and metrics
                                                        SOLVING_STARTED
                                                        SOLVING_ACTIVE        improving solutions arrive
GET  …/schedules/{id}/events                            one frame per improvement, score and status
GET  …/schedules/{id}                                   the best solution so far, with KPIs
                                                        SOLVING_COMPLETED     the webhook fires
```

## Where to start

- **Trying it.** [Run it on your machine](get-started/run-locally.md) starts the whole system in one
  process; [Your first solve](get-started/first-solve.md) submits a demo dataset with curl and watches
  it improve.
- **Understanding it.** [How satisfactory works](concepts/architecture.md) is the overview.
  [Models and datasets](concepts/models-and-datasets.md), [Lineage instead of mutation](concepts/lineage.md)
  and [Receiving results](concepts/receiving-results.md) are the three ideas everything else follows.
- **Calling it.** [Submit and poll](build/submit-and-poll.md) is the request in full,
  [Stream progress](build/streaming.md) and [Receive webhooks](build/webhooks.md) the two other ways to
  get results, and [Use the client](build/client.md) does all of it from Scala.
- **From an ankka application.** [From an ankka application](build/ankka-applications.md): an agent
  submits with generated tools, a workflow owns the wait, the webhook resumes it.
- **Adding a model.** [Write a model](build/models.md).
- **Running it.** [Deploy on ankka](deploy/deploy-on-ankka.md) and [Operate the service](deploy/operate.md).
- **Looking something up.** The [Model API](reference/model-api.md), the [Platform API](reference/platform-api.md),
  the two models ([employee scheduling](reference/employee-scheduling.md), [vehicle routing](reference/vehicle-routing.md)),
  and the [glossary](reference/glossary.md).
- **Checking what is missing.** [Limitations](reference/limitations.md).

## For models and agents

This documentation is published in forms a model can read directly. `llms.txt` at the site root lists
every page with a one-sentence description, `llms-full.txt` holds every page in one file, and each page
is also served as Markdown at its own path with a `.md` suffix. The same pages ship as agent skills in
the `satisfactory` plugin of the ankka marketplace.
[Work with a coding agent](get-started/coding-agents.md) sets that up.
