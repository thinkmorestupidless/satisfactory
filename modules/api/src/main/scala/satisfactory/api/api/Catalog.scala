package satisfactory.api.api

import satisfactory.protocol.*
import satisfactory.spi.{DemoDataset, ModelRuntime}

import scala.jdk.CollectionConverters.*

/** A model's self-description, as the API serves it: the thing tools are generated from. */
object Catalog:

  def descriptor(runtime: ModelRuntime[?, ?]): ModelDescriptor =
    val model = runtime.model()
    ModelDescriptor(
      id = model.key().registrationKey(),
      version = model.key().version(),
      entity = model.key().entity(),
      name = model.info().name(),
      description = model.info().description(),
      maturity = model.maturity().name(),
      features = model.info().features().asScala.toList,
      schemas = ModelSchemas(
        RawJson(satisfactory.spi.Json.bytes(model.inputSchema().node())),
        RawJson(satisfactory.spi.Json.bytes(model.outputSchema().node())),
        RawJson(satisfactory.spi.Json.bytes(model.overridesSchema().node()))
      ),
      patchPaths = model.patchPaths().asScala.map(p => s"${p.path()}/[${p.keyField()}=…]").toList,
      constraints = model.constraints().asScala.toList.map(c =>
        ConstraintDescriptor(c.name(), c.key(), c.description(), c.level().name(), c.defaultWeight())
      ),
      kpis = model.kpiDescriptors().asScala.toList.map(metric),
      inputMetrics = model.inputMetricDescriptors().asScala.toList.map(metric),
      issueTypes = model.validationIssueTypes().asScala.toList.map(i =>
        IssueTypeDescriptor(i.code(), i.severity().name(), i.message())
      )
    )

  private def metric(m: satisfactory.spi.MetricInfo): MetricDescriptor =
    MetricDescriptor(m.id(), m.title(), m.description(), m.`type`(), m.priority(), m.example())

  def demoSummaries(runtime: ModelRuntime[?, ?]): List[DemoDataSummary] =
    runtime.model().demoData().asScala.toList.map(d =>
      DemoDataSummary(
        d.id(),
        d.shortDescription(),
        d.longDescription(),
        d.tags().asScala.toList,
        d.config().asScala.toList.sortBy(_._1).map((k, v) => KeyValue(k, v))
      )
    )

  def demo(runtime: ModelRuntime[?, ?], id: String): DemoDataset =
    runtime.model().demoData().asScala.find(_.id() == id).getOrElse(throw ApiError.notFound(s"no demo dataset $id"))

  def demoConfig(demo: DemoDataset): ModelConfiguration =
    val spent = Option(demo.config().get("spentLimit"))
    ModelConfiguration(run = Some(RunConfiguration(termination = spent.map(s => TerminationConfig(spentLimit = Some(s))))))

  /** Timefold's issue catalog shape: `{ code, severity, metadata: [{ type: message, message }] }`. */
  def issueTypes(runtime: ModelRuntime[?, ?]): List[java.util.Map[String, Object]] =
    runtime.model().validationIssueTypes().asScala.toList.map { i =>
      val m = java.util.LinkedHashMap[String, Object]()
      m.put("code", i.code())
      m.put("severity", i.severity().name())
      m.put("metadata", java.util.List.of(java.util.Map.of("type", "message", "message", i.message())))
      m
    }

  /** The resolved configuration in the request's own shape, as `GET /{id}/config` answers it. */
  def asConfiguration(config: ResolvedConfig): ModelConfiguration =
    ModelConfiguration(
      run = Some(RunConfiguration(maxThreadCount = Some(config.maxThreadCount), termination = Some(config.termination))),
      model = Some(ModelOverrides(Some(config.weights)))
    )
