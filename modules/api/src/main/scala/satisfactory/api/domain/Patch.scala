package satisfactory.api.domain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import satisfactory.protocol.PatchOp
import satisfactory.spi.Json

/** Why a patch could not be applied: which operation, and what was wrong with it. */
final case class PatchError(index: Int, message: String):
  def describe: String = s"patch[$index]: $message"

/**
 * `from-patch` (API.md §2.5): `add`, `remove` and `replace` at dataset paths — JSON Pointer, with two
 * extensions Timefold's platform uses: `[field=value]` selects the array element whose field has that
 * value (`/employees/[name=Ann]/skills`), and `-` appends (`/shifts/-`). Applied to a copy; the
 * original is never touched.
 */
object Patch:

  private enum Step:
    case Field(name: String)
    case Index(i: Int)
    case Match(field: String, value: String)
    case Append

  def apply(document: JsonNode, ops: List[PatchOp]): Either[PatchError, JsonNode] =
    val copy = document.deepCopy[JsonNode]()
    ops.zipWithIndex.foldLeft[Either[PatchError, JsonNode]](Right(copy)) { case (acc, (op, index)) =>
      acc.flatMap(doc => applyOne(doc, op).left.map(PatchError(index, _)).map(_ => doc))
    }

  private def parse(path: String): Either[String, List[Step]] =
    if !path.startsWith("/") then Left(s"path '$path' must start with /")
    else
      val raw = path.drop(1).split("/", -1).toList.map(_.replace("~1", "/").replace("~0", "~"))
      val steps = raw.map {
        case "-"                                                   => Right(Step.Append)
        case s if s.startsWith("[") && s.endsWith("]") && s.contains("=") =>
          val body = s.drop(1).dropRight(1)
          val eq   = body.indexOf('=')
          Right(Step.Match(body.take(eq), body.drop(eq + 1)))
        case s if s.nonEmpty && s.forall(_.isDigit) => Right(Step.Index(s.toInt))
        case s if s.isEmpty                         => Left(s"path '$path' has an empty segment")
        case s                                      => Right(Step.Field(s))
      }
      steps.collectFirst { case Left(e) => e }.toLeft(steps.collect { case Right(s) => s })

  private def child(node: JsonNode, step: Step): Option[JsonNode] = (node, step) match
    case (o: ObjectNode, Step.Field(name)) => Option(o.get(name))
    case (a: ArrayNode, Step.Index(i))     => Option.when(i < a.size())(a.get(i))
    case (a: ArrayNode, Step.Match(f, v))  => indexOf(a, f, v).map(a.get)
    case _                                 => None

  private def indexOf(a: ArrayNode, field: String, value: String): Option[Int] =
    (0 until a.size()).find(i => Option(a.get(i).get(field)).exists(_.asText() == value))

  private def locate(root: JsonNode, steps: List[Step], path: String): Either[String, JsonNode] =
    steps.foldLeft[Either[String, JsonNode]](Right(root)) { (acc, step) =>
      acc.flatMap(node => child(node, step).toRight(s"'$path' does not resolve to anything"))
    }

  private def applyOne(root: JsonNode, op: PatchOp): Either[String, Unit] =
    for
      steps  <- parse(op.path)
      _      <- Either.cond(steps.nonEmpty, (), "the whole document cannot be patched")
      parent <- locate(root, steps.init, op.path)
      _      <- op.op match
        case "add"     => value(op).flatMap(v => add(parent, steps.last, v, op.path))
        case "replace" => value(op).flatMap(v => replace(parent, steps.last, v, op.path))
        case "remove"  => remove(parent, steps.last, op.path)
        case other     => Left(s"unknown op '$other'; add, remove or replace")
    yield ()

  private def value(op: PatchOp): Either[String, JsonNode] =
    Either.cond(op.hasValue, Json.parse(op.value.bytes), s"'${op.op}' needs a value")

  private def add(parent: JsonNode, step: Step, v: JsonNode, path: String): Either[String, Unit] =
    (parent, step) match
      case (o: ObjectNode, Step.Field(name)) => Right(o.set[JsonNode](name, v): Unit)
      case (a: ArrayNode, Step.Append)       => Right(a.add(v): Unit)
      case (a: ArrayNode, Step.Index(i)) if i <= a.size() => Right(a.insert(i, v): Unit)
      case _ => Left(s"cannot add at '$path'")

  private def replace(parent: JsonNode, step: Step, v: JsonNode, path: String): Either[String, Unit] =
    (parent, step) match
      case (o: ObjectNode, Step.Field(name)) if o.has(name) => Right(o.set[JsonNode](name, v): Unit)
      case (a: ArrayNode, Step.Index(i)) if i < a.size()    => Right(a.set(i, v): Unit)
      case (a: ArrayNode, Step.Match(f, value)) =>
        indexOf(a, f, value).map(i => a.set(i, v): Unit).toRight(s"'$path' does not resolve to anything")
      case _ => Left(s"'$path' does not resolve to anything")

  private def remove(parent: JsonNode, step: Step, path: String): Either[String, Unit] =
    (parent, step) match
      case (o: ObjectNode, Step.Field(name)) if o.has(name) => Right(o.remove(name): Unit)
      case (a: ArrayNode, Step.Index(i)) if i < a.size()    => Right(a.remove(i): Unit)
      case (a: ArrayNode, Step.Match(f, value)) =>
        indexOf(a, f, value).map(i => a.remove(i): Unit).toRight(s"'$path' does not resolve to anything")
      case _ => Left(s"'$path' does not resolve to anything")
