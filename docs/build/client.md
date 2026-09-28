---
title: Use the client
description: Call satisfactory from Scala or any JVM language with satisfactory-client — submit a dataset, wait for or stream the result, derive a new dataset, and handle a refusal — with no ankka dependency.
kind: guide
languages: [scala]
related: [build/submit-and-poll.md, build/streaming.md, concepts/lineage.md, reference/model-api.md]
---

# Use the client

`satisfactory-client` is a typed client for the model API. It uses the JDK's `java.net.http.HttpClient`
and depends on nothing but the wire types, so it works in any JVM program: a plain Scala application, a
script, or an ankka service. An ankka application that wants agent tools, webhook verification and a
fake for its tests adds [ankka-satisfactory](ankka-applications.md), which builds on this client.

## Add the dependency

The client is published for Scala 3 under the `com.thinkmorestupidless` organisation:

```scala
libraryDependencies += "com.thinkmorestupidless" %% "satisfactory-client" % "<version>"
```

## Create a client and choose a model

A client is constructed with the API's base URL and a tenant's API key. `model` returns a `ModelClient`
for one model and version; its paths come from the model's own descriptor, so the entity name
(`schedules` for employee scheduling, `route-plans` for vehicle routing) is never spelled in your code:

```scala
import satisfactory.client.*
import satisfactory.protocol.*

val client     = SatisfactoryClient("https://api-satisfactory.example.com", sys.env("SAT_KEY"))
val scheduling = client.model("employee-scheduling", "v1")
```

`SatisfactoryClient` takes two optional parameters: the `HttpClient` to use, and a per-request `timeout`
(60 seconds by default). Every request carries the key in the `X-API-KEY` header.

## Submit, wait, fetch

`submit` posts a `SubmitRequest` and returns the new dataset's `Metadata` at once; solving happens on a
worker. `awaitFinal` polls the metadata until the dataset reaches a final status, with an interval that
grows by half each time up to 30 seconds, and returns the last metadata it saw whether or not the status
is final. `get` fetches the best solution so far with its metrics and KPIs. This is the client suite's
first test, which runs against the real API:

<!-- include: modules/client/src/test/scala/satisfactory/client/ClientSuite.scala#submit -->
```scala
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
```

`SubmitOptions` carries what the API takes as query parameters: `name`, `operation` (`SOLVE` or `NONE`),
`configurationId`, `priority`, `tags`, and two things the API takes as headers: `idempotencyKey`, sent as
`Idempotency-Key` so a retried submit returns the same dataset, and `gzip`, which compresses the body and
sets `Content-Encoding: gzip`.

The request's `config` is optional and every field in it is optional. The termination block is where a
solve's length is set: `spentLimit` and `unimprovedSpentLimit` are ISO-8601 durations, and
`stepCountLimit` and `moveCountLimit` are hardware-independent counts.
[Submit and poll](submit-and-poll.md) explains each field.

## Stream progress

`events` opens the dataset's server-sent event stream and returns a blocking iterator of `StreamFrame`
values. An `Update` carries the frame's sequence number and the dataset's metadata; a `Heartbeat` says the
connection is alive. The iterator ends after the final frame, and ends at once for a dataset that is
already final. Pass `after` to resume from a sequence number:

<!-- include: modules/client/src/test/scala/satisfactory/client/ClientSuite.scala#events -->
```scala
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
```

The iterator reconnects on a dropped connection from the last sequence number it saw, and gives up after
twenty reconnects that yield no frame. Close it early with `close()`.

`eventsPublisher` exposes the same frames as a `java.util.concurrent.Flow.Publisher`, for code that
already consumes reactive streams:

<!-- include: modules/client/src/test/scala/satisfactory/client/ClientSuite.scala#publisher -->
```scala
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
```

The frames on the wire are `Metadata`, never solutions. Fetch the solution with `get` when the score says
it is worth fetching. [Stream progress](streaming.md) describes the stream itself.

