package satisfactory.protocol

/** The public surface never depends on ankka (DESIGN.md §12). */
class SeamSuite extends munit.FunSuite:
  test("no ankka and no Pekko on the protocol's classpath") {
    List("com.thinkmorestupidless.ankka.core.Serializer", "org.apache.pekko.actor.ActorSystem").foreach { name =>
      val present =
        try
          Class.forName(name, false, getClass.getClassLoader)
          true
        catch case _: ClassNotFoundException => false
      assert(!present, s"$name is on the protocol's classpath")
    }
  }
