package de.htwg.softwarearchitecture.almachess.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import de.htwg.softwarearchitecture.almachess.control.Controller
import de.htwg.softwarearchitecture.almachess.model.{GameState, PieceType}
import de.htwg.softwarearchitecture.almachess.persistence.{GameSaveDto, LiveGameStore}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

import JsonFormats.given

final class LiveGameRoutes(
    controller: Controller,
    store: Option[LiveGameStore],
    sharedLock: Object
)(using ec: ExecutionContext):

  private def historyUci: List[String] =
    controller.moveHistory.map { m =>
      val promo = m.promotion.map {
        case PieceType.Queen  => "q"
        case PieceType.Rook   => "r"
        case PieceType.Bishop => "b"
        case PieceType.Knight => "n"
        case _                => ""
      }.getOrElse("")
      m.from.toAlgebraic + m.to.toAlgebraic + promo
    }

  private def snapshot(sessionId: String): GameSaveDto =
    sharedLock.synchronized {
      GameSaveDto(
        gameId     = sessionId,
        currentFen = controller.toFen,
        pgn        = controller.exportPgn(),
        moves      = historyUci,
        status     = controller.state.status,
        savedAt    = System.currentTimeMillis(),
        initialFen = controller.initialFen
      )
    }

  private def toResponse(dto: GameSaveDto): LiveGameDto =
    LiveGameDto(dto.gameId, dto.currentFen, dto.pgn, dto.moves, dto.status, dto.savedAt, dto.initialFen)

  private def restoreFromDto(dto: GameSaveDto): Either[String, Unit] =
    val startFen =
      if dto.initialFen.nonEmpty then dto.initialFen
      else GameState.initial.toFen
    controller.loadSnapshot(startFen, dto.moves) match
      case Right(_)  => Right(())
      case Left(err) =>
        controller.loadFen(dto.currentFen).map(_ => ()).left.map(_ => err)

  private def disabled: Route =
    complete(StatusCodes.ServiceUnavailable -> ErrorResponse(
      "live store disabled (set REDIS_ENABLED=true with REDIS_HOST/REDIS_PORT)"
    ))

  private def withStore(action: LiveGameStore => Route): Route =
    store match
      case Some(s) => action(s)
      case None    => disabled

  private val statusRoute: Route =
    path("api" / "live" / "status") {
      get {
        val resp = store match
          case Some(s) => LiveStatusResponse(enabled = true,  backend = s.name, ttlSeconds = s.ttlSeconds)
          case None    => LiveStatusResponse(enabled = false, backend = "none", ttlSeconds = 0)
        complete(resp)
      }
    }

  private val sessionRoutes: Route =
    pathPrefix("api" / "live" / Segment) { sessionId =>
      concat(
        post {
          withStore { s =>
            val dto = snapshot(sessionId)
            onComplete(s.save(sessionId, dto)) {
              case Success(_)  => complete(toResponse(dto))
              case Failure(ex) => complete(StatusCodes.InternalServerError -> ErrorResponse(s"live save failed: ${ex.getMessage}"))
            }
          }
        },
        get {
          withStore { s =>
            onComplete(s.load(sessionId)) {
              case Success(Some(dto)) =>
                sharedLock.synchronized {
                  restoreFromDto(dto) match
                    case Right(_)  => complete(toResponse(dto))
                    case Left(err) => complete(StatusCodes.UnprocessableEntity -> ErrorResponse(s"snapshot rejected: $err"))
                }
              case Success(None) =>
                complete(StatusCodes.NotFound -> ErrorResponse(s"no live state for session: $sessionId"))
              case Failure(ex) =>
                complete(StatusCodes.InternalServerError -> ErrorResponse(s"live load failed: ${ex.getMessage}"))
            }
          }
        },
        delete {
          withStore { s =>
            onComplete(s.delete(sessionId)) {
              case Success(_)  => complete(SuccessResponse(s"deleted live state $sessionId"))
              case Failure(ex) => complete(StatusCodes.InternalServerError -> ErrorResponse(s"live delete failed: ${ex.getMessage}"))
            }
          }
        }
      )
    }

  val all: Route = concat(statusRoute, sessionRoutes)
