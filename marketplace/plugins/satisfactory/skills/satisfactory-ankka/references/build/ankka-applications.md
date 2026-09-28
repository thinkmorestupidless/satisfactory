# From an ankka application

> Give an ankka agent tools generated from a model's catalog, let a workflow own the wait, receive satisfactory's webhook on an endpoint you write, and test all of it against a fake with nothing running.

Source: https://satisfactory.ankka.cloud/build/ankka-applications/
`ankka-satisfactory` is the library an [ankka](https://docs.ankka.cloud/) application adds to use
satisfactory. It provides three things and mounts nothing: agent tools generated from a model's catalog,
a verifier for the webhook satisfactory sends, and a fake that answers the client and fires webhooks in
a test. The endpoint that receives the webhook is yours to write, with its own path and its own access
control list, because a library that mounted a route with an ACL nobody declared would break ankka's
rule that every endpoint states who may call it.

A solve takes minutes and an ankka tool call is synchronous, so the shape is: **the agent decides and
submits, a workflow owns the wait, and the webhook resumes the workflow.** The workflow's pause is
journaled, so a crash mid-solve resumes at the wait rather than submitting twice.

## Add the dependency

```scala
libraryDependencies += "com.thinkmorestupidless" %% "ankka-satisfactory" % "<version>"
```

It depends on `satisfactory-client` and on ankka's `ankka-sdk`, `ankka-http` and `ankka-agent`
libraries, and declares the ankka version it was built against.

## Tools from the catalog

`SatisfactoryTools.forModel(client, key, version, tags)` fetches the model's descriptor once and returns
three `FunctionTool` values for an agent:

| Tool | Parameters | Does |
|---|---|---|
| `solve_<model>` | `dataset`, `name`, `spentLimit` | submits the dataset and returns a `SolveToolResult` with the `datasetId` |
| `get_best_solution_<model>` | `datasetId` | returns the dataset's best solution so far, as JSON |
| `terminate_<model>` | `datasetId` | stops solving and returns the dataset's metadata |

`<model>` is the registration key with hyphens replaced by underscores, so employee scheduling's tools
are `solve_employee_scheduling`, `get_best_solution_employee_scheduling` and
`terminate_employee_scheduling`. The `dataset` parameter's schema is the model's own input schema, with
its references inlined, and the arguments are checked against that schema before anything is sent: an
argument that does not match is refused in the tool's reply, naming up to ten problems, and no request
leaves the process. The agent therefore sees the same validation a submit would apply.

The `tags` are attached to every submit the tools make. `SatisfactoryTools.workflowTag(id)` and
`sessionTag(id)` produce `ankka-workflow:<id>` and `ankka-session:<id>`, and satisfactory carries a
dataset's tags in every webhook event, so an event finds its way back to the workflow that caused it with
no mapping to maintain. This agent gives the tools to the model with the workflow's tag:

```scala
/** An agent that decides what to solve, and submits it with the generated tool. */
final class SchedulerAgent extends Agent:
  def plan(request: PlanRequest): Effect[String] =
    effects
      .systemMessage("You schedule staff. Submit the roster with the solve tool.")
      .userMessage(request.brief)
      .tools(SatisfactoryTools.forModel(Satisfactory.client, "employee-scheduling", "v1", List(SatisfactoryTools.workflowTag(request.workflowId)))*)
      .memory(MemoryProvider.none)
      .thenReply()

object SchedulerAgent extends Agent.Companion[SchedulerAgent](ComponentId("scheduler-agent")):
  given Serializer[PlanRequest] = Codecs.serializer[PlanRequest]("plan-request")
  def create(context: AgentContext) = new SchedulerAgent
  val plan                          = command("plan")(_.plan)
```

The tool's description tells the model that the solution arrives later and not to wait for it. The
`solve` tool answers a submit refusal, such as a dataset the model rejects, with a `SolveToolResult`
whose `error` and `details` carry the API's message, so the agent can correct the input.

## A workflow owns the wait

The workflow consults the agent in one step, then pauses. `thenPause(after, onTimeout)` is ankka's own
workflow effect: the workflow sleeps until a command transitions it, or until the timeout step runs. The
command the webhook receiver calls is `solutionReady`; it transitions to a step that fetches the solution
with the client. A second delivery of the same event finds the workflow past `waiting` and changes
nothing, which is how at-least-once delivery is handled:

```scala
/** The wait belongs to a workflow, not the agent (DESIGN.md §11.2b): it pauses until the webhook. */
final class RosterWorkflow(context: WorkflowContext) extends Workflow[Roster]:
  def emptyState: Roster = Roster("new", None, None, 0)

  def start(brief: String): Effect[Done] =
    effects.updateState(Roster("consulting", None, None, 0)).transitionTo(RosterWorkflow.consult.withInput(brief)).thenReply(Done)

  def consultStep(brief: String): StepEffect =
    val _ = context.componentClient.forAgent(SessionId(context.workflowId)).call(SchedulerAgent.plan).invoke(PlanRequest(context.workflowId, brief))
    stepEffects.updateState(currentState.copy(status = "waiting")).thenPause(5.minutes, RosterWorkflow.timedOut.ref)

  /** The webhook's receiver calls this. A duplicate delivery finds the work done and changes nothing. */
  def solutionReady(datasetId: String): Effect[Done] =
    if currentState.status != "waiting" then effects.updateState(currentState.copy(resumed = currentState.resumed + 1)).thenReply(Done)
    else effects.updateState(currentState.copy(status = "fetching", datasetId = Some(datasetId), resumed = currentState.resumed + 1)).transitionTo(RosterWorkflow.fetch.ref).thenReply(Done)

  def fetchStep: StepEffect =
    val result = Satisfactory.client.model("employee-scheduling", "v1").get(currentState.datasetId.get)
    stepEffects.updateState(currentState.copy(status = "done", score = result.metadata.score)).thenEnd

  def timedOutStep: StepEffect = stepEffects.updateState(currentState.copy(status = "timed-out")).thenEnd

  def get: ReadOnlyEffect[Roster] = effects.reply(currentState)

object RosterWorkflow extends Workflow.Companion[RosterWorkflow, Roster](ComponentId("roster"), Codecs.serializer[Roster]("roster")):
  def create(ctx: WorkflowContext) = new RosterWorkflow(ctx)
  val consult       = step[String]("consult")(_.consultStep)
  val fetch         = step("fetch")(_.fetchStep)
  val timedOut      = step("timed-out")(_.timedOutStep)
  val start         = command("start")(_.start)
  val solutionReady = command("solution-ready")(_.solutionReady)
  val get           = query("get")(_.get)
```

The timeout step is what handles a webhook that never arrives. Five minutes is this sample's choice; a
production workflow would transition to a step that reads the dataset's metadata once and decides.

## The endpoint you write

The webhook is a `POST` with a JSON body and four headers: `X-Satisfactory-Signature`,
`X-Satisfactory-Timestamp`, `X-Satisfactory-Event` and `X-Satisfactory-Delivery`. The signature is an
HMAC-SHA256 over `<timestamp>.<body>`, Base64 encoded, keyed with the subscription's secret.
[Receive webhooks](webhooks.md) has the wire detail.

`SatisfactoryWebhook` gives you two checks. `looksSigned()` is a predicate for `Acl.AllowIf`: it refuses
a request with no signature header or a timestamp older than five minutes before any code of yours runs,
and it does not check the HMAC, because an ACL cannot see the body. `verify(secret, request, body)` checks
the HMAC over the body's exact bytes, refuses a stale timestamp or a mismatch, and decodes the envelope
into an `Event.Dataset` or an `Event.WebhookFailing`. `workflowOf(event)` reads the workflow id back out
of the tags:

```scala
/** The developer's own endpoint: its path, its ACL. The library only checks and decodes. */
final class HooksEndpoint(client: ComponentClient) extends HttpEndpoint("/hooks"):
  val acl: Acl = Acl.AllowIf(SatisfactoryWebhook.looksSigned())

  postBody("/satisfactory") { (body: Array[Byte]) =>
    val answered: Unit =
    SatisfactoryWebhook.verify(Satisfactory.secret, request, body) match
      case Right(SatisfactoryWebhook.Event.Dataset(event)) =>
        SatisfactoryWebhook.workflowOf(event).foreach(id =>
          client.forWorkflow(EntityId(id)).call(RosterWorkflow.solutionReady).invoke(event.data.id): Unit
        )
      case Right(_)      => ()
      case Left(refusal) => throw HttpProblem.unauthorized(refusal.toString)
    answered
  }
```

Two things in this endpoint are deliberate. `postBody` takes the body as `Array[Byte]`, with the
`FromBody[Array[Byte]]` instance imported from `SatisfactoryWebhook.given`, so the verifier signs the
bytes satisfactory sent and not a re-encoding of them. And the endpoint answers before it does any work:
it hands the dataset id to the workflow and returns, which is what a webhook sender expects. The sender
waits five seconds for an answer and retries up to ten times, ten seconds apart.

A refusal from `verify` becomes a 401 through `HttpProblem.unauthorized`; a request the ACL refuses is a
403. Register the endpoint with `HttpServer.of` as you would any other, expose the service, and register
`https://<service>-<project>.<domain>/hooks/satisfactory` as a webhook subscription on the tenant. That
registration is an operator's act through the [Platform API](../reference/platform-api.md), not something
the application does at startup.

## Test it with the fake

`FakeSatisfactory` is an in-memory HTTP server that answers the client. It always answers the model
catalog, and everything else is scripted in order: `expectSubmit` answers the next submit, `expectGet`
the next fetch of a dataset, and `expect(method, pathEndsWith)` anything else. A call the script does not
cover is answered with a 500 whose message names the call, and `verifyScript()` throws if any script is
left over or any call was unscripted, so a test cannot pass by accident:

```scala
test("the catalog is always answered; scripted calls answer in order and are recorded") {
  val fake = FakeSatisfactory(descriptor)
  try
    fake.expectSubmit((_, request) => metadata("ds_fake_1")).expectGet("ds_fake_1", DatasetResponse(metadata("ds_fake_1"), Some(RawJson("{}")), None, None))
    val model = fake.client().model("employee-scheduling", "v1")
    assertEquals(model.descriptor.entity, "schedules")
    assertEquals(model.submit(SubmitRequest(RawJson("""{"employees":[],"shifts":[]}"""))).id, "ds_fake_1")
    assertEquals(model.get("ds_fake_1").metadata.id, "ds_fake_1")
    assertEquals(fake.remaining, 0)
    fake.verifyScript()
  finally fake.close()
}
```

`FakeSatisfactory.describe(model)` builds a descriptor from a `SolverModel`, which is how the tests above
describe employee scheduling. An application that has no model on its classpath constructs a
`ModelDescriptor` directly, with the input schema its agent's tool should validate against.

`fire(event, url, secret)` posts a signed `DatasetEvent` to an endpoint and returns the status, so the
whole loop runs under `AnkkaTestKit` with nothing of satisfactory running:

```scala
override def beforeAll(): Unit =
  Satisfactory.client = fake.client()
  val server = HttpServer.at("127.0.0.1", 0)(clients => HooksEndpoint(clients.componentClient))
  testKit = AnkkaTestKit.start(
    Seq(SchedulerAgent.descriptor, RosterWorkflow.descriptor) ++ AgentRuntime.descriptors,
    Seq(AgentRuntime.withDefaultModel(model), server)
  )
  hooks = s"http://127.0.0.1:${server.boundPort.get}/hooks/satisfactory"
```

The fake records every request it receives in `received`, so a test can assert that the submit carried
the workflow's tag. A tampered signature makes `fire` return 401, and an unsigned request is refused by
the ACL with 403.

## Mistakes to check for

- A tool call that waits for the solution. The solve tool returns a dataset id; the wait belongs to a
  workflow's pause, never to an LLM polling in a loop.
- A webhook endpoint with `Acl.AllowAll`. Use `Acl.AllowIf(SatisfactoryWebhook.looksSigned())`, then
  `verify` in the handler.
- Reading the body as a `String` or a case class before verifying. The signature covers the bytes on the
  wire.
- A `solutionReady` command that does work twice. A duplicate delivery must be a no-op.
- Registering the webhook from application code. It is a tenant setting made by an operator.
