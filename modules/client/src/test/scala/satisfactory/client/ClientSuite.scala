package satisfactory.client

import satisfactory.api.ApiFixture
import satisfactory.protocol.*

import java.util.concurrent.{CopyOnWriteArrayList, CountDownLatch, Flow, TimeUnit}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** The plain client against the real API in this JVM (Story 9, the half with no ankka). */
class ClientSuite extends ApiFixture:

  // docs:start submit
  private lazy val client     = SatisfactoryClient(baseUrl, keyA)
  private lazy val scheduling = client.model("employee-scheduling", "v1")

  test("describe, demo data, submit, poll, fetch") {
    assertEquals(scheduling.descriptor.entity, "schedules")
    assertEquals(scheduling.demoData().map(_.id), List("SMALL", "LARGE"))
    val demo    = scheduling.demoRequest("SMALL")
    val request = demo.copy(config = Some(ModelConfiguration(run = Some(RunConfiguration(termination = Some(TerminationConfig(spentLimit = Some("PT2S"))))))))
    val created = scheduling.submit(request, SubmitOptions(name = Some("from-client"), tags = List("client"), gzip = true))
    assertEquals(created.name, Some("from-client"))
    val done = scheduling.awaitFinal(created.id, poll = 250.millis)
    assertEquals(done.solverStatus, SolvingStatus.Completed)
    val result = scheduling.get(created.id)
    assert(result.modelOutput.exists(_.asString.contains("\"employee\"")))
    assert(scheduling.scoreAnalysis(created.id).constraints.nonEmpty)
    assert(scheduling.list(ListFilter(tags = List("client"))).content.exists(_.id == created.id))
    assertEquals(client.aboutMe().tenantId, "t_a")
  }
  // docs:end submit

  // docs:start events
  test("the event iterator unwraps frames, ends on the final one, and resumes after a given seq") {
    val id     = scheduling.submit(SubmitRequest(scheduling.demoInput("SMALL"), Some(ModelConfiguration(run = Some(RunConfiguration(termination = Some(TerminationConfig(spentLimit = Some("PT4S"))))))))).id
    val frames = scheduling.events(id).toList
    val updates = frames.collect { case u: StreamFrame.Update => u }
    assert(updates.nonEmpty)
    assertEquals(updates.last.metadata.solverStatus, SolvingStatus.Completed)
    assertEquals(updates.map(_.seq), updates.map(_.seq).sorted.distinct)
    assertEquals(scheduling.events(id).toList, Nil, "a final dataset's stream is gone: the iterator just ends")
    val resumed = scheduling.events(id, after = Some(updates.head.seq)).toList.collect { case u: StreamFrame.Update => u }
    assert(resumed.nonEmpty && resumed.forall(_.seq > updates.head.seq), resumed.toString)
  }
  // docs:end events

  // docs:start publisher
  test("the publisher delivers the same frames to a reactive subscriber") {
    val id       = scheduling.submit(SubmitRequest(scheduling.demoInput("SMALL"), Some(ModelConfiguration(run = Some(RunConfiguration(termination = Some(TerminationConfig(spentLimit = Some("PT2S"))))))))).id
    val received = CopyOnWriteArrayList[StreamFrame]()
    val finished = CountDownLatch(1)
    scheduling.eventsPublisher(id).subscribe(new Flow.Subscriber[StreamFrame]:
      def onSubscribe(s: Flow.Subscription): Unit = s.request(Long.MaxValue)
      def onNext(item: StreamFrame): Unit         = received.add(item): Unit
      def onError(t: Throwable): Unit             = finished.countDown()
      def onComplete(): Unit                      = finished.countDown()
    )
    assert(finished.await(60, TimeUnit.SECONDS))
    assert(received.asScala.exists { case u: StreamFrame.Update => u.metadata.solverStatus == SolvingStatus.Completed; case _ => false })
  }
  // docs:end publisher

  // docs:start errors
  test("refusals are typed: ErrorInfo from the API, and ankka's own 401") {
    val invalid = intercept[SatisfactoryError](scheduling.submit(SubmitRequest(RawJson("""{"shifts":"no"}"""))))
    assertEquals(invalid.status, 400)
    assertEquals(invalid.info.code, "validation")
    val unauthorised = intercept[SatisfactoryError](SatisfactoryClient(baseUrl, "sk_nope").aboutMe())
    assertEquals(unauthorised.status, 401)
    assert(unauthorised.info.message.nonEmpty)
  }
  // docs:end errors

  // docs:start lineage
  test("lineage, terminate, metadata, purge and restore") {
    val parent = scheduling.submit(SubmitRequest(scheduling.demoInput("SMALL"), Some(ModelConfiguration(run = Some(RunConfiguration(termination = Some(TerminationConfig(spentLimit = Some("PT60S"))))))))).id
    val _      = scheduling.events(parent).find { case u: StreamFrame.Update => u.metadata.score.isDefined && u.metadata.solverStatus == SolvingStatus.Active; case _ => false }
    val child  = scheduling.fromPatch(parent, Nil, config = Some(ModelConfiguration(run = Some(RunConfiguration(termination = Some(TerminationConfig(spentLimit = Some("PT1S"))))))))
    assertEquals(child.parentId, Some(parent))
    assertEquals(scheduling.awaitFinal(parent, 250.millis).supersededBy, Some(child.id))
    val done = scheduling.awaitFinal(child.id, 250.millis)
    assertEquals(scheduling.terminate(child.id).metadata.seq, done.seq, "terminating a final dataset changes nothing")
    assertEquals(scheduling.updateMetadata(child.id, name = Some("renamed")).name, Some("renamed"))
    scheduling.purge(child.id)
    assertEquals(intercept[SatisfactoryError](scheduling.get(child.id)).status, 410)
    scheduling.restore(child.id)
    assert(scheduling.get(child.id).modelOutput.isDefined)
  }
  // docs:end lineage
