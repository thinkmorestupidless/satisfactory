package satisfactory.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

/**
 * Every wire type's codec, made once. The configuration matches ankka's `Codecs.make` (discriminator
 * `type`, `None` written as `null`) so a type encoded here and one encoded by ankka agree.
 */
object WireCodecs:
  inline def make[A]: JsonValueCodec[A] =
    JsonCodecMaker.make[A](
      CodecMakerConfig
        .withDiscriminatorFieldName(Some("type"))
        .withAllowRecursiveTypes(true)
        .withRequireDiscriminatorFirst(false)
        .withTransientEmpty(false)
        .withTransientNone(false)
        .withMapMaxInsertNumber(4096)
        .withBigDecimalPrecision(64)
        .withBigDecimalScaleLimit(1000)
        .withBigDecimalDigitsLimit(1000)
    )

  given terminationConfig: JsonValueCodec[TerminationConfig]   = make
  given modelConfiguration: JsonValueCodec[ModelConfiguration] = make
  given submitRequest: JsonValueCodec[SubmitRequest]           = make
  given patchRequest: JsonValueCodec[PatchRequest]             = make
  given fromInputRequest: JsonValueCodec[FromInputRequest]     = make
  given metadata: JsonValueCodec[Metadata]                     = make
  given metadataList: JsonValueCodec[List[Metadata]]           = make
  given metadataPage: JsonValueCodec[Page[Metadata]]           = make
  given datasetResponse: JsonValueCodec[DatasetResponse]       = make
  given validationResult: JsonValueCodec[ValidationResult]     = make
  given scoreAnalysis: JsonValueCodec[ScoreAnalysis]           = make
  given logs: JsonValueCodec[Logs]                             = make
  given metadataPatch: JsonValueCodec[MetadataPatch]           = make
  given errorInfo: JsonValueCodec[ErrorInfo]                   = make
  given demoData: JsonValueCodec[List[DemoDataSummary]]        = make
  given modelDescriptor: JsonValueCodec[ModelDescriptor]       = make
  given issueTypes: JsonValueCodec[List[IssueTypeDescriptor]]  = make
  given issueType: JsonValueCodec[IssueTypeDescriptor]         = make
  given whoAmI: JsonValueCodec[WhoAmI]                         = make

  given resolvedConfig: JsonValueCodec[ResolvedConfig]         = make
  given datasetSpec: JsonValueCodec[DatasetSpec]               = make
  given workerRegistration: JsonValueCodec[WorkerRegistration] = make
  given workerAck: JsonValueCodec[WorkerAck]                   = make
  given claimRequest: JsonValueCodec[ClaimRequest]             = make
  given claimResponse: JsonValueCodec[ClaimResponse]           = make
  given phaseRequest: JsonValueCodec[PhaseRequest]             = make
  given reportRequest: JsonValueCodec[ReportRequest]           = make
  given reportResponse: JsonValueCodec[ReportResponse]         = make
  given heartbeatRequest: JsonValueCodec[HeartbeatRequest]     = make
  given heartbeatResponse: JsonValueCodec[HeartbeatResponse]   = make
  given completeRequest: JsonValueCodec[CompleteRequest]       = make
  given failRequest: JsonValueCodec[FailRequest]               = make
  given releaseRequest: JsonValueCodec[ReleaseRequest]         = make

  def encode[A](value: A)(using codec: JsonValueCodec[A]): Array[Byte] = writeToArray(value)
  def encodeString[A](value: A)(using codec: JsonValueCodec[A]): String = writeToString(value)
  def decode[A](bytes: Array[Byte])(using codec: JsonValueCodec[A]): A  = readFromArray(bytes)
  def decode[A](json: String)(using codec: JsonValueCodec[A]): A        = readFromString(json)
