package de.htwg.softwarearchitecture.almachess.api

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import de.htwg.softwarearchitecture.almachess.clients.{AiClient, NotationClient}
import de.htwg.softwarearchitecture.almachess.control.Controller

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object Server:

  def start(
      controller: Controller,
      host: String = "0.0.0.0",
      port: Int = 8080,
      aiServiceUrl: Option[String] = None,
      notationServiceUrl: Option[String] = None
  ): Future[Http.ServerBinding] =
    given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "almachess-api")
    given ec: ExecutionContext = system.executionContext

    val aiClient: AiClient = aiServiceUrl match
      case Some(url) => println(s"AI client → remote $url");      new AiClient.Http(url)
      case None      => println("AI client → local");             new AiClient.Local(ec)

    val notationClient: NotationClient = notationServiceUrl match
      case Some(url) => println(s"Notation client → remote $url"); new NotationClient.Http(url)
      case None      => println("Notation client → local");        new NotationClient.Local(ec)

    val routes = new Routes(controller, aiClient, notationClient)
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

  def main(args: Array[String]): Unit =
    val controller = new Controller()
    val port = sys.env.get("ALMACHESS_PORT").flatMap(_.toIntOption)
      .orElse(args.headOption.flatMap(_.toIntOption))
      .getOrElse(8080)
    val host = sys.env.getOrElse("ALMACHESS_HOST", "0.0.0.0")
    val aiUrl       = sys.env.get("ALMACHESS_AI_URL").filter(_.nonEmpty)
    val notationUrl = sys.env.get("ALMACHESS_NOTATION_URL").filter(_.nonEmpty)
    start(controller, host, port, aiUrl, notationUrl)
