package satisfactory.api

import com.sun.net.httpserver.HttpServer

import java.net.InetSocketAddress
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue, CopyOnWriteArrayList}
import scala.jdk.CollectionConverters.*

/** One request a receiver got. */
final case class Received(path: String, headers: Map[String, String], body: Array[Byte]):
  def text: String = String(body, "UTF-8")

/**
 * A webhook receiver for tests: `/hook/<name>` records every request and answers from a script per
 * name — a status, or `Hang` (sleep past the sender's timeout) — then 200 once the script runs out.
 */
final class Receiver:
  sealed trait Behaviour
  final case class Status(code: Int) extends Behaviour
  case object Hang                   extends Behaviour

  val received = CopyOnWriteArrayList[Received]()
  private val scripts = ConcurrentHashMap[String, ConcurrentLinkedQueue[Behaviour]]()
  @volatile var always: Map[String, Int] = Map.empty

  private val server =
    val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    s.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
    s.createContext(
      "/hook/",
      exchange =>
        val path    = exchange.getRequestURI.getPath
        val name    = path.stripPrefix("/hook/")
        val headers = exchange.getRequestHeaders.asScala.map((k, v) => k.toLowerCase -> v.asScala.headOption.getOrElse("")).toMap
        val body    = exchange.getRequestBody.readAllBytes()
        received.add(Received(path, headers, body)): Unit
        val behaviour = Option(scripts.get(name)).flatMap(q => Option(q.poll())).orElse(always.get(name).map(Status(_)))
        behaviour match
          case Some(Hang)         => Thread.sleep(4000); exchange.sendResponseHeaders(200, -1)
          case Some(Status(code)) => exchange.sendResponseHeaders(code, -1)
          case None               => exchange.sendResponseHeaders(200, -1)
        exchange.close()
    )
    s.start()
    s

  def url(name: String): String = s"http://127.0.0.1:${server.getAddress.getPort}/hook/$name"

  def script(name: String, behaviours: Behaviour*): Unit =
    scripts.put(name, ConcurrentLinkedQueue(behaviours.asJava)): Unit

  def to(name: String): List[Received] = received.asScala.filter(_.path == s"/hook/$name").toList

  def stop(): Unit = server.stop(0)
