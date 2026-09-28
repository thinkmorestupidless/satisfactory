package satisfactory.api

import satisfactory.protocol.{EventTypes, Signing, SolvingStatus}
import satisfactory.spi.Json

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Story 6: receive results by webhook (quickstart tier 3), with retries shortened to seconds. */
class WebhookHttpSuite extends ApiFixture:

  override protected def configOverrides: Map[String, Any] = TestTokens.config ++ Map(
    "satisfactory.webhooks.retry-interval" -> "1s",
    "satisfactory.webhooks.max-attempts"   -> 3,
    "satisfactory.webhooks.timeout"        -> "2s"
  )

  private val receiver = Receiver()
  override def afterAll(): Unit =
    receiver.stop()
    super.afterAll()

  private val platform = "/api/platform/v1"

  private def as(method: String, path: String, body: String = null, token: String = TestTokens.alice): Reply =
    send(method, platform + path, Option(body).map(_.getBytes("UTF-8")).orElse(Option.when(method == "POST")(Array.emptyByteArray)), key = None, headers = Seq("Authorization" -> s"Bearer $token"))

  private lazy val tenant: String =
    as("POST", "/tenants", """{"name":"hooks","firstAdminSubject":"alice","limits":{"concurrency":4,"submitPerMinute":600,"lifetimeCeiling":"PT1H","retention":"P1D"}}""", TestTokens.operator).json.get("id").asText()

  private lazy val key: String =
    as("POST", s"/tenants/$tenant/keys", """{"label":"hooks","role":"read-write"}""").json.get("key").asText()

  private def subscribe(name: String, events: String = """["dataset.*"]""", filters: String = "{}", signing: String = """{"over":"body"}"""): (String, String) =
    val reply = as("POST", s"/tenants/$tenant/webhooks", s"""{"url":"${receiver.url(name)}","events":$events,"filters":$filters,"signing":$signing,"headers":{"X-Custom":"sig={hmac_signature}"}}""")
    assertEquals(reply.status, 201, reply.toString)
    (reply.json.get("id").asText(), reply.json.get("secret").asText())

  private def solveOne(query: String = "", spentLimit: String = "PT1S", input: String = smallInput): String =
    val id = post(s"$es/schedules$query", submitBody(input, spentLimit), key = Some(key)).json.get("id").asText()
    val _  = awaitFinal(id, key = Some(key))
    id

  test("a final dataset is announced once, signed, with metadata and links and never the solution (SC-009)") {
    val (subId, secret) = subscribe("basic")
    assert(!as("GET", s"/tenants/$tenant/webhooks/$subId").body.contains(secret), "the secret is shown once")
    val started = System.nanoTime()
    val id      = solveOne("?name=week-40&tags=site:leeds")
    val got     = eventually(30.seconds)(receiver.to("basic"))(_.nonEmpty)
    assert((System.nanoTime() - started).nanos < 30.seconds)
    Thread.sleep(1500)
    assertEquals(receiver.to("basic").size, 1)
    val r     = got.head
    val event = Json.parse(r.body)
    assertEquals(event.get("type").asText(), EventTypes.Completed)
    assertEquals(event.get("tenant").asText(), tenant)
    assertEquals(event.at("/data/id").asText(), id)
    assertEquals(event.at("/data/name").asText(), "week-40")
    assertEquals(event.at("/data/status").asText(), SolvingStatus.Completed)
    assert(event.at("/data/seq").asLong() > 0)
    assert(event.at("/data/runLink").asText().endsWith(s"/schedules/$id"))
    assert(!r.text.contains("\"shifts\""), "never a solution")
    assertEquals(r.headers("x-satisfactory-event"), EventTypes.Completed)
    val verified = Signing.verify(secret, r.headers.get("x-satisfactory-signature"), r.headers.get("x-satisfactory-timestamp"), r.body)
    assertEquals(verified, Right(()))
    assertEquals(r.headers("x-custom"), s"sig=${r.headers("x-satisfactory-signature")}")
    val _ = as("DELETE", s"/tenants/$tenant/webhooks/$subId")
  }

  test("filters: status, tags and name pattern decide what is sent") {
    val (a, _) = subscribe("only-failed", filters = """{"status":["SOLVING_FAILED"]}""")
    val (b, _) = subscribe("tagged", filters = """{"tags":["urgent"],"nameRegex":"^night-.*"}""")
    val _      = solveOne("?name=night-1&tags=urgent")
    val _      = solveOne("?name=day-1&tags=urgent")
    val got    = eventually(20.seconds)(receiver.to("tagged"))(_.nonEmpty)
    Thread.sleep(1500)
    assertEquals(receiver.to("tagged").size, 1)
    assertEquals(Json.parse(got.head.body).at("/data/name").asText(), "night-1")
    assertEquals(receiver.to("only-failed"), Nil)
    List(a, b).foreach(s => as("DELETE", s"/tenants/$tenant/webhooks/$s"))
  }

  test("a receiver that is down is retried until it answers, a read timeout included; the log shows every attempt") {
    receiver.script("flaky", receiver.Status(503), receiver.Hang)
    val (subId, _) = subscribe("flaky")
    val id         = solveOne()
    val got        = eventually(40.seconds)(receiver.to("flaky"))(_.size >= 3)
    val ids        = got.map(r => Json.parse(r.body).get("id").asText()).distinct
    assertEquals(ids.size, 1, "every attempt is the same event: a receiver dedupes on its id")
    val log = eventually(20.seconds)(as("GET", s"/tenants/$tenant/webhooks/deliveries?subscriptionId=$subId").json)(
      _.get("content").elements().asScala.exists(_.get("outcome").asText() == "delivered")
    )
    val entry = log.get("content").get(0)
    assertEquals(entry.get("datasetId").asText(), id)
    assertEquals(entry.get("attempts").size(), 3)
    assertEquals(entry.at("/attempts/0/status").asInt(), 503)
    assert(entry.at("/attempts/1/error").asText().contains("Timeout"), entry.toString)
    val _ = as("DELETE", s"/tenants/$tenant/webhooks/$subId")
  }

  test("exhausted deliveries raise webhook.failing on the tenant's other subscriptions, at most once per two hours; a retry resends the same event") {
    receiver.always = Map("dead" -> 500)
    val (dead, _)    = subscribe("dead", filters = """{"tags":["exhaust"]}""")
    val (watcher, _) = subscribe("watcher", events = """["webhook.failing"]""")
    val _ = solveOne("?tags=exhaust")
    val failing = eventually(40.seconds)(receiver.to("watcher"))(_.nonEmpty)
    val event   = Json.parse(failing.head.body)
    assertEquals(event.get("type").asText(), EventTypes.Failing)
    assertEquals(event.at("/data/subscriptionId").asText(), dead)
    assertEquals(event.at("/data/attempts").asInt(), 3)
    assertEquals(receiver.to("dead").size, 3)
    val _ = solveOne("?tags=exhaust")
    Thread.sleep(8000)
    assertEquals(receiver.to("watcher").size, 1, "once per two hours per failing subscription")
    val exhausted = as("GET", s"/tenants/$tenant/webhooks/deliveries?subscriptionId=$dead&outcome=exhausted").json
    val deliveryId = exhausted.at("/content/0/deliveryId").asText()
    receiver.always = Map.empty
    val before = receiver.to("dead").size
    assertEquals(as("POST", s"/tenants/$tenant/webhooks/deliveries/$deliveryId/retry").status, 204)
    val resent = eventually(20.seconds)(receiver.to("dead"))(_.size > before)
    assertEquals(Json.parse(resent.last.body).get("id").asText(), exhausted.at("/content/0/eventId").asText(), "the same event, resent")
    List(dead, watcher).foreach(s => as("DELETE", s"/tenants/$tenant/webhooks/$s"))
  }

  test("operation=NONE announces dataset.computed; an invalid dataset announces dataset.invalid; path signing signs the path") {
    val (subId, secret) = subscribe("kinds", signing = """{"over":"path"}""")
    val _ = solveOne("?operation=NONE")
    val _ = solveOne(input = """{"employees":[],"shifts":[]}""")
    val got   = eventually(30.seconds)(receiver.to("kinds"))(_.size >= 2)
    val types = got.map(r => Json.parse(r.body).get("type").asText()).toSet
    assertEquals(types, Set(EventTypes.Computed, EventTypes.Invalid))
    val r = got.head
    assertEquals(Signing.verify(secret, r.headers.get("x-satisfactory-signature"), r.headers.get("x-satisfactory-timestamp"), "/hook/kinds".getBytes), Right(()))
    val _ = as("DELETE", s"/tenants/$tenant/webhooks/$subId")
  }

  test("subscriptions are admin-managed; a bad URL or event type is refused") {
    assertEquals(as("POST", s"/tenants/$tenant/webhooks", """{"url":"ftp://x","events":["dataset.*"]}""").status, 400)
    assertEquals(as("POST", s"/tenants/$tenant/webhooks", s"""{"url":"${receiver.url("x")}","events":["tenant.*"]}""").status, 400)
    assertEquals(as("POST", s"/tenants/$tenant/webhooks", s"""{"url":"${receiver.url("x")}","events":["dataset.*"]}""", TestTokens.bob).status, 404)
  }
