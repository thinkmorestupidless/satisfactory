package satisfactory.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.*

import java.nio.charset.StandardCharsets.UTF_8

/**
 * A JSON value carried through the wire types without being interpreted: a model's input, its
 * output, its KPIs, its schemas. The bytes are exactly what was read, and are written back verbatim.
 */
final class RawJson private (val bytes: Array[Byte]):
  def asString: String = String(bytes, UTF_8)

  override def equals(other: Any): Boolean = other match
    case that: RawJson => java.util.Arrays.equals(bytes, that.bytes)
    case _             => false

  override def hashCode: Int     = java.util.Arrays.hashCode(bytes)
  override def toString: String = s"RawJson(${asString.take(120)})"

object RawJson:
  def apply(bytes: Array[Byte]): RawJson = new RawJson(bytes)
  def apply(json: String): RawJson       = new RawJson(json.getBytes(UTF_8))

  val emptyObject: RawJson = RawJson("{}")
  val emptyArray: RawJson  = RawJson("[]")

  given codec: JsonValueCodec[RawJson] = new JsonValueCodec[RawJson]:
    def decodeValue(in: JsonReader, default: RawJson): RawJson = RawJson(in.readRawValAsBytes())
    def encodeValue(x: RawJson, out: JsonWriter): Unit          = out.writeRawVal(x.bytes)
    def nullValue: RawJson                                      = null
