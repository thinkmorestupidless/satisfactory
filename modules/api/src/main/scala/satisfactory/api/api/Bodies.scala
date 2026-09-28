package satisfactory.api.api

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReaderException, JsonValueCodec, readFromArray}
import com.thinkmorestupidless.ankka.http.{FromBody, RequestContext}

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import scala.util.Using

/**
 * Request bodies as bytes, decoded by the handler: `Content-Encoding: gzip` is honoured here (ankka's
 * server does not decode), with Timefold's limits of 100 MB compressed and 2 GB inflated, enforced
 * while inflating so a small bomb cannot exhaust the heap.
 */
object Bodies:
  val MaxCompressed: Long = 100L * 1024 * 1024
  val MaxInflated: Long   = math.min(2L * 1024 * 1024 * 1024, Int.MaxValue.toLong - 64)

  given bytes: FromBody[Array[Byte]] = new FromBody[Array[Byte]]:
    val contentType                        = "application/json"
    def read(bytes: Array[Byte])           = Right(bytes)

  def decoded(request: RequestContext, body: Array[Byte]): Array[Byte] =
    if body.length.toLong > MaxCompressed then
      throw ApiError.tooLarge(s"the body is ${body.length} bytes; the limit is $MaxCompressed")
    val gzip = request.header("Content-Encoding").exists(_.trim.equalsIgnoreCase("gzip"))
    if !gzip then body else inflate(body, MaxInflated)

  private[satisfactory] def inflate(body: Array[Byte], max: Long): Array[Byte] =
    Using.resource(GZIPInputStream(java.io.ByteArrayInputStream(body))) { in =>
      val out    = ByteArrayOutputStream()
      val buffer = new Array[Byte](64 * 1024)
      var total  = 0L
      var n      = in.read(buffer)
      while n >= 0 do
        total += n
        if total > max then throw ApiError.tooLarge(s"the body inflates past $max bytes")
        out.write(buffer, 0, n)
        n = in.read(buffer)
      out.toByteArray
    }

  def parse[A](bytes: Array[Byte])(using codec: JsonValueCodec[A]): A =
    if bytes.isEmpty then throw ApiError.badRequest("a JSON body is required")
    try readFromArray[A](bytes)
    catch case e: JsonReaderException => throw ApiError.badRequest(s"malformed JSON body: ${e.getMessage}")
