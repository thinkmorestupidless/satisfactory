package satisfactory.client

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString}
import satisfactory.protocol.*

import java.io.{IOException, UncheckedIOException}
import java.net.http.HttpResponse
import java.util.concurrent.SubmissionPublisher
import scala.jdk.CollectionConverters.*

/** One event of a dataset's stream. */
enum StreamFrame:
  case Update(seq: Long, metadata: Metadata, skipped: Long)
  case Heartbeat

object Streams:

  private final case class Wire(seq: Option[Long] = None, metadata: Option[Metadata] = None, skipped: Option[Long] = None, heartbeat: Option[Boolean] = None)
  private given JsonValueCodec[Wire] = WireCodecs.make

  private val stringCodec: JsonValueCodec[String] = WireCodecs.make

  def isFinal(m: Metadata): Boolean =
    SolvingStatus.terminal.contains(m.solverStatus) || m.solverStatus == SolvingStatus.DatasetComputed

  /**
   * ankka's SSE writes each payload as a JSON string (`data: "{\"seq\":…}"`), so a frame is decoded
   * twice: the string, then the frame inside it.
   */
  private[client] def decode(line: String): Option[StreamFrame] =
    if !line.startsWith("data:") then None
    else
      val payload = line.stripPrefix("data:").trim
      val inner   = if payload.startsWith("\"") then readFromString[String](payload)(using stringCodec) else payload
      val wire    = readFromString[Wire](inner)
      if wire.heartbeat.contains(true) then Some(StreamFrame.Heartbeat)
      else wire.metadata.map(m => StreamFrame.Update(wire.seq.getOrElse(m.seq), m, wire.skipped.getOrElse(0L)))

  /** A blocking iterator over a dataset's frames, reconnecting from the last `seq` on a dropped connection. */
  final class Frames private[client] (
      client: SatisfactoryClient,
      path: String,
      after: Option[Long],
      statuses: Set[String],
      followLineage: Boolean
  ) extends Iterator[StreamFrame] with AutoCloseable:
    private var lastSeq: Option[Long]              = after
    private var done                               = false
    private var lines: Option[java.util.Iterator[String]] = None
    private var stream: Option[java.util.stream.Stream[String]] = None
    private var nextFrame: Option[StreamFrame]     = None
    private var reconnects                         = 0

    private def open(): Unit =
      val q = Query.many(Query("after" -> lastSeq.map(_.toString), "follow" -> Option.when(followLineage)("lineage")), "status", statuses.toSeq)
      val response = client.http_.send(client.request("GET", path + q, None), HttpResponse.BodyHandlers.ofLines())
      if response.statusCode == 410 then
        response.body().close()
        done = true
      else if response.statusCode >= 400 then
        val body = response.body().iterator().asScala.mkString("\n")
        throw client.error(response.statusCode, body.getBytes("UTF-8"))
      else
        stream = Some(response.body())
        lines = Some(response.body().iterator())

    private def advance(): Unit =
      while nextFrame.isEmpty && !done do
        if lines.isEmpty then open()
        lines match
          case None => ()
          case Some(it) =>
            val hasLine =
              try it.hasNext
              catch case _: UncheckedIOException | _: IOException => false
            if !hasLine then
              stream.foreach(_.close())
              lines = None
              stream = None
              reconnects += 1
              if reconnects > 20 then done = true
            else
              decode(it.next()).foreach { frame =>
                frame match
                  case u: StreamFrame.Update =>
                    lastSeq = Some(u.seq)
                    reconnects = 0
                    if isFinal(u.metadata) && (u.metadata.supersededBy.isEmpty || !followLineage) then
                      closeStream()
                      done = true
                  case StreamFrame.Heartbeat => ()
                nextFrame = Some(frame)
              }

    private def closeStream(): Unit =
      stream.foreach(_.close())
      stream = None
      lines = None

    def hasNext: Boolean =
      if nextFrame.isEmpty then advance()
      nextFrame.isDefined

    def next(): StreamFrame =
      if !hasNext then throw java.util.NoSuchElementException("the stream has ended")
      val frame = nextFrame.get
      nextFrame = None
      frame

    def close(): Unit =
      done = true
      closeStream()

  /** Feeds an iterator into a publisher on a virtual thread; `submit` blocks when subscribers lag. */
  def publisher(frames: Frames): java.util.concurrent.Flow.Publisher[StreamFrame] =
    val p = SubmissionPublisher[StreamFrame](java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(), 256)
    Thread.ofVirtual().start { () =>
      try frames.foreach(f => p.submit(f): Unit)
      catch case e: Throwable => p.closeExceptionally(e)
      finally p.close()
    }
    p
