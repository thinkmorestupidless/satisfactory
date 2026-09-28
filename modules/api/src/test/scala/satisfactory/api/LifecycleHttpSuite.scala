package satisfactory.api

import satisfactory.api.application.Limits
import satisfactory.protocol.SolvingStatus
import satisfactory.spi.Json

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Story 8: manage datasets over their lifetime, and SC-018: dataset content never leaks. */
class LifecycleHttpSuite extends ApiFixture:

  /** Eight seconds of retention, so expiry happens inside a test. */
  override protected def tenantALimits: Option[Limits] = Some(Limits(4, 600, 43200, 8))

  override protected def configOverrides: Map[String, Any] =
    TestTokens.config ++ Map("satisfactory.metrics-token" -> "scrape-me")

  test("rename and re-tag after submission, within the limits") {
    val id = submit(spentLimit = "PT1S").get("id").asText()
    val renamed = send("PATCH", s"$es/schedules/$id/metadata", Some("""{"name":"week-41","tags":["a","b"]}""".getBytes))
    assertEquals(renamed.status, 200, renamed.toString)
    assertEquals(renamed.json.get("name").asText(), "week-41")
    assertEquals(metadata(id).get("tags").elements().asScala.map(_.asText()).toList, List("a", "b"))
    assertEquals(send("PATCH", s"$es/schedules/$id/metadata", Some(s"""{"name":"${"x" * 256}"}""".getBytes)).status, 400)
    assertEquals(send("PATCH", s"$es/schedules/$id/metadata", Some("""{"tags":["a","a"]}""".getBytes)).status, 400)
    val tooMany = (1 to 101).map(i => s""""t$i"""").mkString("[", ",", "]")
    assertEquals(send("PATCH", s"$es/schedules/$id/metadata", Some(s"""{"tags":$tooMany}""".getBytes)).status, 400)
    val _ = awaitFinal(id)
  }

  test("purge is refused while solving; purged bodies are gone, metadata stays; restore brings them back") {
    val id = submit(spentLimit = "PT3S").get("id").asText()
    val _  = awaitStatus(id, Set(SolvingStatus.Active))
    assertEquals(send("DELETE", s"$es/schedules/$id/purge").status, 400)
    val _ = awaitFinal(id)
    assertEquals(send("DELETE", s"$es/schedules/$id/purge").status, 204)
    assertEquals(get(s"$es/schedules/$id").status, 410)
    assertEquals(get(s"$es/schedules/$id/input").status, 410)
    assertEquals(get(s"$es/schedules/$id/metadata").status, 200)
    assertEquals(send("PATCH", s"$es/schedules/$id/metadata", Some("""{"name":"x"}""".getBytes)).status, 400)
    assertEquals(send("PUT", s"$es/schedules/$id").status, 204)
    assertEquals(get(s"$es/schedules/$id").status, 200)
  }

  test("when retention runs out the bodies are deleted for good and restore is refused (SC-016)") {
    val id = submit(spentLimit = "PT1S").get("id").asText()
    val _  = awaitFinal(id)
    assert(wiring.blobs.store.get(s"$id/input/0").isDefined)
    assertEquals(send("DELETE", s"$es/schedules/$id/purge").status, 204)
    val _ = eventually(40.seconds)(send("PUT", s"$es/schedules/$id").status)(_ == 404)
    assertEquals(wiring.blobs.store.get(s"$id/input/0"), None, "the input is physically deleted")
    val _ = eventually()(get(s"$es/schedules?size=200").body)(!_.contains(id))
    assert(get(s"$es/schedules?size=200&includeExpired=true").body.contains(id), "their metadata remains")
  }

  test("the listing filters by status and tag, and pages") {
    val ids = (1 to 3).map(_ => submit(spentLimit = "PT1S", query = "?tags=paging").get("id").asText())
    ids.foreach(awaitFinal(_))
    val page0 = eventually()(get(s"$es/schedules?tag=paging&size=2&page=0").json)(_.get("content").size() == 2)
    assert(page0.get("hasNext").asBoolean())
    val page1 = get(s"$es/schedules?tag=paging&size=2&page=1").json
    assertEquals(page1.get("content").size(), 1)
    val seen = (page0.get("content").elements().asScala ++ page1.get("content").elements().asScala).map(_.get("id").asText()).toSet
    assertEquals(seen, ids.toSet)
  }

  test("SC-018: no log line, metric, operator row or webhook payload quotes a dataset") {
    val input = Json.parse(smallInput)
    val names = input.get("employees").elements().asScala.map(_.get("name").asText()).toList
    val locations = input.get("shifts").elements().asScala.map(_.get("location").asText()).toSet.toList
    val id = submit(spentLimit = "PT1S").get("id").asText()
    val _  = awaitFinal(id)
    val lines = CapturingAppender.lines.asScala.toList
    assert(lines.nonEmpty)
    val leaks = lines.filter(line => (names ++ locations).exists(line.contains))
    assertEquals(leaks, Nil)
    val metrics = send("GET", "/metrics", key = None, headers = Seq("Authorization" -> "Bearer scrape-me")).body
    assert(!(names ++ locations).exists(metrics.contains))
    val ops = send("GET", "/api/platform/v1/ops/datasets?size=200", key = None, headers = Seq("Authorization" -> s"Bearer ${TestTokens.operator}")).body
    assert(ops.contains(id))
    assert(!(names ++ locations).exists(ops.contains))
  }
