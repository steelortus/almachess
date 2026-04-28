package de.htwg.softwarearchitecture.almachess.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import de.htwg.softwarearchitecture.almachess.clients.{AiClient, NotationClient}
import de.htwg.softwarearchitecture.almachess.control.Controller
import de.htwg.softwarearchitecture.almachess.model.{Color, PieceType}
import de.htwg.softwarearchitecture.almachess.persistence.{GameRepository, LiveGameStore}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

import JsonFormats.given

final class Routes(
    controller: Controller,
    aiClient: AiClient,
    notationClient: NotationClient,
    repository: Option[GameRepository] = None,
    liveStore: Option[LiveGameStore] = None
)(using ec: ExecutionContext):

  // All mutating access to the Controller is serialized through this lock
  // so parallel HTTP requests cannot race on the shared mutable game state.
  private val lock = new Object

  private val persistenceRoutes = new PersistenceRoutes(controller, repository, lock)
  private val liveRoutes        = new LiveGameRoutes(controller, liveStore, lock)

  private def gameState: GameStateResponse = lock.synchronized {
    GameStateResponse(
      fen      = controller.toFen,
      status   = controller.state.status,
      turn     = if controller.state.turn == Color.White then "white" else "black",
      canUndo  = controller.canUndo,
      canRedo  = controller.canRedo,
      gameOver = controller.isGameOver
    )
  }

  private def historyUci: List[String] =
    lock.synchronized {
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
    }

  private def legalMovesUci(fromOpt: Option[String]): Either[String, (Option[String], List[String])] =
    lock.synchronized {
      val state = controller.state
      fromOpt match
        case None =>
          val all = state.allLegalMoves().toList.map(AiClient.moveToUci)
          Right((None, all))
        case Some(sq) =>
          de.htwg.softwarearchitecture.almachess.model.Pos.fromAlgebraic(sq) match
            case None      => Left(s"invalid square: $sq")
            case Some(pos) =>
              val moves = state.legalMovesFrom(pos).toList.map(AiClient.moveToUci)
              Right((Some(sq), moves))
    }

  private val healthRoutes: Route =
    path("health") { get { complete(HealthResponse("ok")) } }

  private val gameRoutes: Route =
    pathPrefix("api" / "game") {
      concat(
        pathEndOrSingleSlash { get { complete(gameState) } },

        path("reset") {
          post {
            lock.synchronized(controller.reset())
            complete(gameState)
          }
        },

        path("history") { get { complete(HistoryResponse(historyUci)) } },

        path("legal-moves") {
          get {
            parameter("from".optional) { fromOpt =>
              legalMovesUci(fromOpt) match
                case Right((from, moves)) => complete(LegalMovesResponse(from, moves))
                case Left(err)            => complete(StatusCodes.BadRequest -> ErrorResponse(err))
            }
          }
        },

        path("move") {
          post {
            entity(as[MoveRequest]) { req =>
              lock.synchronized {
                if controller.isGameOver then
                  complete(StatusCodes.Conflict -> ErrorResponse(s"game is over: ${controller.state.status}"))
                else
                  controller.move(req.from, req.to, req.promotion) match
                    case Right(_)  => complete(gameState)
                    case Left(err) => complete(StatusCodes.UnprocessableEntity -> ErrorResponse(err))
              }
            }
          }
        },

        path("ai-move") {
          post {
            entity(as[AiMoveRequest]) { req =>
              val depth = req.depth.getOrElse(controller.currentAiDepth).max(1).min(6)
              val fenSnapshot = lock.synchronized {
                if controller.isGameOver then Left(s"game is over: ${controller.state.status}")
                else Right(controller.toFen)
              }
              fenSnapshot match
                case Left(err) =>
                  complete(StatusCodes.Conflict -> ErrorResponse(err))
                case Right(fen) =>
                  onComplete(aiClient.bestMove(fen, depth)) {
                    case Success(Right(uci)) =>
                      lock.synchronized {
                        if controller.toFen != fen then
                          complete(StatusCodes.Conflict -> ErrorResponse("state changed during ai computation"))
                        else
                          controller.move(uci) match
                            case Right(_)  => complete(AiMoveResponse(uci, gameState))
                            case Left(err) => complete(StatusCodes.UnprocessableEntity -> ErrorResponse(err))
                      }
                    case Success(Left(err)) =>
                      complete(StatusCodes.UnprocessableEntity -> ErrorResponse(err))
                    case Failure(ex) =>
                      complete(StatusCodes.ServiceUnavailable -> ErrorResponse(s"ai service unavailable: ${ex.getMessage}"))
                  }
            }
          }
        },

        path("undo") {
          post {
            lock.synchronized {
              controller.undo() match
                case Right(_)  => complete(gameState)
                case Left(err) => complete(StatusCodes.Conflict -> ErrorResponse(err))
            }
          }
        },

        path("redo") {
          post {
            lock.synchronized {
              controller.redo() match
                case Right(_)  => complete(gameState)
                case Left(err) => complete(StatusCodes.Conflict -> ErrorResponse(err))
            }
          }
        }
      )
    }

  private val fenRoutes: Route =
    pathPrefix("api" / "fen") {
      concat(
        get { complete(FenResponse(controller.toFen)) },
        post {
          entity(as[FenLoadRequest]) { req =>
            onComplete(notationClient.validateFen(req.fen)) {
              case Success(Right(_)) =>
                lock.synchronized {
                  controller.loadFen(req.fen) match
                    case Right(msg) => complete(SuccessResponse(msg, Some(controller.toFen)))
                    case Left(err)  => complete(StatusCodes.UnprocessableEntity -> ErrorResponse(err))
                }
              case Success(Left(err)) =>
                complete(StatusCodes.UnprocessableEntity -> ErrorResponse(err))
              case Failure(ex) =>
                complete(StatusCodes.ServiceUnavailable -> ErrorResponse(s"notation service unavailable: ${ex.getMessage}"))
            }
          }
        }
      )
    }

  private val pgnRoutes: Route =
    pathPrefix("api" / "pgn") {
      concat(
        get { complete(PgnResponse(lock.synchronized(controller.exportPgn()))) },
        post {
          entity(as[PgnImportRequest]) { req =>
            onComplete(notationClient.parsePgn(req.pgn)) {
              case Success(Right(_)) =>
                lock.synchronized {
                  controller.importPgn(req.pgn) match
                    case Right(msg) => complete(SuccessResponse(msg, Some(controller.toFen)))
                    case Left(err)  => complete(StatusCodes.UnprocessableEntity -> ErrorResponse(err))
                }
              case Success(Left(err)) =>
                complete(StatusCodes.UnprocessableEntity -> ErrorResponse(err))
              case Failure(ex) =>
                complete(StatusCodes.ServiceUnavailable -> ErrorResponse(s"notation service unavailable: ${ex.getMessage}"))
            }
          }
        }
      )
    }

  val all: Route = concat(healthRoutes, gameRoutes, fenRoutes, pgnRoutes, persistenceRoutes.all, liveRoutes.all)
