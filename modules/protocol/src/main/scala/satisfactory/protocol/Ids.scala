package satisfactory.protocol

import java.security.SecureRandom

/**
 * Identifiers: a prefix and a ULID (48 bits of milliseconds, 80 random bits, Crockford base32), so
 * they sort by creation time and say what they name.
 */
object Ids:
  private val random   = SecureRandom()
  private val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray

  def ulid(now: Long = System.currentTimeMillis()): String =
    val chars = new Array[Char](26)
    var time  = now
    var i     = 9
    while i >= 0 do
      chars(i) = alphabet((time & 31).toInt)
      time = time >>> 5
      i -= 1
    val bytes = new Array[Byte](10)
    random.nextBytes(bytes)
    var bits  = BigInt(1, bytes)
    var j     = 25
    while j >= 10 do
      chars(j) = alphabet((bits & 31).toInt)
      bits = bits >> 5
      j -= 1
    String(chars)

  def dataset(): String      = "ds_" + ulid()
  def tenant(): String       = "t_" + ulid()
  def apiKey(): String       = "k_" + ulid()
  def profile(): String      = "cp_" + ulid()
  def subscription(): String = "wh_" + ulid()
  def event(): String        = "evt_" + ulid()

  /** A random secret: 32 bytes, base64url, no padding. */
  def secret(prefix: String): String =
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    prefix + java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

/** Blob references: `<datasetId>/<kind>/<part>`, so a dataset's blobs are one prefix. */
object BlobRefs:
  def input(datasetId: String): String                      = s"$datasetId/input/0"
  def patch(datasetId: String): String                      = s"$datasetId/patch/0"
  def solution(datasetId: String, epoch: Long, n: Long): String = s"$datasetId/solution/e$epoch-$n"
  def analysis(datasetId: String, name: String): String     = s"$datasetId/analysis/$name"
  def datasetOf(ref: String): String                         = ref.takeWhile(_ != '/')
