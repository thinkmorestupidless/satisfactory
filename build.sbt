import Dependencies.*
import com.typesafe.sbt.packager.docker.DockerPlugin
import com.typesafe.sbt.packager.archetypes.JavaAppPackaging

ThisBuild / scalaVersion := V.scala
ThisBuild / organization := "com.thinkmorestupidless"
// No `ThisBuild / version`: sbt-dynver derives it from the nearest tag, as ankka's build does.
ThisBuild / homepage     := Some(url("https://github.com/thinkmorestupidless/satisfactory"))
ThisBuild / licenses     := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / developers := List(
  Developer("thinkmorestupidless", "Trevor Burton-McCreadie", "", url("https://github.com/thinkmorestupidless"))
)
ThisBuild / scmInfo := Some(
  ScmInfo(url("https://github.com/thinkmorestupidless/satisfactory"), "scm:git@github.com:thinkmorestupidless/satisfactory.git")
)

/**
 * Suites that start Postgres (every `AnkkaTestKit` suite) contend for Docker and CPU when they
 * overlap; ankka measured 147s in parallel against 6s alone. One test suite at a time.
 */
Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)

lazy val commonScala = Seq(
  scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all", "-Wvalue-discard"),
  javacOptions ++= Seq("--release", "21"),
  Test / fork               := true,
  Test / parallelExecution  := false,
  Test / javaOptions ++= Seq("-Xmx2g"),
  libraryDependencies += munit % Test
)

/** A Java module: no Scala suffix on the artifact; tests are munit suites in Scala. */
lazy val javaModule = Seq(
  crossPaths := false,
  javacOptions ++= Seq("--release", "21", "-parameters", "-Xlint:unchecked"),
  Compile / doc / javacOptions := Seq("--release", "21"),
  Test / fork              := true,
  Test / parallelExecution := false,
  libraryDependencies += munit % Test
)

// ── The public surface: no ankka ──────────────────────────────────────────────────────────────

/** Wire types and codecs: the public API and the runner protocol. jsoniter only. */
lazy val protocol = project
  .in(file("modules/protocol"))
  .settings(commonScala)
  .settings(
    name := "satisfactory-protocol",
    libraryDependencies ++= Seq(jsoniterCore, jsoniterMacros)
  )

/** The model SPI, in Java: the only thing a model sees. */
lazy val modelSpi = project
  .in(file("modules/model-spi"))
  .settings(javaModule)
  .settings(
    name := "satisfactory-model-spi",
    libraryDependencies ++= Seq(timefoldCore, jacksonDatabind, jacksonJsr310, jsonSchema)
  )

lazy val employeeScheduling = project
  .in(file("modules/models/employee-scheduling"))
  .dependsOn(modelSpi)
  .settings(javaModule)
  .settings(name := "satisfactory-model-employee-scheduling", publish / skip := true)

lazy val vehicleRouting = project
  .in(file("modules/models/vehicle-routing"))
  .dependsOn(modelSpi)
  .settings(javaModule)
  .settings(name := "satisfactory-model-vehicle-routing", publish / skip := true)

/** The runner: claim, solve, throttle, report. No Pekko and no HTTP types (DESIGN.md §11.2b). */
lazy val runner = project
  .in(file("modules/runner"))
  .dependsOn(protocol, modelSpi, employeeScheduling % "test->compile")
  .settings(commonScala)
  .settings(name := "satisfactory-runner", publish / skip := true)

/** satisfactory-client: plain HTTP + SSE over the JDK client. */
lazy val client = project
  .in(file("modules/client"))
  .dependsOn(protocol, api % "test->test")
  .settings(commonScala)
  .settings(name := "satisfactory-client")

/** Phase 0: one model driven through SolverManager with the throttle. */
lazy val spike = project
  .in(file("modules/spike"))
  .dependsOn(runner, employeeScheduling)
  .settings(commonScala)
  .settings(name := "satisfactory-spike", publish / skip := true, run / fork := true)

// ── The two ankka services ────────────────────────────────────────────────────────────────────

lazy val serviceImage = Seq(
  dockerBaseImage    := "eclipse-temurin:21-jre",
  dockerUpdateLatest := true,
  Docker / version   := version.value.replace('+', '-'),
  Docker / dockerRepository := sys.env.get("DOCKER_REPOSITORY"),
  publish / skip     := true
)

