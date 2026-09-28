package satisfactory.api

import satisfactory.api.api.{ApiError, Bodies}

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** The edges an attacker would try first (T110). */
class SecurityHttpSuite extends ApiFixture:

  override protected def configOverrides: Map[String, Any] = TestTokens.config

  test("the runner routes refuse anything without the runner token, or with a wrong one") {
    val body = Some("""{"workerId":"x","models":["employee-scheduling/v1"]}""".getBytes)
    assertEquals(send("POST", "/internal/leases", body, key = None).status, 403)
    assertEquals(send("POST", "/internal/leases", body, key = None, headers = Seq("X-Runner-Token" -> "guess")).status, 403)
    assertEquals(send("POST", "/internal/leases", body, key = Some(keyA)).status, 403, "an API key is not a runner token")
    assertEquals(send("GET", "/internal/blobs/ds_x/input%2F0", key = None).status, 403)
  }

  test("every operator route refuses a tenant member's token") {
    val member = Seq("Authorization" -> s"Bearer ${TestTokens.alice}")
    List(
      "GET"  -> "/api/platform/v1/ops/datasets",
      "GET"  -> "/api/platform/v1/ops/workers",
      "GET"  -> "/api/platform/v1/ops/audit",
      "POST" -> "/api/platform/v1/ops/datasets/ds_x/terminate",
      "POST" -> "/api/platform/v1/ops/workers/w/drain",
      "POST" -> "/api/platform/v1/ops/workers/w/undrain",
      "POST" -> "/api/platform/v1/ops/tenants/t_a/queue/pause",
      "POST" -> "/api/platform/v1/ops/tenants/t_a/queue/resume"
    ).foreach { (method, path) =>
      val reply = send(method, path, Option.when(method == "POST")(Array.emptyByteArray), key = None, headers = member)
      assertEquals(reply.status, 403, s"$method $path")
    }
  }

  test("the platform API does not accept an API key, and the model API does not accept a token") {
    assertEquals(send("GET", "/api/platform/v1/tenants", key = Some(keyA)).status, 401)
    assertEquals(send("GET", s"$es/schedules", key = None, headers = Seq("Authorization" -> s"Bearer ${TestTokens.operator}")).status, 401)
  }

  test("a gzip bomb is refused while it inflates, before it can fill the heap") {
    val out = ByteArrayOutputStream()
    val gz  = GZIPOutputStream(out)
    val zeros = new Array[Byte](1 << 20)
    (1 to 64).foreach(_ => gz.write(zeros))
    gz.close()
    val small = out.toByteArray
    assert(small.length < 200_000, s"${small.length} bytes compressed")
    val reply = send("POST", s"$es/schedules", Some(small), headers = Seq("Content-Encoding" -> "gzip"))
    assertEquals(reply.status, 400, "64 MiB of zeros inflates fine but is not JSON")
    val refused = intercept[ApiError](Bodies.inflate(small, 1 << 20))
    assertEquals(refused.status, 413, "past the cap it stops inflating and refuses")
    assert(Bodies.MaxInflated <= Int.MaxValue.toLong, "the cap keeps the inflated body inside one array")
  }
