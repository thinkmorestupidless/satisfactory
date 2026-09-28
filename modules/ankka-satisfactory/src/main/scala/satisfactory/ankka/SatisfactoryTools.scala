package satisfactory.ankka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.agent.{FunctionTool, Json, SchemaType, ToolOutput}
import satisfactory.client.{SatisfactoryClient, SatisfactoryError, SubmitOptions}
import satisfactory.protocol.*
import satisfactory.spi.JsonSchema

import scala.jdk.CollectionConverters.*

/** What the solve tool answers: the dataset to wait for, or why the service refused it. */
final case class SolveToolResult(
    datasetId: Option[String],
    status: Option[String],
    error: Option[String],
    details: List[String]
)

/**
 * Agent tools generated from a model's catalog entry (DESIGN.md §11.2b, API.md §6): the model's
 * description is the tools' description, and the solve tool's parameter schema is the model's own
 * input schema — so an agent's arguments are validated by the same schema the service applies, and
 * refused before anything is sent when they do not match.
 *
 * A solve takes minutes and a tool is a synchronous call, so the solve tool submits and returns the
 * dataset id; a workflow owns the wait and is resumed by the webhook. `tags` go on every submission —
 * `ankka-workflow:<id>` or `ankka-session:<id>` — so the event routes itself back.
 */
object SatisfactoryTools:

  given resultCodec: JsonValueCodec[SolveToolResult] = WireCodecs.make

  def workflowTag(workflowId: String): String = s"ankka-workflow:$workflowId"
  def sessionTag(sessionId: String): String   = s"ankka-session:$sessionId"

  def forModel(client: SatisfactoryClient, key: String, version: String, tags: List[String] = Nil): List[FunctionTool] =
    val model      = client.model(key, version)
    val descriptor = model.descriptor
    val suffix     = key.replace('-', '_')
    val inputNode  = satisfactory.spi.Json.parse(descriptor.schemas.input.bytes)
    val validator  = JsonSchema.of(inputNode)
    given SchemaType[RawJson] = datasetType(inlined(inputNode), validator)

    val solve = FunctionTool
      .named(s"solve_$suffix")
      .describedAs(
        s"Submit a ${descriptor.name} problem to be solved. ${descriptor.description} " +
          "Returns a datasetId; the solution arrives later, so do not wait for it."
      )
      .param[RawJson]("dataset", s"The problem, as ${descriptor.name}'s input.")
      .param[Option[String]]("name", "A name for this dataset.")
      .param[Option[String]]("spentLimit", "How long to solve, ISO-8601 (PT5M). Defaults to the model's.")
      .handle { (dataset: RawJson, name: Option[String], spentLimit: Option[String]) =>
        val config = spentLimit.map(s => ModelConfiguration(run = Some(RunConfiguration(termination = Some(TerminationConfig(spentLimit = Some(s)))))))
        try
          val created = model.submit(SubmitRequest(dataset, config), SubmitOptions(name = name, tags = tags))
          SolveToolResult(Some(created.id), Some(created.solverStatus), None, Nil)
        catch case e: SatisfactoryError => SolveToolResult(None, None, Some(e.info.message), e.info.details)
      }

    val best = FunctionTool
      .named(s"get_best_solution_$suffix")
      .describedAs(s"The best ${descriptor.name} solution found so far for a dataset, with its score and KPIs.")
      .param[String]("datasetId", "The id the solve tool returned.")
      .handle((id: String) => WireCodecs.encodeString(model.get(id))(using WireCodecs.datasetResponse))

    val terminate = FunctionTool
      .named(s"terminate_$suffix")
      .describedAs("Stop solving a dataset now, keeping the best solution found so far.")
      .param[String]("datasetId", "The id the solve tool returned.")
      .handle((id: String) => WireCodecs.encodeString(model.terminate(id).metadata)(using WireCodecs.metadata))

    List(solve, best, terminate)

  /** The model's input schema as the parameter schema, validated on the way in. */
  private def datasetType(input: JsonNode, validator: JsonSchema): SchemaType[RawJson] = new SchemaType[RawJson]:
    val schema: Json = Json.parse(satisfactory.spi.Json.string(input)).getOrElse(Json.obj("type" -> Json.str("object")))
    def decode(value: Option[Json]): Either[String, RawJson] = value match
      case None => Left("missing required value")
      case Some(json) =>
        val text     = json.render
        val problems = validator.validate(satisfactory.spi.Json.parse(text)).asScala.toList
        if problems.isEmpty then Right(RawJson(text))
        else Left(s"does not match the model's input schema: ${problems.take(10).mkString("; ")}")

  /**
   * A tool's parameter schema is embedded under `properties`, where `#/$defs/…` would resolve against
   * the tool's own root; so references are inlined and the schema's own `$schema`/`$id` dropped.
   */
  private[ankka] def inlined(schema: JsonNode): JsonNode =
    val defs = Option(schema.get("$defs"))
    def resolve(node: JsonNode, depth: Int): JsonNode =
      if depth > 32 then node
      else node match
        case o: ObjectNode if o.has("$ref") && o.get("$ref").asText().startsWith("#/$defs/") =>
          val name = o.get("$ref").asText().stripPrefix("#/$defs/")
          defs.flatMap(d => Option(d.get(name))).map(d => resolve(d.deepCopy[JsonNode](), depth + 1)).getOrElse(o)
        case o: ObjectNode =>
          val copy = o.deepCopy()
          copy.remove(java.util.List.of("$defs", "$schema", "$id")): Unit
          copy.fieldNames().asScala.toList.foreach(f => copy.set[JsonNode](f, resolve(copy.get(f), depth + 1)): Unit)
          copy
        case a: com.fasterxml.jackson.databind.node.ArrayNode =>
          val copy = a.arrayNode()
          a.elements().asScala.foreach(e => copy.add(resolve(e, depth + 1)): Unit)
          copy
        case other => other
    resolve(schema, 0)

  private given ToolOutput[SolveToolResult] = ToolOutput.encoded[SolveToolResult]
