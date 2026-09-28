package satisfactory.api

import com.typesafe.config.ConfigFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import satisfactory.api.blobs.*

import scala.jdk.CollectionConverters.*

/** Both blob stores behave the same: put, get, delete by ref, delete a dataset's prefix. */
class BlobStoreSuite extends munit.FunSuite:

  private var container: PostgreSQLContainer[?] = null
  private var postgres: PostgresBlobStore       = null

  override def beforeAll(): Unit =
    container = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    container.start()
    postgres = PostgresBlobStore.fromConfig(
      ConfigFactory.parseMap(
        Map(
          "pekko.persistence.r2dbc.connection-factory.host"     -> container.getHost,
          "pekko.persistence.r2dbc.connection-factory.port"     -> container.getMappedPort(5432),
          "pekko.persistence.r2dbc.connection-factory.database" -> container.getDatabaseName,
          "pekko.persistence.r2dbc.connection-factory.user"     -> container.getUsername,
          "pekko.persistence.r2dbc.connection-factory.password" -> container.getPassword
        ).map((k, v) => k -> v.asInstanceOf[AnyRef]).asJava
      )
    )

  override def afterAll(): Unit =
    if postgres != null then postgres.close()
    if container != null then container.stop()

  private def behaves(name: String, store: => BlobStore): Unit =
    test(s"$name: put, get, overwrite, delete, delete a dataset") {
      val s = store
      s.put("ds_a/input/0", "application/json", "{}".getBytes)
      s.put("ds_a/solution/e1-1", "application/json", "[1]".getBytes)
      s.put("ds_a/solution/e1-1", "application/json", "[2]".getBytes)
      s.put("ds_b/input/0", "application/json", "{\"b\":1}".getBytes)
      assertEquals(s.get("ds_a/solution/e1-1").map(b => String(b.bytes)), Some("[2]"))
      s.delete(List("ds_a/input/0"))
      assertEquals(s.get("ds_a/input/0"), None)
      assertEquals(s.deleteAll("ds_a"), 1)
      assertEquals(s.get("ds_a/solution/e1-1"), None)
      assertEquals(s.get("ds_b/input/0").map(_.contentType), Some("application/json"))
    }

  behaves("in memory", InMemoryBlobStore())
  behaves("postgres", postgres)
