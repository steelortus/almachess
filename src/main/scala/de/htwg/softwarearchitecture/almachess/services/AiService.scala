package de.htwg.softwarearchitecture.almachess.services

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import de.htwg.softwarearchitecture.almachess.ai.ChessAI
import de.htwg.softwarearchitecture.almachess.api.*
import de.htwg.softwarearchitecture.almachess.api.JsonFormats.given
import de.htwg.softwarearchitecture.almachess.clients.AiClient
import de.htwg.softwarearchitecture.almachess.parser.FenParser

import scala.util.{Failure, Success}

object AiService:

  val route: Route =
    concat(
      path("health") { get { complete(HealthResponse("ok")) } },
      path("ai" / "bestmove") {
        post {
          entity(as[BestMoveRequest]) { req =>
            FenParser.parse(req.fen) match
              case Left(err)    => complete(StatusCodes.BadRequest -> BestMoveResponse(None, Some(err)))
              case Right(state) =>
                val depth = req.depth.getOrElse(3).max(1).min(6)
                ChessAI.bestMove(state, depth) match
                  case None    => complete(BestMoveResponse(None, Some("no legal moves")))
                  case Some(m) => complete(BestMoveResponse(Some(AiClient.moveToUci(m)), None))
          }
        }
      }
    )

  def main(args: Array[String]): Unit =
    given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "ai-service")
    given ec: scala.concurrent.ExecutionContext = system.executionContext
    val host = sys.env.getOrElse("AI_HOST", "0.0.0.0")
    val port = sys.env.get("AI_PORT").flatMap(_.toIntOption)
      .orElse(args.headOption.flatMap(_.toIntOption))
      .getOrElse(8082)
    Http().newServerAt(host, port).bind(route).onComplete {
      case Success(b)  => println(s"AiService online at http://${b.localAddress.getHostString}:${b.localAddress.getPort}/")
      case Failure(ex) => System.err.println(s"AiService failed to bind: ${ex.getMessage}"); system.terminate()
    }
