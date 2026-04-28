package de.htwg.softwarearchitecture.almachess.api

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import de.htwg.softwarearchitecture.almachess.clients.{AiClient, NotationClient}
import de.htwg.softwarearchitecture.almachess.control.Controller
import de.htwg.softwarearchitecture.almachess.persistence.{GameRepository, LiveGameStore, MongoGameRepository, PostgresGameRepository, RedisLiveGameStore}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object Server:

  def start(
      controller: Controller,
      host: String = "0.0.0.0",
      port: Int = 8080,
      aiServiceUrl: Option[String] = None,
      notationServiceUrl: Option[String] = None,
      repository: Option[GameRepository] = None,
      liveStore: Option[LiveGameStore] = None
  ): Future[Http.ServerBinding] =
    given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "almachess-api")
    given ec: ExecutionContext = system.executionContext

    val aiClient: AiClient = aiServiceUrl match
      case Some(url) => println(s"AI client → remote $url");      new AiClient.Http(url)
      case None      => println("AI client → local");             new AiClient.Local(ec)

    val notationClient: NotationClient = notationServiceUrl match
      case Some(url) => println(s"Notation client → remote $url"); new NotationClient.Http(url)
      case None      => println("Notation client → local");        new NotationClient.Local(ec)

    val routes = new Routes(controller, aiClient, notationClient, repository, liveStore)
    val binding = Http().newServerAt(host, port).bind(routes.all)
    binding.onComplete {
      case Success(b) =>
        val addr = b.localAddress
        println(s"AlmaChess API online at http://${addr.getHostString}:${addr.getPort}/")
      case Failure(ex) =>
        System.err.println(s"Failed to bind API server: ${ex.getMessage}")
        system.terminate()
    }
    binding

  private def buildRepository()(using ec: ExecutionContext): Option[GameRepository] =
    sys.env.get("ALMACHESS_DB").map(_.trim.toLowerCase).filter(_.nonEmpty) match
      case None | Some("none") =>
        println("Persistence disabled (ALMACHESS_DB not set)")
        None
      case Some("postgres") =>
        val host = sys.env.getOrElse("POSTGRES_HOST", "localhost")
        val port = sys.env.getOrElse("POSTGRES_PORT", "5432")
        val dbn  = sys.env.getOrElse("POSTGRES_DB",   "almachess")
        val user = sys.env.getOrElse("POSTGRES_USER", "almachess")
        val pass = sys.env.getOrElse("POSTGRES_PASSWORD", "almachess")
        val url  = s"jdbc:postgresql://$host:$port/$dbn"
        println(s"Persistence → Postgres at $url")
        Some(new PostgresGameRepository(url, user, pass))
      case Some("mongo") =>
        val uri = sys.env.getOrElse("MONGO_URI", "mongodb://localhost:27017")
        val dbn = sys.env.getOrElse("MONGO_DB",  "almachess")
        println(s"Persistence → Mongo at $uri/$dbn")
        Some(new MongoGameRepository(uri, dbn))
      case Some(other) =>
        System.err.println(s"Unknown ALMACHESS_DB=$other — persistence disabled")
        None

  private def buildLiveStore()(using ec: ExecutionContext): Option[LiveGameStore] =
    val enabled = sys.env.get("REDIS_ENABLED").map(_.trim.toLowerCase) match
      case Some("false") | Some("0") | Some("no") => false
      case Some(_)                                => true
      case None                                   => sys.env.contains("REDIS_HOST")
    if !enabled then
      println("Live store disabled (REDIS_ENABLED not set)")
      None
    else
      val host = sys.env.getOrElse("REDIS_HOST", "localhost")
      val port = sys.env.get("REDIS_PORT").flatMap(_.toIntOption).getOrElse(6379)
      val ttl  = sys.env.get("REDIS_TTL_SECONDS").flatMap(_.toIntOption).getOrElse(1800)
      try
        println(s"Live store → Redis at $host:$port (TTL ${ttl}s)")
        Some(new RedisLiveGameStore(host, port, ttl))
      catch
        case ex: Throwable =>
          System.err.println(s"Failed to connect Redis at $host:$port — live store disabled: ${ex.getMessage}")
          None

  def main(args: Array[String]): Unit =
    val controller = new Controller()
    val port = sys.env.get("ALMACHESS_PORT").flatMap(_.toIntOption)
      .orElse(args.headOption.flatMap(_.toIntOption))
      .getOrElse(8080)
    val host = sys.env.getOrElse("ALMACHESS_HOST", "0.0.0.0")
    val aiUrl       = sys.env.get("ALMACHESS_AI_URL").filter(_.nonEmpty)
    val notationUrl = sys.env.get("ALMACHESS_NOTATION_URL").filter(_.nonEmpty)

    given ec: ExecutionContext = scala.concurrent.ExecutionContext.global
    val repository = buildRepository()
    val liveStore  = buildLiveStore()

    start(controller, host, port, aiUrl, notationUrl, repository, liveStore)
