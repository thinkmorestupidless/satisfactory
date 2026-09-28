import sbt.*

/** Single source of truth for every external version in the build. */
object Dependencies {

  object V {
    val scala = "3.9.0"

    /** The platform. Also the `runtime` the deploy descriptors declare. */
    val ankka = "0.7.1+28-ed931dc0+20260928-1802-SNAPSHOT"

    /** Timefold Solver Community (Apache-2.0). Score analysis is Enterprise-only; see research R2. */
    val timefold = "2.7.0"

    val jsoniter = "2.40.1"

    /**
     * Dataset JSON is Jackson 2, aligned with the databind ankka's runtime resolves, so the models, the
     * runner and both services agree on one tree type. Timefold core has no Jackson dependency of its own.
     */
    val jackson    = "2.21.4"
    val jsonSchema = "1.5.9"

    val nimbusJoseJwt  = "10.9.1"
    val hikari         = "7.1.0"
    val postgres       = "42.7.13"
    val logback        = "1.6.3"
    val munit          = "1.3.6"
    val testcontainers = "1.21.4"
  }

  private def ankka(m: String) = "com.thinkmorestupidless" %% s"ankka-$m" % V.ankka

  val ankkaSdk     = ankka("sdk")
  val ankkaRuntime = ankka("runtime")
  val ankkaHttp    = ankka("http")
  val ankkaAgent   = ankka("agent")
  val ankkaTestkit = ankka("testkit")

  val timefoldCore = "ai.timefold.solver" % "timefold-solver-core" % V.timefold

  val jsoniterCore   = "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core"   % V.jsoniter
  val jsoniterMacros = "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % V.jsoniter

  val jacksonDatabind = "com.fasterxml.jackson.core"     % "jackson-databind"        % V.jackson
  val jacksonJsr310   = "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % V.jackson
  val jsonSchema      = "com.networknt"                  % "json-schema-validator"   % V.jsonSchema

  val nimbusJoseJwt = "com.nimbusds"   % "nimbus-jose-jwt" % V.nimbusJoseJwt
  val hikari        = "com.zaxxer"     % "HikariCP"        % V.hikari
  val postgres      = "org.postgresql" % "postgresql"      % V.postgres
  val logback       = "ch.qos.logback" % "logback-classic" % V.logback

  val munit          = "org.scalameta"     %% "munit"      % V.munit
  val testcontainers = "org.testcontainers" % "postgresql" % V.testcontainers
}
