package satisfactory.api.api

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, writeToString}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import satisfactory.api.application.Updates
import satisfactory.protocol.{Metadata, WireCodecs}

import scala.concurrent.Future
import scala.concurrent.duration.*

/** One frame of a dataset's event stream: its metadata at `seq`, never the solution (API.md §2.4). */
final case class StreamFrame(seq: Long, metadata: Metadata, skipped: Option[Long])

object StreamFrame:
  given codec: JsonValueCodec[StreamFrame] = WireCodecs.make

/**
 * A dataset's metadata as server-sent events (DESIGN.md §5). Entities cannot stream, so this polls
 * the entity's ring every `tick`, coalesces to at most one frame per `minInterval` (the latest wins),
 * sends a keep-alive when quiet, and ends after the frame that carries a final status. With
 * `followLineage`, a superseded dataset's final frame is followed by its successor's frames.
 *
 * Resumable by construction: frames carry the entity's journaled `seq`, and `after` picks up exactly
 * where a reader left off — or opens with the current best and says how many it skipped.
 */
object StreamSource:

  final case class Options(
      after: Long = 0,
      statuses: Set[String] = Set.empty,
      followLineage: Boolean = false,
      tick: FiniteDuration = 500.millis,
      minInterval: FiniteDuration = 1.second,
      keepAlive: FiniteDuration = 15.seconds
  )

  val Heartbeat = """{"heartbeat":true}"""

  private val End = "\u0000end"

  def apply(datasetId: String, options: Options)(fetch: (String, Long) => Future[Updates]): Source[String, NotUsed] =
    Source
      .fromMaterializer { (_, _) =>
        val state = State(datasetId, options.after, options, () => System.nanoTime())
        Source
          .tick(Duration.Zero, options.tick, ())
          .mapAsync(1)(_ => fetch(state.datasetId, state.lastSeq))
          .mapConcat(state.onUpdates)
          .takeWhile(_ != End)
      }
      .mapMaterializedValue(_ => NotUsed)

  /** The stream's memory: where it is, what is waiting, when it last spoke. One per materialisation. */
  private[api] final class State(var datasetId: String, var lastSeq: Long, options: Options, nanoTime: () => Long):
    private var pending: Option[StreamFrame] = None
    private var lastEmitted                  = Long.MinValue
    private var lastSpoke                    = nanoTime()

    private def due(now: Long): Boolean =
      lastEmitted == Long.MinValue || now - lastEmitted >= options.minInterval.toNanos

    def onUpdates(updates: Updates): List[String] =
      val now = nanoTime()
      if !updates.exists then return List(End)
      val matching = updates.entries.filter(e => options.statuses.isEmpty || options.statuses.contains(e.metadata.solverStatus))
      matching.lastOption.foreach(e =>
        pending = Some(StreamFrame(e.seq, e.metadata, Option.when(updates.skipped > 0)(updates.skipped)))
      )
      lastSeq = math.max(lastSeq, updates.seq)
      val out = List.newBuilder[String]
      if pending.isDefined && due(now) then
        out += writeToString(pending.get)
        pending = None
        lastEmitted = now
        lastSpoke = now
      val caughtUp = pending.isEmpty && lastSeq >= updates.seq
      if updates.isFinal && caughtUp then
        updates.supersededBy.filter(_ => options.followLineage) match
          case Some(child) =>
            datasetId = child
            lastSeq = 0
          case None => out += End
      else if now - lastSpoke >= options.keepAlive.toNanos then
        out += Heartbeat
        lastSpoke = now
      out.result()
