package satisfactory.api.api

import satisfactory.api.application.{RingEntry, Updates}
import satisfactory.protocol.{Metadata, SolvingStatus}

import scala.concurrent.duration.*

/** The stream's coalescing, keep-alive, ending and lineage rules, with a hand-driven clock. */
class StreamSourceSuite extends munit.FunSuite:

  private class Clock:
    var now = 0L
    def advance(d: FiniteDuration): Unit = now += d.toNanos

  private def meta(seq: Long, status: String = SolvingStatus.Active, id: String = "ds_1") =
    Metadata(id, None, None, None, Nil, status, Some(s"0hard/${-100 + seq}soft"), None, None, None, None, None, None, None, None, None, seq)

  private def updates(seqs: Seq[Long], last: Long, status: String = SolvingStatus.Active, isFinal: Boolean = false, supersededBy: Option[String] = None, skipped: Long = 0) =
    Updates(exists = true, seqs.map(s => RingEntry(s, meta(s, status))).toList, skipped, isFinal, supersededBy, last)

  private def state(clock: Clock, options: StreamSource.Options = StreamSource.Options()) =
    StreamSource.State("ds_1", options.after, options, () => clock.now)

  test("the first frame goes at once; later ones are coalesced to one a second, latest wins") {
    val clock = Clock()
    val s     = state(clock)
    val first = s.onUpdates(updates(Seq(1, 2, 3), 3))
    assertEquals(first.size, 1)
    assert(first.head.contains("\"seq\":3"), first.head)
    clock.advance(500.millis)
    assertEquals(s.onUpdates(updates(Seq(4, 5), 5)), Nil)
    clock.advance(600.millis)
    val next = s.onUpdates(updates(Nil, 5))
    assert(next.head.contains("\"seq\":5"), next.toString)
  }

  test("the stream ends only after the final frame has been sent") {
    val clock = Clock()
    val s     = state(clock)
    val _     = s.onUpdates(updates(Seq(1), 1))
    clock.advance(100.millis)
    val held = s.onUpdates(updates(Seq(2), 2, SolvingStatus.Completed, isFinal = true))
    assertEquals(held, Nil, "the final frame waits for its turn")
    clock.advance(1.second)
    val out = s.onUpdates(updates(Nil, 2, SolvingStatus.Completed, isFinal = true))
    assertEquals(out.size, 2)
    assert(out.head.contains(SolvingStatus.Completed))
    assert(out.last.startsWith("\u0000"), "then the end marker")
  }

  test("a quiet stream sends a keep-alive every 15 seconds") {
    val clock = Clock()
    val s     = state(clock)
    clock.advance(10.seconds)
    assertEquals(s.onUpdates(updates(Nil, 0)), Nil)
    clock.advance(6.seconds)
    assertEquals(s.onUpdates(updates(Nil, 0)), List(StreamSource.Heartbeat))
  }

  test("a status filter passes only matching frames, but the stream still ends on a final state") {
    val clock = Clock()
    val s     = state(clock, StreamSource.Options(statuses = Set(SolvingStatus.Completed)))
    assertEquals(s.onUpdates(updates(Seq(1, 2), 2)), Nil)
    clock.advance(2.seconds)
    val out = s.onUpdates(updates(Seq(3), 3, SolvingStatus.Completed, isFinal = true))
    assert(out.head.contains("\"seq\":3"))
  }

  test("a reader too far behind is told how many frames it missed") {
    val clock = Clock()
    val out   = state(clock, StreamSource.Options(after = 5)).onUpdates(updates(Seq(90), 90, skipped = 84))
    assert(out.head.contains("\"skipped\":84"), out.head)
  }

  test("with follow=lineage a superseded dataset hands over to its child") {
    val clock = Clock()
    val s     = state(clock, StreamSource.Options(followLineage = true))
    val out   = s.onUpdates(updates(Seq(4), 4, SolvingStatus.Completed, isFinal = true, supersededBy = Some("ds_child")))
    assertEquals(out.size, 1, "the parent's final frame, and no end")
    assertEquals(s.datasetId, "ds_child")
    assertEquals(s.lastSeq, 0L)
  }
