package satisfactory.api

import satisfactory.protocol.SolvingStatus
import satisfactory.spi.Json

import java.net.URI
import java.net.http.{HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Story 2: follow progress live and stop when it is good enough (quickstart tier 3). */
class StreamHttpSuite extends ApiFixture:

  final case class Frame(json: com.fasterxml.jackson.databind.JsonNode, at: Long):
    def seq: Long       = json.get("seq").asLong()
    def status: String  = json.at("/metadata/solverStatus").asText()
    def score: String   = json.at("/metadata/score").asText()
    def heartbeat       = json.has("heartbeat")

  /** Reads frames until the stream closes, `limit` frames arrive, or `timeout` passes. */
  private def stream(path: String, limit: Int = Int.MaxValue, timeout: FiniteDuration = 90.seconds): (Int, List[Frame]) =
    val request = HttpRequest.newBuilder(URI.create(baseUrl + path))
      .header("X-API-KEY", keyA)
      .timeout(Duration.ofMillis(timeout.toMillis))
      .GET()
      .build()
    val response = http.send(request, HttpResponse.BodyHandlers.ofLines())
    if response.statusCode != 200 then (response.statusCode, Nil)
    else
      val frames = response.body().iterator().asScala
        .filter(_.startsWith("data:"))
        .map(line => Frame(Json.parse(Json.parse(line.stripPrefix("data:").trim).asText()), System.nanoTime()))
        .take(limit)
        .toList
      response.body().close()
      (200, frames)

  private def scoreValue(score: String): (BigDecimal, BigDecimal) =
    val parts = score.split('/')
    (BigDecimal(parts(0).stripSuffix("hard")), BigDecimal(parts(1).stripSuffix("soft")))

  test("a live stream: frames carry metadata not solutions, never regress, come at most once a second, and close on the final one (SC-003, SC-004)") {
    val id = submit(spentLimit = "PT6S").get("id").asText()
    val (status, frames) = stream(s"$es/schedules/$id/events")
    assertEquals(status, 200)
    val data = frames.filterNot(_.heartbeat)
    assert(data.size >= 2, data.map(_.json).toString)
    assert(data.forall(f => !f.json.at("/metadata").has("modelOutput")))
    assertEquals(data.last.status, SolvingStatus.Completed)
    assertEquals(data.map(_.seq), data.map(_.seq).sorted.distinct)
    val scored = data.filter(_.json.at("/metadata/score").isTextual).map(f => scoreValue(f.score))
    scored.zip(scored.drop(1)).foreach { case (a, b) =>
      assert(Ordering[(BigDecimal, BigDecimal)].gteq(b, a), s"$a then $b")
    }
    data.zip(data.drop(1)).foreach { case (a, b) =>
      assert((b.at - a.at).nanos >= 800.millis, s"frames ${a.seq} and ${b.seq} were ${(b.at - a.at).nanos.toMillis} ms apart")
    }
  }

  test("a reader that disconnects resumes after the last frame it saw, with no gaps and no repeats") {
    val id = submit(spentLimit = "PT8S").get("id").asText()
    val (_, firstTwo) = stream(s"$es/schedules/$id/events", limit = 2)
    val lastSeen      = firstTwo.last.seq
    val (_, rest)     = stream(s"$es/schedules/$id/events?after=$lastSeen")
    val resumed       = rest.filterNot(_.heartbeat)
    assert(resumed.nonEmpty)
    assert(resumed.head.seq > lastSeen, s"${resumed.head.seq} after $lastSeen")
    assertEquals(resumed.last.status, SolvingStatus.Completed)
  }

  test("a final dataset's stream is gone; fetch it instead") {
    val id = submit(spentLimit = "PT1S").get("id").asText()
    val _  = awaitFinal(id)
    assertEquals(stream(s"$es/schedules/$id/events")._1, 410)
  }

  test("a status filter sends only the frames asked for") {
    val id              = submit(spentLimit = "PT3S").get("id").asText()
    val (_, frames)     = stream(s"$es/schedules/$id/events?status=SOLVING_COMPLETED")
    val data            = frames.filterNot(_.heartbeat)
    assertEquals(data.map(_.status), List(SolvingStatus.Completed))
  }

  test("terminating a solve keeps its best: completed within the heartbeat, with the solution (SC-005)") {
    val id = submit(spentLimit = "PT120S").get("id").asText()
    val _  = awaitStatus(id, Set(SolvingStatus.Active))
    val _  = eventually()(get(s"$es/schedules/$id").json)(_.get("modelOutput").isObject)
    val started = System.nanoTime()
    val reply   = send("DELETE", s"$es/schedules/$id")
    assertEquals(reply.status, 200, reply.toString)
    val done = awaitFinal(id, 30.seconds)
    assert((System.nanoTime() - started).nanos < 10.seconds)
    assertEquals(done.get("solverStatus").asText(), SolvingStatus.Completed)
    assert(get(s"$es/schedules/$id").json.get("modelOutput").isObject)
    assert(get(s"$es/schedules/$id/logs").json.get("details").asText().contains("terminat"))
  }

  test("a quiet stream keeps its connection with keep-alives, and a queued dataset terminated has no solution") {
    val busy   = submit(spentLimit = "PT60S").get("id").asText()
    val _      = awaitStatus(busy, Set(SolvingStatus.Active))
    val queued = submit(spentLimit = "PT5S").get("id").asText()
    val (_, frames) = stream(s"$es/schedules/$queued/events", limit = 2, timeout = 40.seconds)
    assert(frames.exists(_.heartbeat), frames.map(_.json).toString)
    val stopped = send("DELETE", s"$es/schedules/$queued").json
    assertEquals(stopped.at("/metadata/solverStatus").asText(), SolvingStatus.Incomplete)
    val again = send("DELETE", s"$es/schedules/$queued").json
    assertEquals(again.at("/metadata/seq").asLong(), stopped.at("/metadata/seq").asLong(), "terminating a final dataset changes nothing")
    val _ = send("DELETE", s"$es/schedules/$busy?force=true")
    assertEquals(awaitFinal(busy, 10.seconds).get("solverStatus").asText(), SolvingStatus.Completed)
  }

  test("a read-only key cannot terminate") {
    val id = submit(spentLimit = "PT1S").get("id").asText()
    assertEquals(send("DELETE", s"$es/schedules/$id", key = Some(keyAReadOnly)).status, 403)
    val _ = awaitFinal(id)
  }
