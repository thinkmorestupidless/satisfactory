package satisfactory.runner

import satisfactory.protocol.*

/**
 * What a worker needs from the API, and nothing else (contracts/runner-protocol.md). Two
 * implementations: over HTTP in the `solver` service, and through the component client inside `api`
 * for the single-process mode. No HTTP or Pekko types here — that is the seam that lets a dedicated
 * pod run this code without an actor system (DESIGN.md §11.1).
 */
trait ControlChannel:
  def register(workerId: String, registration: WorkerRegistration): Either[ChannelError, WorkerAck]
  def deregister(workerId: String): Either[ChannelError, Unit]

  /** `None` when nothing is claimable right now; the caller backs off and asks again. */
  def claim(request: ClaimRequest): Either[ChannelError, Option[ClaimResponse]]

  def phase(datasetId: String, request: PhaseRequest): Either[ChannelError, Unit]
  def report(datasetId: String, request: ReportRequest): Either[ChannelError, ReportResponse]
  def heartbeat(datasetId: String, request: HeartbeatRequest): Either[ChannelError, HeartbeatResponse]
  def complete(datasetId: String, request: CompleteRequest): Either[ChannelError, Unit]
  def fail(datasetId: String, request: FailRequest): Either[ChannelError, Unit]
  def release(datasetId: String, request: ReleaseRequest): Either[ChannelError, Unit]

  def getBlob(ref: String): Either[ChannelError, Array[Byte]]
  def putBlob(ref: String, contentType: String, bytes: Array[Byte]): Either[ChannelError, Unit]

enum ChannelError:
  /** The lease this worker holds was superseded (409): stop the solve, discard it, free the slot. */
  case Stale(message: String)

  /** The API could not be reached or answered 5xx; worth retrying. */
  case Unavailable(message: String)

  /** Any other refusal. */
  case Rejected(status: Int, message: String)

  def describe: String = this match
    case Stale(m)          => s"stale lease: $m"
    case Unavailable(m)    => s"api unavailable: $m"
    case Rejected(s, m)    => s"rejected ($s): $m"
