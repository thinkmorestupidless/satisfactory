package satisfactory.api

import com.thinkmorestupidless.ankka.core.{Done, ErrorCode}
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import satisfactory.api.application.*
import satisfactory.protocol.*

import java.time.Instant

/**
 * The dataset's fold rules, with no runtime or database (quickstart tier 2). Every reply and input
 * still round-trips through the entity's serializers.
 */
class DatasetEntitySuite extends munit.FunSuite:

  private val t0 = Instant.parse("2026-09-28T10:00:00Z")
  private def at(seconds: Long) = t0.plusSeconds(seconds)

  private def spec(operation: String = Operation.Solve) = DatasetSpec(
    "t_1", "employee-scheduling", "v1", "schedules", operation, 5, BlobRefs.input("ds_1"),
    None, None, None, None, ResolvedConfig(TerminationConfig(spentLimit = Some("PT5S")), Map.empty, 1, None), None
  )

  private def kit(operation: String = Operation.Solve) =
    val k = EventSourcedTestKit.of(DatasetEntity, "ds_1")
    val _ = k.call(DatasetEntity.create)(CreateDataset(spec(operation), Some("week-39"), List("a"), t0))
    k

  private def leased(k: EventSourcedTestKit[DatasetEntity, Dataset, DatasetEvent], worker: String = "w1") =
    k.call(DatasetEntity.lease)(LeaseDataset(worker, at(1))).replyValue

  private def record(k: EventSourcedTestKit[DatasetEntity, Dataset, DatasetEvent], epoch: Long, soft: Int, ref: String) =
    k.call(DatasetEntity.recordSolution)(
      RecordSolution(epoch, s"0hard/${soft}soft", List(BigDecimal(0), BigDecimal(soft)), true, ref, None, at(2))
    )

  test("SOLVE is scheduled at once; NONE waits to be validated") {
    assertEquals(kit().currentState.status, SolvingStatus.Scheduled)
    assertEquals(kit(Operation.None).currentState.status, SolvingStatus.DatasetCreated)
    assert(kit(Operation.None).currentState.queued)
  }

  test("creating twice is a conflict") {
    val k = kit()
    val again = k.call(DatasetEntity.create)(CreateDataset(spec(), None, Nil, t0))
    assertEquals(again.error.code, ErrorCode.Conflict)
    assert(!again.persisted)
  }

  test("one claimant wins the lease; the next is refused") {
    val k     = kit()
    val grant = leased(k)
    assertEquals(grant.epoch, 1L)
    assertEquals(grant.mode, ClaimMode.Full)
    val second = k.call(DatasetEntity.lease)(LeaseDataset("w2", at(1)))
    assertEquals(second.error.code, ErrorCode.Conflict)
  }

  test("rule 1: a report from a stale epoch is refused and persists nothing") {
    val k = kit()
    val _ = leased(k)
    val _ = k.call(DatasetEntity.expireLease)(ExpireLease(1, 3, at(30)))
    val grant = leased(k)
    assertEquals(grant.epoch, 2L)
    val stale = record(k, 1, -100, "ds_1/solution/e1-1")
    assertEquals(stale.error.code, ErrorCode.Conflict)
    assert(!stale.persisted)
    assert(k.call(DatasetEntity.heartbeat)(Beat(1, Nil)).isError)
    assert(!k.call(DatasetEntity.heartbeat)(Beat(2, Nil)).isError)
  }

  test("rule 2: only a strictly better score is recorded, and seq is the entity's") {
    val k = kit()
    val e = leased(k).epoch
    val first = record(k, e, -500, "r1")
    assert(first.replyValue.recorded)
    val same = record(k, e, -500, "r2")
    assert(!same.replyValue.recorded)
    assert(!same.persisted)
    val worse = record(k, e, -600, "r3")
    assert(!worse.persisted)
    val better = record(k, e, -400, "r4")
    assert(better.replyValue.recorded)
    assert(better.replyValue.seq > first.replyValue.seq)
    assertEquals(k.currentState.best.map(_.score), Some("0hard/-400soft"))
    assertEquals(k.currentState.best.map(_.seq), Some(k.currentState.seq))
  }

  test("the stream is monotonic: every recorded score in the ring improves on the last") {
    val k = kit()
    val e = leased(k).epoch
    (1 to 30).foreach(i => record(k, e, -1000 + i * (if i % 3 == 0 then -1 else 7), s"r$i"))
    val scores = k.currentState.ring.flatMap(_.metadata.score).map(_.stripPrefix("0hard/").stripSuffix("soft").toInt)
    assertEquals(scores, scores.distinct.sorted)
    val seqs = k.currentState.ring.map(_.seq)
    assertEquals(seqs, seqs.sorted.distinct)
  }

  test("the ring is bounded, and a reader too far behind is told how much it missed") {
    val k = kit()
    val e = leased(k).epoch
    (1 to 120).foreach(i => record(k, e, -1000 + i, s"r$i"))
    assertEquals(k.currentState.ring.size, DatasetFold.RingSize)
    val updates = k.call(DatasetEntity.updatesSince)(5L).replyValue
    assertEquals(updates.entries.size, 1)
    assert(updates.skipped > 0)
    val recent = k.call(DatasetEntity.updatesSince)(k.currentState.seq - 3).replyValue
    assertEquals(recent.entries.map(_.seq), ((k.currentState.seq - 2) to k.currentState.seq).toList)
    assertEquals(recent.skipped, 0L)
  }

  test("retention keeps the first feasible and the last ten; the rest are evicted") {
    val k = kit()
    val e = leased(k).epoch
    val evicted = (1 to 15).flatMap(i => record(k, e, -1000 + i, s"r$i").replyValue.evicted).toList
    assertEquals(evicted, List("r2", "r3", "r4", "r5"))
    assertEquals(k.currentState.retained.toList, "r1" :: (6 to 15).map(i => s"r$i").toList)
  }

  test("a lost lease re-queues warm from the best; the third loss fails and keeps the best") {
    val k = kit()
    val e1 = leased(k).epoch
    val _ = record(k, e1, -50, "best-1")
    val _ = k.call(DatasetEntity.expireLease)(ExpireLease(e1, 3, at(30)))
    assert(k.currentState.queued)
    assertEquals(k.currentState.status, SolvingStatus.Active)
    val g2 = leased(k)
    assertEquals(g2.mode, ClaimMode.Warm)
    assertEquals(g2.warmStartRef, Some("best-1"))
    val _ = k.call(DatasetEntity.expireLease)(ExpireLease(g2.epoch, 3, at(60)))
    val g3 = leased(k)
    val _ = k.call(DatasetEntity.expireLease)(ExpireLease(g3.epoch, 3, at(90)))
    assertEquals(k.currentState.status, SolvingStatus.Failed)
    assertEquals(k.currentState.best.map(_.solutionRef), Some("best-1"))
    assert(k.currentState.failureMessage.exists(_.contains("3 times")))
  }

  test("the lifetime ceiling finishes an active solve with its best, and is a no-op once final") {
    val k = kit()
    val e = leased(k).epoch
    val _ = record(k, e, -10, "r1")
    val _ = k.call(DatasetEntity.expireLifetime)(At(at(100)))
    assertEquals(k.currentState.status, SolvingStatus.Completed)
    assert(k.call(DatasetEntity.heartbeat)(Beat(e, Nil)).isError, "the worker is told to stop")
    assert(!k.call(DatasetEntity.expireLifetime)(At(at(101))).persisted)
    val queued = kit()
    val _ = queued.call(DatasetEntity.expireLifetime)(At(at(100)))
    assertEquals(queued.currentState.status, SolvingStatus.Incomplete)
  }

  test("an expiry for an epoch that has moved on is a no-op") {
    val k = kit()
    val e = leased(k).epoch
    val r = k.call(DatasetEntity.expireLease)(ExpireLease(e + 5, 3, at(30)))
    assertEquals(r.replyValue, Done)
    assert(!r.persisted)
  }

  test("terminate: a leased dataset hears it on its heartbeat, then completes with its best") {
    val k = kit()
    val e = leased(k).epoch
    val _ = record(k, e, -10, "r1")
    val _ = k.call(DatasetEntity.requestTerminate)(TerminateDataset("k_1", force = false, at(3)))
    assertEquals(k.call(DatasetEntity.heartbeat)(Beat(e, Nil)).replyValue, Control(true, false))
    val _ = k.call(DatasetEntity.complete)(CompleteDataset(e, CompleteReason.Terminated, None, at(4)))
    assertEquals(k.currentState.status, SolvingStatus.Completed)
    assert(k.currentState.isFinal)
  }

  test("terminate: queued or forced finishes at once; without a solution it is incomplete") {
    val queued = kit()
    val _ = queued.call(DatasetEntity.requestTerminate)(TerminateDataset("k_1", force = false, at(3)))
    assertEquals(queued.currentState.status, SolvingStatus.Incomplete)
    val forced = kit()
    val e = leased(forced).epoch
    val _ = record(forced, e, -10, "r1")
    val _ = forced.call(DatasetEntity.requestTerminate)(TerminateDataset("op", force = true, at(3)))
    assertEquals(forced.currentState.status, SolvingStatus.Completed)
    assert(forced.call(DatasetEntity.heartbeat)(Beat(e, Nil)).isError, "the worker's lease is gone")
  }

  test("terminating a final dataset changes nothing") {
    val k = kit()
    val _ = k.call(DatasetEntity.requestTerminate)(TerminateDataset("k_1", force = false, at(3)))
    val again = k.call(DatasetEntity.requestTerminate)(TerminateDataset("k_1", force = false, at(4)))
    assert(!again.persisted)
  }

  test("supersede completes an active parent with its best and names the child") {
    val k = kit()
    val e = leased(k).epoch
    val _ = record(k, e, -10, "r1")
    val m = k.call(DatasetEntity.supersede)(SupersedeDataset("ds_child", at(5))).replyValue
    assertEquals(m.solverStatus, SolvingStatus.Completed)
    assertEquals(m.supersededBy, Some("ds_child"))
  }

  test("operation NONE stops at computed, and a later solve continues without re-validating") {
    val k = kit(Operation.None)
    val e = leased(k).epoch
    val summary = Some(ValidationSummary(ValidationStatus.Ok))
    val ok      = Some(ValidationResult(ValidationStatus.Ok, RawJson.emptyArray))
    val _ = k.call(DatasetEntity.phase)(PhaseUpdate(e, Phase.Validated, ok, summary, None, None, None, at(2)))
    val _ = k.call(DatasetEntity.phase)(PhaseUpdate(e, Phase.Computed, None, None, Some("0hard/-5soft"), None, None, at(3)))
    assertEquals(k.currentState.status, SolvingStatus.DatasetComputed)
    assert(k.currentState.isFinal)
    assert(k.currentState.lease.isEmpty)
    assertEquals(k.currentState.metadata.score, Some("0hard/-5soft"))
    val m = k.call(DatasetEntity.solve)(SolveDataset(Some(9), at(4))).replyValue
    assertEquals(m.solverStatus, SolvingStatus.Scheduled)
    assertEquals(leased(k).mode, ClaimMode.Solve)
    assertEquals(k.currentState.spec.map(_.priority), Some(9))
  }

  test("an invalid dataset is final") {
    val k = kit()
    val e = leased(k).epoch
    val bad = Some(ValidationResult(ValidationStatus.Errors, RawJson("""[{"code":"EmptySchedule"}]""")))
    val _ = k.call(DatasetEntity.phase)(PhaseUpdate(e, Phase.Invalid, bad, Some(ValidationSummary("ERRORS", List("EmptySchedule"))), None, None, None, at(2)))
    assertEquals(k.currentState.status, SolvingStatus.DatasetInvalid)
    assert(k.currentState.isFinal)
  }

  test("purge is refused while solving, and restore is refused after expiry") {
    val k = kit()
    assertEquals(k.call(DatasetEntity.purge)(At(at(1))).error.code, ErrorCode.BadRequest)
    val _ = k.call(DatasetEntity.requestTerminate)(TerminateDataset("k", force = false, at(2)))
    assert(k.call(DatasetEntity.purge)(At(at(3))).replyValue.id == "ds_1")
    assert(k.currentState.purged)
    val _ = k.call(DatasetEntity.restore)(At(at(4)))
    assert(!k.currentState.purged)
    assert(k.call(DatasetEntity.expireRetention)(At(at(5))).replyValue)
    assertEquals(k.call(DatasetEntity.restore)(At(at(6))).error.code, ErrorCode.NotFound)
  }

  test("metadata limits: 255 characters, 100 unique tags") {
    val k = kit()
    assert(k.call(DatasetEntity.updateMetadata)(UpdateMetadata(Some("x" * 256), None)).isError)
    assert(k.call(DatasetEntity.updateMetadata)(UpdateMetadata(None, Some(List("a", "a")))).isError)
    val ok = k.call(DatasetEntity.updateMetadata)(UpdateMetadata(Some("renamed"), Some(List("b")))).replyValue
    assertEquals((ok.name, ok.tags), (Some("renamed"), List("b")))
  }

  test("replaying the journal reproduces the state, sequence numbers included") {
    val k = kit()
    val e = leased(k).epoch
    (1 to 12).foreach(i => record(k, e, -100 + i, s"r$i"))
    val _ = k.call(DatasetEntity.complete)(CompleteDataset(e, CompleteReason.Termination, None, at(9)))
    val replayed = k.allEvents.foldLeft(DatasetFold.empty("ds_1"))(DatasetFold.apply)
    assertEquals(replayed, k.currentState)
  }
