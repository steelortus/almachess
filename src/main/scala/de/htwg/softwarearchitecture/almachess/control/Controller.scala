package de.htwg.softwarearchitecture.almachess.control

import de.htwg.softwarearchitecture.almachess.model.*
import de.htwg.softwarearchitecture.almachess.parser.FenParser
import de.htwg.softwarearchitecture.almachess.util.*

class Controller(private var currentState: GameState = GameState.initial) extends Observable:

  def state: GameState = currentState

  def ascii: String =
    currentState.board.toAscii

  def toFen: String =
    currentState.toFen

  def currentFen: String =
    currentState.toFen

  def isGameOver: Boolean =
    currentState.status.startsWith("checkmate") || currentState.status == "stalemate"

  def reset(): Unit =
    currentState = GameState.initial
    notifyObservers(GameEvent.BoardChanged(currentState))
    notifyObservers(GameEvent.Status("new game started"))

  def loadFen(fen: String): Either[String, String] =
    FenParser.parse(fen).map { parsed =>
      currentState = parsed
      notifyObservers(GameEvent.BoardChanged(currentState))
      notifyObservers(GameEvent.Status(s"position loaded: ${currentState.toFen}"))
      "FEN geladen."
    }

  def move(input: String): Either[String, Unit] =
    if isGameOver then
      Left(s"game is over: ${currentState.status}")
    else
      Move.parse(input).flatMap(move)

  def move(move: Move): Either[String, Unit] =
    if isGameOver then
      Left(s"game is over: ${currentState.status}")
    else
      currentState.applyMove(move).map { next =>
        currentState = next
        notifyObservers(GameEvent.BoardChanged(currentState))

        if next.status.startsWith("checkmate") || next.status == "stalemate" then
          notifyObservers(GameEvent.GameOver(next.status))
        else
          notifyObservers(GameEvent.Status(next.status))
      }.left.map { err =>
        notifyObservers(GameEvent.IllegalMove(err))
        err
      }

  def move(from: String, to: String, promotion: Option[String] = None): Either[String, String] =
    if isGameOver then
      Left(s"game is over: ${currentState.status}")
    else
      val promoType: Option[PieceType] = promotion.flatMap(parsePromotion)

      for
        fromPos <- Pos.fromAlgebraic(from).toRight(s"invalid position: $from")
        toPos   <- Pos.fromAlgebraic(to).toRight(s"invalid position: $to")
        _ <- move(Move(fromPos, toPos, promoType))
      yield currentState.status

  private def parsePromotion(s: String): Option[PieceType] =
    s.trim.toUpperCase match
      case "Q" | "QUEEN"  => Some(PieceType.Queen)
      case "R" | "ROOK"   => Some(PieceType.Rook)
      case "B" | "BISHOP" => Some(PieceType.Bishop)
      case "N" | "KNIGHT" => Some(PieceType.Knight)
      case _              => None