lazy val api = project
  .in(file("modules/api"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .dependsOn(protocol, runner, employeeScheduling, vehicleRouting)
  .settings(commonScala, serviceImage)
  .settings(
    name                 := "satisfactory-api",
    Docker / packageName := "satisfactory-api",
    dockerExposedPorts   := Seq(9000),
    libraryDependencies ++= Seq(
      ankkaSdk,
      ankkaRuntime,
      ankkaHttp,
      nimbusJoseJwt,
      hikari,
      postgres,
      logback,
      ankkaTestkit % Test
    ),
    run / fork := true
  )

lazy val solver = project
  .in(file("modules/solver"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .dependsOn(protocol, runner, employeeScheduling, vehicleRouting, api % "test->test")
  .settings(commonScala, serviceImage)
  .settings(
    name                 := "satisfactory-solver",
    Docker / packageName := "satisfactory-solver",
    dockerExposedPorts   := Seq.empty,
    // One core is left for cluster heartbeats (slots = cores - 1); the heap is most of the pod.
    Universal / javaOptions ++= Seq("-J-XX:MaxRAMPercentage=70", "-J-XX:+UseParallelGC"),
    libraryDependencies ++= Seq(ankkaSdk, ankkaRuntime, logback, ankkaTestkit % Test),
    run / fork := true
  )

// ── The ankka extension library ───────────────────────────────────────────────────────────────

lazy val ankkaSatisfactory = project
  .in(file("modules/ankka-satisfactory"))
  .dependsOn(client, modelSpi, employeeScheduling % "test->compile")
  .settings(commonScala)
  .settings(
    name := "ankka-satisfactory",
    libraryDependencies ++= Seq(ankkaSdk, ankkaHttp, ankkaAgent, ankkaRuntime, ankkaTestkit % Test)
  )

lazy val root = project
  .in(file("."))
  .aggregate(
    protocol,
    modelSpi,
    employeeScheduling,
    vehicleRouting,
    runner,
    client,
    spike,
    api,
    solver,
    ankkaSatisfactory
  )
  .settings(name := "satisfactory", publish / skip := true)

// ── Local development ─────────────────────────────────────────────────────────────────────────

/**
 * ankka's database schema, taken out of the ankka-runtime jar into target/ddl, which
 * docker-compose.yml mounts as Postgres' init directory — as ankka's own template does.
 */
lazy val schema = taskKey[File]("Writes ankka's database schema from the runtime artifact into target/ddl")
schema := {
  val log = streams.value.log
  val jar = (api / Compile / dependencyClasspath).value
    .map(_.data)
    .find(f => f.getName.startsWith("ankka-runtime_") && f.getName.endsWith(".jar"))
    .getOrElse(sys.error("ankka-runtime is not on the api classpath"))
  val out = target.value / "ddl"
  IO.delete(out)
  IO.createDirectory(out)
  IO.unzip(jar, out, (entry: String) => entry.startsWith("ankka/ddl/") && entry.endsWith(".sql"))
  val files = (out / "ankka" / "ddl").listFiles().toList.sortBy(_.getName)
  files.foreach(f => IO.move(f, out / f.getName))
  IO.delete(out / "ankka")
  log.info(s"schema: ${files.map(_.getName).mkString(", ")} -> $out")
  out
}

/**
 * Renders deploy/api.json and deploy/solver.json with the image tag this build produces, so no
 * descriptor ever names an image by a literal tag.
 */
lazy val deployDescriptors = taskKey[Seq[File]]("Renders the ankka service descriptors into target/deploy")
deployDescriptors := {
  val tag     = version.value.replace('+', '-')
  val repo    = sys.env.get("DOCKER_REPOSITORY").map(_ + "/").getOrElse("")
  val out     = target.value / "deploy"
  IO.createDirectory(out)
  Seq("api", "solver").map { svc =>
    val template = IO.read(baseDirectory.value / "deploy" / s"$svc.json")
    val rendered = template
      .replace("${IMAGE}", s"${repo}satisfactory-$svc:$tag")
      .replace("${ANKKA_VERSION}", V.ankka)
    val file = out / s"$svc.json"
    IO.write(file, rendered)
    file
  }
}
