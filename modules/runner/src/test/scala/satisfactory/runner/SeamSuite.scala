package satisfactory.runner

/**
 * The seam rule (DESIGN.md §11.2b): the runner has no Pekko and no HTTP types, so a dedicated pod can
 * run it without an actor system. Its test classpath is exactly its own dependencies.
 */
class SeamSuite extends munit.FunSuite:
  private def absent(className: String): Boolean =
    try
      Class.forName(className, false, getClass.getClassLoader)
      false
    catch case _: ClassNotFoundException => true

  test("no Pekko, no ankka, no HTTP server types on the runner's classpath") {
    assert(absent("org.apache.pekko.actor.ActorSystem"))
    assert(absent("org.apache.pekko.http.scaladsl.Http"))
    assert(absent("com.thinkmorestupidless.ankka.runtime.Ankka"))
  }
