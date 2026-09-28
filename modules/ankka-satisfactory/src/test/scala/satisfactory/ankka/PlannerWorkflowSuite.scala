package satisfactory.ankka

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Done, EntityId, Serializer, SessionId}
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import satisfactory.ankka.SatisfactoryWebhook.given
import satisfactory.client.SatisfactoryClient
import satisfactory.models.employeescheduling.EmployeeScheduling
import satisfactory.protocol.*

import java.time.Instant
import scala.concurrent.duration.*

// ── A small ankka application that uses satisfactory: an agent, a workflow, a webhook endpoint ──

/** Where the test's fake lives; an agent is built by the runtime and cannot be handed one. */
object Satisfactory:
  @volatile var client: SatisfactoryClient = null
  val secret = "whsec_test"

final case class PlanRequest(workflowId: String, brief: String)

// docs:start agent
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
// docs:end agent

final case class Roster(status: String, datasetId: Option[String], score: Option[String], resumed: Int)

// docs:start workflow
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
// docs:end workflow

// docs:start endpoint
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
// docs:end endpoint

/**
 * Story 9 end to end (quickstart tier 6): an agent submits through the generated tool, the workflow
 * pauses, satisfactory's (fake) webhook resumes it with the result. Nothing of satisfactory runs.
 */
class PlannerWorkflowSuite extends munit.FunSuite:
  override val munitTimeout = 5.minutes

  private val model = TestModelProvider()
  private val fake  = FakeSatisfactory(FakeSatisfactory.describe(EmployeeScheduling.V1))
  private var testKit: AnkkaTestKit = null
  private var hooks: String         = ""

  // docs:start wiring
  override def beforeAll(): Unit =
    Satisfactory.client = fake.client()
    val server = HttpServer.at("127.0.0.1", 0)(clients => HooksEndpoint(clients.componentClient))
    testKit = AnkkaTestKit.start(
      Seq(SchedulerAgent.descriptor, RosterWorkflow.descriptor) ++ AgentRuntime.descriptors,
      Seq(AgentRuntime.withDefaultModel(model), server)
    )
    hooks = s"http://127.0.0.1:${server.boundPort.get}/hooks/satisfactory"
  // docs:end wiring

  override def afterAll(): Unit =
    if testKit != null then testKit.stop()
    fake.close()

  private def roster(id: String) = testKit.componentClient.forWorkflow(EntityId(id))

  private def eventually[A](within: FiniteDuration = 60.seconds)(probe: => A)(ok: A => Boolean): A =
    val deadline = System.nanoTime() + within.toNanos
    var v        = probe
    while !ok(v) && System.nanoTime() < deadline do
      Thread.sleep(100)
      v = probe
    assert(ok(v), s"not within $within: $v")
    v

  private val input = EmployeeScheduling.V1.demoData().get(0).input().get()

  private def event(datasetId: String, tags: List[String]) = DatasetEvent(
    s"evt_${datasetId}_9", EventTypes.Completed, Instant.now(), "t_1",
    DatasetEventData(datasetId, None, "employee-scheduling", "v1", tags, SolvingStatus.Completed, Some("0hard/-5soft"), 9, None, None, None, "http://x", "http://x")
  )

  test("the agent submits with the workflow's tag, the workflow waits, the webhook resumes it with the result (SC-015)") {
    model
      .expectToolCall("solve_employee_scheduling", Json.obj("dataset" -> Json.parse(satisfactory.spi.Json.string(input)).toOption.get, "spentLimit" -> Json.str("PT5M")))
      .expectText("Submitted the roster."): Unit
    fake.expectSubmit((_, request) =>
      Metadata("ds_fake_1", None, None, None, List("ankka-workflow:wf-1"), SolvingStatus.Scheduled, None, Some(Instant.now()), None, None, None, None, None, None, None, None, 1)
    ): Unit
    fake.expectGet("ds_fake_1", DatasetResponse(
      Metadata("ds_fake_1", None, None, None, Nil, SolvingStatus.Completed, Some("0hard/-5soft"), None, None, None, None, None, None, None, None, None, 9),
      Some(RawJson("{}")), None, None
    )): Unit

    assertEquals(roster("wf-1").call(RosterWorkflow.start).invoke("Staff the ward for two weeks"), Done)
    val _ = eventually()(roster("wf-1").call(RosterWorkflow.get).invoke())(_.status == "waiting")
    val submitted = fake.received.toArray.toList.map(_.asInstanceOf[FakeRequest]).find(_.method == "POST").get
    assert(submitted.query.contains("tags=ankka-workflow%3Awf-1"), submitted.query)

    assertEquals(fake.fire(event("ds_fake_1", List("ankka-workflow:wf-1")), hooks, Satisfactory.secret), 204)
    val done = eventually()(roster("wf-1").call(RosterWorkflow.get).invoke())(_.status == "done")
    assertEquals(done.score, Some("0hard/-5soft"))

    assertEquals(fake.fire(event("ds_fake_1", List("ankka-workflow:wf-1")), hooks, Satisfactory.secret), 204, "a duplicate is accepted …")
    val after = roster("wf-1").call(RosterWorkflow.get).invoke()
    assertEquals(after.status, "done", "… and changes nothing")
    fake.verifyScript()
  }

  test("a webhook with a wrong signature, or none, is refused before the body is looked at") {
    assertEquals(fake.fire(event("ds_x", List("ankka-workflow:wf-x")), hooks, "the-wrong-secret"), 401)
    val unsigned = java.net.http.HttpClient.newHttpClient().send(
      java.net.http.HttpRequest.newBuilder(java.net.URI.create(hooks)).POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build(),
      java.net.http.HttpResponse.BodyHandlers.discarding()
    )
    assertEquals(unsigned.statusCode, 403)
  }