## Derive a new dataset

A dataset is never changed in place. `fromInput` and `fromPatch` create a child dataset with `parentId`
set to the source, and deriving from a dataset that is still solving supersedes it: the parent completes
with its best solution and its metadata names the child in `supersededBy`. `terminate` stops a solve and
returns the dataset with its best solution kept; `purge` removes the bodies from storage, after which
`get` answers 410, and `restore` brings them back while retention lasts:

<!-- include: modules/client/src/test/scala/satisfactory/client/ClientSuite.scala#lineage -->
```scala
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
```

`fromPatch` takes a list of `PatchOp` values, each an `op` (`add`, `remove` or `replace`), a `path` into
the dataset such as `/employees/[id=Lee]/tags`, and a `value`. [Lineage instead of mutation](../concepts/lineage.md)
explains when to use each of the three.

## Handle a refusal

Every method throws `SatisfactoryError` for a response with a status of 400 or above. It carries the
HTTP `status` and the API's `ErrorInfo`: an `id`, a `code`, a `message` and a list of `details`. A
refusal that ankka's runtime makes before the request reaches satisfactory, such as a 401 from the access
control list, has no `ErrorInfo`; the client builds one whose code is `http-<status>` and whose message
is ankka's:

<!-- include: modules/client/src/test/scala/satisfactory/client/ClientSuite.scala#errors -->
```scala
test("refusals are typed: ErrorInfo from the API, and ankka's own 401") {
  val invalid = intercept[SatisfactoryError](scheduling.submit(SubmitRequest(RawJson("""{"shifts":"no"}"""))))
  assertEquals(invalid.status, 400)
  assertEquals(invalid.info.code, "validation")
  val unauthorised = intercept[SatisfactoryError](SatisfactoryClient(baseUrl, "sk_nope").aboutMe())
  assertEquals(unauthorised.status, 401)
  assert(unauthorised.info.message.nonEmpty)
}
```

## Every method

| Method | Calls |
|---|---|
| `describe()` and `descriptor` | the model's descriptor: schemas, constraints, KPIs, issue types |
| `demoData()`, `demoRequest(id)`, `demoInput(id)` | the model's demo datasets |
| `submit(request, opts)` | `POST /{entity}` |
| `solve(id, priority)` | `POST /{entity}/{id}`, for a dataset submitted with `operation=NONE` |
| `get(id)`, `metadata(id)`, `input(id)`, `modelRequest(id)`, `config(id)` | the dataset, its metadata, its input, the whole request, the resolved configuration |
| `validationResult(id)`, `scoreAnalysis(id, includeJustifications)`, `logs(id)` | the model's validation issues, the per-constraint score, the solve's log |
| `analyze(request, configurationId)` | `POST /{entity}/score-analysis`: scores a plan without creating a dataset |
| `list(filter)` | `GET /{entity}` with statuses, tags, page and size |
| `updateMetadata(id, name, tags)` | `PATCH /{entity}/{id}/metadata` |
| `terminate(id, force)`, `purge(id)`, `restore(id)` | `DELETE /{entity}/{id}`, `DELETE /{entity}/{id}/purge`, `PUT /{entity}/{id}` |
| `fromInput(id, select, config, opts)`, `fromPatch(id, patch, select, config, opts)` | a child dataset from the parent's input or output, or from a patch |
| `awaitFinal(id, poll, max)` | polls `metadata(id)` until a final status |
| `events(id, after, statuses, followLineage)`, `eventsPublisher(...)` | the server-sent event stream |
| `client.aboutMe()` | `GET /api/aboutme`: the key's tenant and role |

The status constants are on `SolvingStatus` (`SolvingStatus.Completed` is `"SOLVING_COMPLETED"`), the
operations on `Operation`, and the `select` values on `Select`. Every wire type is a case class in
`satisfactory.protocol`; [Model API](../reference/model-api.md) lists the fields.
