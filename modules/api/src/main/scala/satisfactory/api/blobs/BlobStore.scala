package satisfactory.api.blobs

import com.thinkmorestupidless.ankka.runtime.{AnkkaService, DatabaseTls, RuntimeExtension}
import com.typesafe.config.Config
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*
import scala.util.Using

final case class Blob(contentType: String, bytes: Array[Byte])

/**
 * Bodies that never enter the journal: inputs, patches, solutions, analyses (DESIGN.md §3.3 rule 3).
 * Addressed by reference; a dataset's blobs share its id as a prefix. Blocking calls — every caller
 * is on a virtual thread.
 */
trait BlobStore:
  def put(ref: String, contentType: String, bytes: Array[Byte]): Unit
  def get(ref: String): Option[Blob]
  def delete(refs: Iterable[String]): Unit
  def deleteAll(datasetId: String): Int
  def close(): Unit = ()

final class InMemoryBlobStore extends BlobStore:
  private val blobs = ConcurrentHashMap[String, Blob]()

  def put(ref: String, contentType: String, bytes: Array[Byte]): Unit =
    blobs.put(ref, Blob(contentType, bytes)): Unit

  def get(ref: String): Option[Blob] = Option(blobs.get(ref))

  def delete(refs: Iterable[String]): Unit = refs.foreach(r => blobs.remove(r): Unit)

  def deleteAll(datasetId: String): Int =
    val doomed = blobs.keySet().asScala.filter(_.startsWith(datasetId + "/")).toList
    doomed.foreach(r => blobs.remove(r): Unit)
    doomed.size

  def size: Int = blobs.size

/**
 * A table in the api service's own provisioned database (research R11), reached with plain JDBC on
 * the same coordinates the journal uses. Adequate to tens of megabytes per solution; object storage
 * is a later change behind the same trait.
 */
final class PostgresBlobStore(dataSource: HikariDataSource) extends BlobStore:

  Using.resource(dataSource.getConnection) { c =>
    Using.resource(c.createStatement()) { s =>
      s.execute(
        """CREATE TABLE IF NOT EXISTS satisfactory_blobs (
          |  ref          TEXT PRIMARY KEY,
          |  dataset_id   TEXT NOT NULL,
          |  content_type TEXT NOT NULL,
          |  size         BIGINT NOT NULL,
          |  body         BYTEA NOT NULL,
          |  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
          |)""".stripMargin
      ): Unit
      s.execute("CREATE INDEX IF NOT EXISTS satisfactory_blobs_dataset ON satisfactory_blobs (dataset_id)"): Unit
    }
  }

  private def withConnection[A](f: Connection => A): A = Using.resource(dataSource.getConnection)(f)

  def put(ref: String, contentType: String, bytes: Array[Byte]): Unit = withConnection { c =>
    Using.resource(
      c.prepareStatement(
        """INSERT INTO satisfactory_blobs (ref, dataset_id, content_type, size, body) VALUES (?, ?, ?, ?, ?)
          |ON CONFLICT (ref) DO UPDATE SET content_type = EXCLUDED.content_type, size = EXCLUDED.size,
          |  body = EXCLUDED.body""".stripMargin
      )
    ) { s =>
      s.setString(1, ref)
      s.setString(2, ref.takeWhile(_ != '/'))
      s.setString(3, contentType)
      s.setLong(4, bytes.length.toLong)
      s.setBytes(5, bytes)
      s.executeUpdate(): Unit
    }
  }

  def get(ref: String): Option[Blob] = withConnection { c =>
    Using.resource(c.prepareStatement("SELECT content_type, body FROM satisfactory_blobs WHERE ref = ?")) { s =>
      s.setString(1, ref)
      Using.resource(s.executeQuery()) { rs =>
        if rs.next() then Some(Blob(rs.getString(1), rs.getBytes(2))) else None
      }
    }
  }

  def delete(refs: Iterable[String]): Unit =
    if refs.nonEmpty then
      withConnection { c =>
        Using.resource(c.prepareStatement("DELETE FROM satisfactory_blobs WHERE ref = ANY (?)")) { s =>
          s.setArray(1, c.createArrayOf("text", refs.toArray[AnyRef]))
          s.executeUpdate(): Unit
        }
      }

  def deleteAll(datasetId: String): Int = withConnection { c =>
    Using.resource(c.prepareStatement("DELETE FROM satisfactory_blobs WHERE dataset_id = ?")) { s =>
      s.setString(1, datasetId)
      s.executeUpdate()
    }
  }

  override def close(): Unit = dataSource.close()

object PostgresBlobStore:
  /** The journal's own connection settings: in a deployment the platform's, in tests the testkit's. */
  def fromConfig(config: Config): PostgresBlobStore =
    val c  = config.getConfig("pekko.persistence.r2dbc.connection-factory")
    val hc = HikariConfig()
    hc.setJdbcUrl(s"jdbc:postgresql://${c.getString("host")}:${c.getInt("port")}/${c.getString("database")}")
    hc.setUsername(c.getString("user"))
    hc.setPassword(c.getString("password"))
    hc.setMaximumPoolSize(8)
    hc.setPoolName("satisfactory-blobs")
    // The platform's database speaks TLS and authenticates the service by certificate, not password:
    // the same `ssl` block ankka's DatabaseTls applies to the journal's pool, applied to this one.
    DatabaseTls.settings(c).foreach { ssl =>
      hc.addDataSourceProperty("ssl", "true")
      hc.addDataSourceProperty("sslmode", ssl.mode)
      hc.addDataSourceProperty("sslfactory", classOf[DatabaseSslSocketFactory].getName)
      ssl.rootCert.foreach(hc.addDataSourceProperty("sslrootcert", _))
      ssl.clientCertificate.foreach { (cert, key) =>
        hc.addDataSourceProperty("sslcert", cert)
        hc.addDataSourceProperty("sslkey", key)
      }
    }
    PostgresBlobStore(HikariDataSource(hc))

/**
 * Builds the blob store when the service starts, from the service's own configuration, and
 * publishes it to the components that cannot take constructor arguments (timers, consumers).
 */
final class BlobStoreExtension(kind: String) extends RuntimeExtension:
  @volatile private var current: Option[BlobStore] = None

  def name: String = "blob-store"

  def store: BlobStore = current.getOrElse(throw IllegalStateException("the blob store has not started"))

  def start(service: AnkkaService): Unit =
    val store = kind match
      case "memory" => InMemoryBlobStore()
      case _        => PostgresBlobStore.fromConfig(service.system.settings.config)
    current = Some(store)
    ApiServices.blobs = store

  override def stop(): Unit =
    current.foreach(_.close())
    current = None

  override def readiness: Option[() => Boolean] = Some(() => current.isDefined)

/**
 * What components that are constructed by the runtime (timed actions, consumers) need and cannot be
 * handed: set once at startup. One service per JVM, which is what a deployment and every suite has.
 */
object ApiServices:
  @volatile var blobs: BlobStore = InMemoryBlobStore()
  @volatile var timers: Option[com.thinkmorestupidless.ankka.sdk.TimerScheduler] = None
  @volatile var settings: Option[satisfactory.api.Settings] = None
