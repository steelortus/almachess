package de.htwg.softwarearchitecture.almachess.services

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import de.htwg.softwarearchitecture.almachess.api.*
import de.htwg.softwarearchitecture.almachess.api.JsonFormats.given
import de.htwg.softwarearchitecture.almachess.parser.{FenParser, PgnParser}

import scala.util.{Failure, Success}

object NotationService:

  val route: Route =
    concat(
      path("health") { get { complete(HealthResponse("ok")) } },
      pathPrefix("notation") {
        concat(
          path("fen" / "validate") {
            post {
              entity(as[FenValidateRequest]) { req =>
                FenParser.parse(req.fen) match
                  case Right(state) => complete(FenValidateResponse(true, Some(state.toFen), None))
                  case Left(err)    => complete(FenValidateResponse(false, None, Some(err)))
              }
            }
          },
          path("pgn" / "parse") {
            post {
              entity(as[PgnParseRequest]) { req =>
                PgnParser.parse(req.pgn) match
                  case Right((tags, moves)) => complete(PgnParseResponse(tags, moves))
                  case Left(err)            => complete(StatusCodes.UnprocessableEntity -> ErrorResponse(err))
              }
            }
          }
        )
      }
    )

  def main(args: Array[String]): Unit =
    given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "notation-service")
    given ec: scala.concurrent.ExecutionContext = system.executionContext
    val host = sys.env.getOrElse("NOTATION_HOST", "0.0.0.0")
    val port = sys.env.get("NOTATION_PORT").flatMap(_.toIntOption)
      .orElse(args.headOption.flatMap(_.toIntOption))
      .getOrElse(8081)
    Http().newServerAt(host, port).bind(route).onComplete {
      case Success(b)  => println(s"NotationService online at http://${b.localAddress.getHostString}:${b.localAddress.getPort}/")
      case Failure(ex) => System.err.println(s"NotationService failed to bind: ${ex.getMessage}"); system.terminate()
    }
