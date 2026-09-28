package satisfactory.api.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.{JWSVerificationKeySelector, SecurityContext}
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.{DefaultJWTClaimsVerifier, DefaultJWTProcessor}
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Principal, RequestContext}

import java.net.URI
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * Tokens from the installation's Keycloak, verified offline against its published keys (research R8,
 * the pattern ankka's control plane uses): the signature, the issuer, the expiry. The subject is the
 * caller; Keycloak's realm roles ride in `realm_access.roles`.
 */
final class TokenVerifier(issuer: String, jwksUrl: String):
  private val processor = DefaultJWTProcessor[SecurityContext]()
  processor.setJWSKeySelector(
    JWSVerificationKeySelector[SecurityContext](
      Set(JWSAlgorithm.RS256, JWSAlgorithm.ES256).asJava,
      JWKSourceBuilder.create[SecurityContext](URI(jwksUrl).toURL).build()
    )
  )
  processor.setJWTClaimsSetVerifier(
    DefaultJWTClaimsVerifier[SecurityContext](JWTClaimsSet.Builder().issuer(issuer).build(), Set("sub", "exp").asJava)
  )

  def verify(token: String): Either[String, Principal] =
    try
      val claims = processor.process(token, null)
      val roles = Option(claims.getJSONObjectClaim("realm_access"))
        .flatMap(access => Option(access.get("roles")))
        .collect { case list: java.util.List[?] => list.asScala.map(_.toString).toSet }
        .getOrElse(Set.empty)
      Right(
        Principal(
          subject = claims.getSubject,
          name = Option(claims.getStringClaim("preferred_username")),
          email = Option(claims.getStringClaim("email")),
          roles = roles
        )
      )
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))

/** The platform API's ACL: a bearer token from the identity provider, or nothing. */
object PlatformAuth:
  def acl(verifier: Option[TokenVerifier]): Acl = Acl.Authenticate(request => decide(verifier, request))

  private def decide(verifier: Option[TokenVerifier], request: RequestContext): AuthDecision =
    verifier match
      case None => AuthDecision.Unavailable("the platform API needs an identity provider (satisfactory.auth.issuer)")
      case Some(v) =>
        request.header("Authorization").map(_.trim).filter(_.regionMatches(true, 0, "Bearer ", 0, 7)) match
          case None => AuthDecision.Unauthenticated("realm=\"satisfactory\"")
          case Some(header) =>
            v.verify(header.drop(7).trim) match
              case Right(principal) => AuthDecision.Allow(principal)
              case Left(reason)     => AuthDecision.Unauthenticated(s"realm=\"satisfactory\", error=\"invalid_token\", error_description=\"$reason\"")
