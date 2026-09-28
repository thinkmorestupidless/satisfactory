package satisfactory.protocol

/**
 * Timefold's ten `solverStatus` values, adopted exactly (API.md §2.2). Strings on the wire, so a
 * status a client does not know yet still parses.
 */
object SolvingStatus:
  val DatasetCreated   = "DATASET_CREATED"
  val DatasetValidated = "DATASET_VALIDATED"
  val DatasetInvalid   = "DATASET_INVALID"
  val DatasetComputed  = "DATASET_COMPUTED"
  val Scheduled        = "SOLVING_SCHEDULED"
  val Started          = "SOLVING_STARTED"
  val Active           = "SOLVING_ACTIVE"
  val Incomplete       = "SOLVING_INCOMPLETE"
  val Completed        = "SOLVING_COMPLETED"
  val Failed           = "SOLVING_FAILED"

  val all: Vector[String] = Vector(
    DatasetCreated,
    DatasetValidated,
    DatasetInvalid,
    DatasetComputed,
    Scheduled,
    Started,
    Active,
    Incomplete,
    Completed,
    Failed
  )

  /** Where a status sits in the lifecycle; phases only ever move forward. */
  def rank(status: String): Int = status match
    case DatasetCreated   => 0
    case Scheduled        => 1
    case DatasetValidated => 2
    case DatasetComputed  => 3
    case Started          => 4
    case Active           => 5
    case _                => 6

  /**
   * The statuses streams close on and webhooks fire on. `DATASET_COMPUTED` is final only for a
   * dataset submitted with `operation=NONE` that has not been asked to solve since — which only the
   * dataset knows; see `Metadata.isFinal`.
   */
  val terminal: Set[String] = Set(DatasetInvalid, Completed, Incomplete, Failed)

object Operation:
  val Solve = "SOLVE"
  val None  = "NONE"

object Select:
  val Unsolved = "UNSOLVED"
  val Solved   = "SOLVED"
