package satisfactory.solver

import com.thinkmorestupidless.ankka.runtime.{Ankka, ClusterConfig}

/**
 * The solver service: no components, one extension. It serves nothing (`"http": false`) and pulls
 * work from `api` over the runner protocol.
 */
@main def run(): Unit =
  val config   = ClusterConfig.load()
  val settings = SolverSettings.from(config)
  val runtime  = SolverRuntime(settings, SolverRuntime.catalog, HttpControlChannel(settings.apiUrl, settings.runnerToken))
  val service  = Ankka.service.withExtension(runtime).start("satisfactory-solver", config)
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
