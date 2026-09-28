package satisfactory.ankka

import satisfactory.client.SatisfactoryError
import satisfactory.models.employeescheduling.EmployeeScheduling
import satisfactory.protocol.*

import java.time.Instant

class FakeSatisfactorySuite extends munit.FunSuite:

  private val descriptor = FakeSatisfactory.describe(EmployeeScheduling.V1)

  private def metadata(id: String, tags: List[String] = Nil, status: String = SolvingStatus.Scheduled) =
    Metadata(id, None, None, None, tags, status, None, Some(Instant.now()), None, None, None, None, None, None, None, None, 1)

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

  test("a script that runs out fails loudly, naming the call") {
    val fake = FakeSatisfactory(descriptor)
    try
      val model  = fake.client().model("employee-scheduling", "v1")
      val refused = intercept[SatisfactoryError](model.metadata("ds_x"))
      assert(refused.info.message.contains("no scripted response for GET"), refused.getMessage)
      val failure = intercept[AssertionError](fake.verifyScript())
      assert(failure.getMessage.contains("ds_x"))
    finally fake.close()
  }

  test("the tools' parameter schema is the model's input schema, references inlined, and arguments are checked against it") {
    val fake = FakeSatisfactory(descriptor)
    try
      val tools = SatisfactoryTools.forModel(fake.client(), "employee-scheduling", "v1", List(SatisfactoryTools.workflowTag("wf-1")))
      assertEquals(tools.map(_.name), List("solve_employee_scheduling", "get_best_solution_employee_scheduling", "terminate_employee_scheduling"))
      val schema = tools.head.spec.inputSchema.render
      assert(schema.contains("\"requiredSkill\""), schema)
      assert(!schema.contains("$ref") && !schema.contains("$defs"), schema)
      val bad = tools.head.invoke(com.thinkmorestupidless.ankka.agent.Json.obj("dataset" -> com.thinkmorestupidless.ankka.agent.Json.obj("shifts" -> com.thinkmorestupidless.ankka.agent.Json.str("no"))))
      assert(bad.left.exists(_.contains("input schema")), bad.toString)
      assertEquals(fake.received.size, 1, "only the catalog was asked: nothing was submitted")
    finally fake.close()
  }
