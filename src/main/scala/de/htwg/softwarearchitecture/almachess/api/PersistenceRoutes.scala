package de.htwg.softwarearchitecture.almachess.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import de.htwg.softwarearchitecture.almachess.control.Controller
import de.htwg.softwarearchitecture.almachess.model.PieceType
import de.htwg.softwarearchitecture.almachess.persistence.{GameRepository, GameSaveDto}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

import JsonFormats.given

final class PersistenceRoutes(
    controller: Controller,
    repository: Option[GameRepository],
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

  private def snapshot(gameId: String): GameSaveDto =
    sharedLock.synchronized {
      GameSaveDto(
        gameId     = gameId,
        currentFen = controller.toFen,
        pgn        = controller.exportPgn(),
        moves      = historyUci,
        status     = controller.state.status
      )
    }

  private def disabled: Route =
    complete(StatusCodes.ServiceUnavailable -> ErrorResponse(
      "persistence disabled (set ALMACHESS_DB=postgres or ALMACHESS_DB=mongo)"
    ))

  private def withRepo(action: GameRepository => Route): Route =
    repository match
      case Some(repo) => action(repo)
      case None       => disabled

  private def toResponse(dto: GameSaveDto): PersistenceGameDto =
    PersistenceGameDto(dto.gameId, dto.currentFen, dto.pgn, dto.moves, dto.status, dto.savedAt)

  private val statusRoute: Route =
    path("api" / "persistence" / "status") {
      get {
        val resp = repository match
          case Some(repo) => PersistenceStatusResponse(enabled = true,  backend = repo.name)
          case None       => PersistenceStatusResponse(enabled = false, backend = "none")
        complete(resp)
      }
    }

  private val gamesRoutes: Route =
    pathPrefix("api" / "persistence" / "games") {
      concat(
        pathEndOrSingleSlash {
          get {
            withRepo { repo =>
              onComplete(repo.list()) {
                case Success(entries) =>
                  complete(PersistenceListResponse(entries.map(e => PersistenceListEntry(e.gameId, e.savedAt))))
                case Failure(ex) =>
                  complete(StatusCodes.InternalServerError -> ErrorResponse(s"list failed: ${ex.getMessage}"))
              }
            }
          }
        },
        path(Segment) { gameId =>
          concat(
            post {
              withRepo { repo =>
                val dto = snapshot(gameId)
                onComplete(repo.save(dto)) {
                  case Success(_)  => complete(toResponse(dto))
                  case Failure(ex) => complete(StatusCodes.InternalServerError -> ErrorResponse(s"save failed: ${ex.getMessage}"))
                }
              }
            },
            get {
              withRepo { repo =>
                onComplete(repo.load(gameId)) {
                  case Success(Some(dto)) =>
                    sharedLock.synchronized {
                      controller.loadFen(dto.currentFen) match
                        case Right(_)  => complete(toResponse(dto))
                        case Left(err) => complete(StatusCodes.UnprocessableEntity -> ErrorResponse(s"loaded but FEN rejected: $err"))
                    }
                  case Success(None) =>
                    complete(StatusCodes.NotFound -> ErrorResponse(s"game not found: $gameId"))
                  case Failure(ex) =>
                    complete(StatusCodes.InternalServerError -> ErrorResponse(s"load failed: ${ex.getMessage}"))
                }
              }
            },
            delete {
              withRepo { repo =>
                onComplete(repo.delete(gameId)) {
                  case Success(_)  => complete(SuccessResponse(s"deleted $gameId"))
                  case Failure(ex) => complete(StatusCodes.InternalServerError -> ErrorResponse(s"delete failed: ${ex.getMessage}"))
                }
              }
            }
          )
        }
      )
    }

  val all: Route = concat(statusRoute, gamesRoutes)
