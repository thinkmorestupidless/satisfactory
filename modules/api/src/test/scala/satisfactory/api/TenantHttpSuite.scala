package satisfactory.api

import satisfactory.protocol.SolvingStatus

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Story 5: administer a tenant — keys, members, limits, profiles — and SC-011's isolation matrix. */
class TenantHttpSuite extends ApiFixture:

  override protected def configOverrides: Map[String, Any] =
    TestTokens.config ++ Map("satisfactory.local-slots" -> 2)

  private val platform = "/api/platform/v1"

  private def as(token: String, method: String, path: String, body: String = null): Reply =
    send(method, platform + path, Option(body).map(_.getBytes("UTF-8")).orElse(Option.when(method != "GET" && method != "DELETE")(Array.emptyByteArray)), key = None, headers = Seq("Authorization" -> s"Bearer $token"))

  private lazy val tenant: String =
    val reply = as(TestTokens.operator, "POST", "/tenants", """{"name":"initech","firstAdminSubject":"alice"}""")
    assertEquals(reply.status, 201, reply.toString)
    reply.json.get("id").asText()

  private def newKey(role: String, token: String = TestTokens.alice): String =
    val reply = as(token, "POST", s"/tenants/$tenant/keys", s"""{"label":"ci","role":"$role"}""")
    assertEquals(reply.status, 201, reply.toString)
    reply.json.get("key").asText()

  test("only a platform operator creates tenants; the first admin is its member") {
    assertEquals(as(TestTokens.alice, "POST", "/tenants", """{"name":"x","firstAdminSubject":"alice"}""").status, 403)
    val t = as(TestTokens.alice, "GET", s"/tenants/$tenant").json
    assertEquals(t.get("name").asText(), "initech")
    assertEquals(t.get("memberCount").asInt(), 1)
    val me = eventually()(as(TestTokens.alice, "GET", "/whoami").json)(
      _.get("tenants").elements().asScala.exists(m => m.get("tenantId").asText() == tenant && m.get("role").asText() == "admin")
    )
    assert(!me.get("operator").asBoolean())
  }

  test("a key is shown once, works with exactly its role, and stops working when revoked") {
    val rw = newKey("read-write")
    val ro = newKey("read-only")
    val listed = as(TestTokens.alice, "GET", s"/tenants/$tenant/keys")
    assert(!listed.body.contains(rw) && !listed.body.contains(ro), "never the plaintext again")
    assertEquals(post(s"$es/schedules", submitBody(spentLimit = "PT1S"), key = Some(ro)).status, 403)
    val id = post(s"$es/schedules", submitBody(spentLimit = "PT1S"), key = Some(rw)).json.get("id").asText()
    assertEquals(get(s"$es/schedules/$id/metadata", key = Some(ro)).status, 200, "read-only reads")
    val keyId = listed.json.elements().asScala.find(_.get("role").asText() == "read-write").get.get("keyId").asText()
    assertEquals(as(TestTokens.alice, "DELETE", s"/tenants/$tenant/keys/$keyId").status, 204)
    val _ = eventually(10.seconds)(get(s"$es/schedules", key = Some(rw)).status)(_ == 401)
  }

  test("members read, admins change: a member cannot make keys, members or limits; outsiders see nothing") {
    assertEquals(as(TestTokens.alice, "POST", s"/tenants/$tenant/members", """{"subject":"bob","role":"member"}""").status, 201)
    assertEquals(as(TestTokens.bob, "GET", s"/tenants/$tenant").status, 200)
    assertEquals(as(TestTokens.bob, "GET", s"/tenants/$tenant/keys").status, 200)
    assertEquals(as(TestTokens.bob, "POST", s"/tenants/$tenant/keys", """{"label":"x","role":"read-write"}""").status, 403)
    assertEquals(as(TestTokens.bob, "POST", s"/tenants/$tenant/members", """{"subject":"eve","role":"admin"}""").status, 403)
    assertEquals(as(TestTokens.bob, "PUT", s"/tenants/$tenant/limits", """{"concurrency":1,"submitPerMinute":1,"lifetimeCeiling":"PT1H","retention":"P1D"}""").status, 403)
    val eve = TestTokens.token("eve")
    assertEquals(as(eve, "GET", s"/tenants/$tenant").status, 404)
    assertEquals(as(eve, "GET", s"/tenants/$tenant/members").status, 404)
    assertEquals(as(TestTokens.alice, "DELETE", s"/tenants/$tenant/members/alice").status, 409, "the last admin stays")
    assertEquals(as(TestTokens.alice, "PATCH", s"/tenants/$tenant/members/bob", """{"role":"admin"}""").json.get("role").asText(), "admin")
  }

  test("limits: admins may lower them, only an operator may raise them past the defaults") {
    val lower = as(TestTokens.alice, "PUT", s"/tenants/$tenant/limits", """{"concurrency":1,"submitPerMinute":30,"lifetimeCeiling":"PT6H","retention":"P7D"}""")
    assertEquals(lower.status, 200, lower.toString)
    assertEquals(as(TestTokens.alice, "PUT", s"/tenants/$tenant/limits", """{"concurrency":50,"submitPerMinute":30,"lifetimeCeiling":"PT6H","retention":"P7D"}""").status, 403)
    assertEquals(as(TestTokens.operator, "PUT", s"/tenants/$tenant/limits", """{"concurrency":1,"submitPerMinute":900,"lifetimeCeiling":"PT6H","retention":"P7D"}""").status, 200)
  }

  test("at its concurrency limit a tenant queues rather than fails, and starts the highest priority first (SC-010)") {
    val _   = as(TestTokens.operator, "PUT", s"/tenants/$tenant/limits", """{"concurrency":1,"submitPerMinute":900,"lifetimeCeiling":"PT6H","retention":"P7D"}""")
    val key = newKey("read-write")
    val first = post(s"$es/schedules", submitBody(spentLimit = "PT6S"), key = Some(key)).json.get("id").asText()
    val _     = awaitStatus(first, Set(SolvingStatus.Active), key = Some(key))
    val low   = post(s"$es/schedules?priority=1", submitBody(spentLimit = "PT1S"), key = Some(key)).json.get("id").asText()
    val high  = post(s"$es/schedules?priority=9", submitBody(spentLimit = "PT1S"), key = Some(key)).json.get("id").asText()
    Thread.sleep(2000)
    assertEquals(metadata(low, Some(key)).get("solverStatus").asText(), SolvingStatus.Scheduled, "a worker slot is free, the tenant's is not")
    val highDone = awaitFinal(high, key = Some(key))
    val lowMeta  = metadata(low, Some(key))
    val lowStart = Option(lowMeta.get("startDateTime")).filterNot(_.isNull).map(_.asText())
    assert(lowStart.forall(_ > highDone.get("startDateTime").asText()), s"low started $lowStart, high ${highDone.get("startDateTime")}")
    val _ = awaitFinal(low, key = Some(key))
  }

  test("over its rate a tenant is refused with 429 and nothing is created") {
    val _   = as(TestTokens.operator, "PUT", s"/tenants/$tenant/limits", """{"concurrency":1,"submitPerMinute":2,"lifetimeCeiling":"PT6H","retention":"P7D"}""")
    val key = newKey("read-write")
    Thread.sleep(100)
    val replies = (1 to 4).map(_ => post(s"$es/schedules?operation=NONE", submitBody(), key = Some(key)).status)
    assert(replies.contains(429), replies.toString)
    val _ = as(TestTokens.operator, "PUT", s"/tenants/$tenant/limits", """{"concurrency":2,"submitPerMinute":900,"lifetimeCeiling":"PT6H","retention":"P7D"}""")
  }

  test("profiles: by name or id, layered under the request, standard read-only, unknown refused") {
    val created = as(TestTokens.alice, "POST", s"/tenants/$tenant/configurations",
      """{"model":"employee-scheduling/v1","name":"fair","runConfiguration":{"maxThreadCount":4,"termination":{"spentLimit":"PT2S"}},"modelConfiguration":{"balanceEmployeeShiftAssignmentsWeight":8}}""")
    assertEquals(created.status, 201, created.toString + " body sent")
    val profileId = created.json.get("id").asText()
    val key       = newKey("read-write")
    val submitted = post(s"$es/schedules?configurationId=fair", s"""{"modelInput":$smallInput}""", key = Some(key))
    assertEquals(submitted.status, 202, submitted.toString)
    val byName    = submitted.json.get("id").asText()
    val config    = get(s"$es/schedules/$byName/config", key = Some(key)).json
    assertEquals(config.at("/model/overrides/balanceEmployeeShiftAssignmentsWeight").asInt(), 8)
    assertEquals(config.at("/run/termination/spentLimit").asText(), "PT2S")
    assertEquals(config.at("/run/maxThreadCount").asInt(), 1)
    val byId = post(s"$es/schedules?configurationId=$profileId", submitBody(spentLimit = "PT1S"), key = Some(key)).json.get("id").asText()
    assertEquals(get(s"$es/schedules/$byId/config", key = Some(key)).json.at("/run/termination/spentLimit").asText(), "PT1S", "the request's own config wins")
    assertEquals(post(s"$es/schedules?configurationId=nope", submitBody(), key = Some(key)).status, 400)
    assertEquals(as(TestTokens.alice, "PUT", s"/tenants/$tenant/configurations/standard", """{"model":"employee-scheduling/v1","name":"x"}""").status, 405)
    val listed = as(TestTokens.alice, "GET", s"/tenants/$tenant/configurations?model=employee-scheduling/v1").json
    assertEquals(listed.elements().asScala.map(_.get("name").asText()).toList, List("standard", "fair"))
    val bad = as(TestTokens.alice, "POST", s"/tenants/$tenant/configurations", """{"model":"employee-scheduling/v1","name":"bad","modelConfiguration":{"nopeWeight":1}}""")
    assertEquals(bad.status, 400)
    val _ = awaitFinal(byName, key = Some(key))
    val _ = awaitFinal(byId, key = Some(key))
  }

  test("SC-011: nothing of one tenant is readable or actionable by another") {
    val mine  = submit(spentLimit = "PT1S").get("id").asText()
    val _     = awaitFinal(mine)
    val modelRoutes = List(
      "GET" -> s"$es/schedules/$mine", "GET" -> s"$es/schedules/$mine/metadata", "GET" -> s"$es/schedules/$mine/input",
      "GET" -> s"$es/schedules/$mine/model-request", "GET" -> s"$es/schedules/$mine/config", "GET" -> s"$es/schedules/$mine/validation-result",
      "GET" -> s"$es/schedules/$mine/logs", "GET" -> s"$es/schedules/$mine/events", "DELETE" -> s"$es/schedules/$mine",
      "POST" -> s"$es/schedules/$mine", "POST" -> s"$es/schedules/$mine/from-input", "POST" -> s"$es/schedules/$mine/from-patch"
    )
    modelRoutes.foreach { (method, path) =>
      val reply = send(method, path, Option.when(method == "POST")("""{"patch":[]}""".getBytes), key = Some(keyB))
      assertEquals(reply.status, 404, s"$method $path as tenant B: $reply")
    }
    assert(!get(s"$es/schedules?size=200", key = Some(keyB)).body.contains(mine))
    val outsider = TestTokens.token("mallory")
    List("GET" -> s"/tenants/$tenant", "GET" -> s"/tenants/$tenant/keys", "GET" -> s"/tenants/$tenant/members",
      "GET" -> s"/tenants/$tenant/limits", "GET" -> s"/tenants/$tenant/configurations", "POST" -> s"/tenants/$tenant/keys",
      "DELETE" -> s"/tenants/$tenant/members/alice", "PUT" -> s"/tenants/$tenant/limits").foreach { (method, path) =>
      assertEquals(as(outsider, method, path, "{}").status, 404, s"$method $path as an outsider")
    }
    assert(!as(outsider, "GET", "/tenants").body.contains(tenant))
  }
