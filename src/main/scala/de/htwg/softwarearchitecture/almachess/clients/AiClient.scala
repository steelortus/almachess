package de.htwg.softwarearchitecture.almachess.clients

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.*
import akka.http.scaladsl.unmarshalling.Unmarshal
import de.htwg.softwarearchitecture.almachess.ai.ChessAI
import de.htwg.softwarearchitecture.almachess.api.*
import de.htwg.softwarearchitecture.almachess.api.JsonFormats.given
import de.htwg.softwarearchitecture.almachess.model.{GameState, Move, PieceType}
import de.htwg.softwarearchitecture.almachess.parser.FenParser

import scala.concurrent.{ExecutionContext, Future}

trait AiClient:
  /** Returns best move in UCI notation for the given FEN. */
  def bestMove(
      fen: String,
      depth: Int,
      movetime: Option[Int] = None,
      skill: Option[Int] = None
  ): Future[Either[String, String]]

object AiClient:

  final class Local(ec: ExecutionContext) extends AiClient:
    given ExecutionContext = ec
    def bestMove(
        fen: String,
        depth: Int,
        movetime: Option[Int] = None,
        skill: Option[Int] = None
    ): Future[Either[String, String]] =
      Future {
        FenParser.parse(fen).flatMap { state =>
          ChessAI.bestMove(state, depth) match
            case None    => Left("no move found")
            case Some(m) => Right(moveToUci(m))
        }
      }

  final class Http(baseUrl: String)(using system: ActorSystem[?]) extends AiClient:
    given ExecutionContext = system.executionContext
    def bestMove(
        fen: String,
        depth: Int,
        movetime: Option[Int] = None,
        skill: Option[Int] = None
    ): Future[Either[String, String]] =
      val uri = s"${baseUrl.stripSuffix("/")}/ai/bestmove"
      for
        entity <- Marshal(BestMoveRequest(fen, Some(depth), movetime, skill)).to[RequestEntity]
        resp   <- akka.http.scaladsl.Http()(system).singleRequest(
                    HttpRequest(HttpMethods.POST, uri, entity = entity))
        body   <- Unmarshal(resp.entity).to[BestMoveResponse]
      yield body.move match
        case Some(m) => Right(m)
        case None    => Left(body.error.getOrElse("ai service returned no move"))

  def moveToUci(m: Move): String =
    val promo = m.promotion.map {
      case PieceType.Queen  => "q"
      case PieceType.Rook   => "r"
      case PieceType.Bishop => "b"
      case PieceType.Knight => "n"
      case _                => ""
    }.getOrElse("")
    m.from.toAlgebraic + m.to.toAlgebraic + promo
