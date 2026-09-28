package satisfactory.api

import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.{JWKSet, RSAKey}
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import com.sun.net.httpserver.HttpServer

import java.net.InetSocketAddress
import java.util.Date
import scala.jdk.CollectionConverters.*

/**
 * A stand-in identity provider for the platform API's suites: an RSA key, its JWKS served over HTTP
 * on a random port, and tokens shaped like Keycloak's (subject, issuer, `realm_access.roles`).
 */
object TestTokens:
  val issuer = "http://keycloak.test/realms/ankka"

  private val key: RSAKey = RSAKeyGenerator(2048).keyID("test").generate()

  private lazy val server: HttpServer =
    val s    = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val body = JWKSet(key.toPublicJWK).toString.getBytes("UTF-8")
    s.createContext(
      "/jwks",
      exchange =>
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.length.toLong)
        exchange.getResponseBody.write(body)
        exchange.close()
    )
    s.start()
    s

  def jwksUrl: String = s"http://127.0.0.1:${server.getAddress.getPort}/jwks"

  /** The configuration that points the api at this provider. */
  def config: Map[String, Any] = Map("satisfactory.auth.issuer" -> issuer, "satisfactory.auth.jwks-url" -> jwksUrl)

  def token(subject: String, roles: Set[String] = Set.empty, expiresInSeconds: Long = 300, iss: String = issuer): String =
    val claims = JWTClaimsSet.Builder()
      .subject(subject)
      .issuer(iss)
      .expirationTime(Date(System.currentTimeMillis() + expiresInSeconds * 1000))
      .claim("realm_access", Map("roles" -> roles.toList.asJava).asJava)
      .claim("preferred_username", subject)
      .build()
    val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID).build(), claims)
    jwt.sign(RSASSASigner(key))
    jwt.serialize()

  val operator: String = token("op-1", Set("satisfactory-operator"))
  val alice: String    = token("alice")
  val bob: String      = token("bob")
