package satisfactory.api.blobs

import com.thinkmorestupidless.ankka.runtime.DatabaseTls
import org.postgresql.ssl.WrappedFactory

import java.io.ByteArrayInputStream
import java.nio.file.{Files, Paths}
import java.security.KeyStore
import java.security.cert.{CertificateFactory, X509Certificate}
import java.util.Properties
import javax.net.ssl.{KeyManager, SSLContext, TrustManagerFactory}
import scala.jdk.CollectionConverters.*

/**
 * TLS to the service's Postgres for the blob store's JDBC pool, the way ankka's `DatabaseTls` does
 * it for the journal's r2dbc pool: the server is verified against the root the platform mounts, and
 * the client authenticates with the certificate the platform mounts instead of a password. The JDBC
 * driver's own `sslkey` reads a DER key and the mounted one is PEM, so the key goes through ankka's
 * rotating key manager, which also picks up the renewed certificate without a restart.
 *
 * Instantiated by the driver from the `sslfactory` property, with the connection's properties:
 * `sslrootcert`, `sslcert` and `sslkey` name the files, as they would for the driver's own factory.
 */
final class DatabaseSslSocketFactory(info: Properties) extends WrappedFactory:
  private def property(name: String): Option[String] =
    Option(info.getProperty(name)).map(_.trim).filter(_.nonEmpty)

  private val trust = property("sslrootcert").map { root =>
    val store = KeyStore.getInstance(KeyStore.getDefaultType)
    store.load(null, null)
    CertificateFactory
      .getInstance("X.509")
      .generateCertificates(ByteArrayInputStream(Files.readAllBytes(Paths.get(root))))
      .asScala
      .collect { case c: X509Certificate => c }
      .zipWithIndex
      .foreach((c, i) => store.setCertificateEntry(s"root-$i", c))
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(store)
    tmf.getTrustManagers
  }

  private val keys =
    for cert <- property("sslcert"); key <- property("sslkey")
    yield DatabaseTls.RotatingKeyManager(Paths.get(cert), Paths.get(key))

  private val context = SSLContext.getInstance("TLS")
  context.init(keys.map(k => Array[KeyManager](k)).orNull, trust.orNull, null)
  factory = context.getSocketFactory
