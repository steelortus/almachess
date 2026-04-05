package de.htwg.softwarearchitecture.almachess.control

import de.htwg.softwarearchitecture.almachess.model.*
import de.htwg.softwarearchitecture.almachess.parser.{FenParser, PgnParser}
import de.htwg.softwarearchitecture.almachess.io.JsonIO
import de.htwg.softwarearchitecture.almachess.ai.ChessAI
import de.htwg.softwarearchitecture.almachess.util.*

class Controller(private var currentState: GameState = GameState.initial) extends Observable:

  // --- Undo / Redo stacks ---
  // Each entry is (stateBeforeMove, moveMade)
  private var undoStack: List[(GameState, Move)] = Nil
  private var redoStack: List[(GameState, Move)] = Nil

  // The position from which the current game started (after reset / FEN / JSON / PGN load)
  private var initialState: GameState = GameState.initial

  // PGN metadata tags
  private var tags: Map[String, String] = Map(
    "Event"  -> "AlmaChess Game",
    "Site"   -> "HTWG Konstanz",
    "White"  -> "White",
    "Black"  -> "Black",
    "Result" -> "*"
  )

  // --- AI ---
  private var aiColor: Option[Color] = None
  private var aiDepth: Int           = 3

  def enableAi(color: Color, depth: Int = 3): Unit =
    aiColor = Some(color)
    aiDepth = depth

  def disableAi(): Unit =
    aiColor = None

  def setAiDepth(d: Int): Unit =
    aiDepth = d

  /** True when it is the AI's turn and the game is still running. */
  def isAiTurn: Boolean =
    aiColor.contains(currentState.turn) && !isGameOver

  def aiMode: Option[Color] = aiColor
  def currentAiDepth: Int   = aiDepth

  /** Let the AI pick and play its move. Should be called from a background thread. */
  def aiMove(): Either[String, Unit] =
    ChessAI.bestMove(currentState, aiDepth) match
      case None    => Left("KI hat keinen Zug gefunden")
      case Some(m) => move(m)

  // --- Public state accessors ---

  def state: GameState    = currentState
  def canUndo: Boolean    = undoStack.nonEmpty
  def canRedo: Boolean    = redoStack.nonEmpty

  /** All moves played so far, in chronological order. */
  def moveHistory: List[Move] = undoStack.reverse.map(_._2)

  def ascii: String  = currentState.board.toAscii
  def toFen: String  = currentState.toFen
  def currentFen: String = currentState.toFen

  def isGameOver: Boolean =
    currentState.status.startsWith("checkmate") || currentState.status == "stalemate"

  // --- Game control ---

  def reset(): Unit =
    currentState = GameState.initial
    initialState = GameState.initial
    undoStack    = Nil
    redoStack    = Nil
    notifyObservers(GameEvent.BoardChanged(currentState))
    notifyObservers(GameEvent.Status("new game started"))

  def loadFen(fen: String): Either[String, String] =
    FenParser.parse(fen).map { parsed =>
      initialState = parsed
      currentState = parsed
      undoStack    = Nil
      redoStack    = Nil
      notifyObservers(GameEvent.BoardChanged(currentState))
      notifyObservers(GameEvent.Status(s"position loaded: ${currentState.toFen}"))
      "FEN geladen."
    }

  // --- Move ---

  def move(input: String): Either[String, Unit] =
    if isGameOver then Left(s"game is over: ${currentState.status}")
    else Move.parse(input).flatMap(move)

  def move(m: Move): Either[String, Unit] =
    if isGameOver then Left(s"game is over: ${currentState.status}")
    else
      val before = currentState
      currentState.applyMove(m).map { next =>
        undoStack    = (before, m) :: undoStack
        redoStack    = Nil
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
    if isGameOver then Left(s"game is over: ${currentState.status}")
    else
      val promoType = promotion.flatMap(parsePromotion)
      for
        fromPos <- Pos.fromAlgebraic(from).toRight(s"invalid position: $from")
        toPos   <- Pos.fromAlgebraic(to).toRight(s"invalid position: $to")
        _       <- move(Move(fromPos, toPos, promoType))
      yield currentState.status

  private def parsePromotion(s: String): Option[PieceType] =
    s.trim.toUpperCase match
      case "Q" | "QUEEN"  => Some(PieceType.Queen)
      case "R" | "ROOK"   => Some(PieceType.Rook)
      case "B" | "BISHOP" => Some(PieceType.Bishop)
      case "N" | "KNIGHT" => Some(PieceType.Knight)
      case _              => None

  // --- Undo / Redo ---

  def undo(): Either[String, Unit] =
    undoStack match
      case Nil =>
        Left("nothing to undo")
      case (prev, m) :: rest =>
        redoStack    = (currentState, m) :: redoStack
        currentState = prev
        undoStack    = rest
        notifyObservers(GameEvent.BoardChanged(currentState))
        notifyObservers(GameEvent.Status(currentState.status))
        Right(())

  def redo(): Either[String, Unit] =
    redoStack match
      case Nil =>
        Left("nothing to redo")
      case (next, m) :: rest =>
        undoStack    = (currentState, m) :: undoStack
        currentState = next
        redoStack    = rest
        notifyObservers(GameEvent.BoardChanged(currentState))
        notifyObservers(GameEvent.Status(currentState.status))
        Right(())

  // --- PGN Export / Import ---

  def exportPgn(): String =
    val result =
      if currentState.status.startsWith("checkmate") then
        if currentState.turn == Color.White then "0-1" else "1-0"
      else if currentState.status == "stalemate" then "1/2-1/2"
      else "*"

    val updatedTags = tags + ("Result" -> result)
    val headerStr   = updatedTags.map { case (k, v) => s"""[$k "$v"]""" }.mkString("\n")

    // Replay moves from initial state to generate SAN
    val sans: List[String] = {
      var s = initialState
      moveHistory.map { m =>
        val san = San.toSan(m, s)
        s = s.applyMove(m).getOrElse(s)
        san
      }
    }

    val moveStr = sans.zipWithIndex.map { case (san, i) =>
      if i % 2 == 0 then s"${i / 2 + 1}. $san" else san
    }.mkString(" ")

    s"$headerStr\n\n$moveStr $result\n"

  def importPgn(pgn: String): Either[String, String] =
    PgnParser.parse(pgn).flatMap { case (pgnTags, sanMoves) =>
      val startState = pgnTags.get("FEN")
        .flatMap(fen => FenParser.parse(fen).toOption)
        .getOrElse(GameState.initial)

      replayMoves(startState, sanMoves.map { san =>
        San.fromSan(san, _: GameState)
      }).map { (finalState, played) =>
        initialState = startState
        currentState = finalState
        undoStack    = buildUndoStack(startState, played)
        redoStack    = Nil
        tags         = tags ++ pgnTags
        notifyObservers(GameEvent.BoardChanged(currentState))
        notifyObservers(GameEvent.Status(currentState.status))
        s"PGN geladen: ${played.size} Züge"
      }
    }

  // --- JSON Export / Import ---

  def exportJson(): String =
    JsonIO.exportGame(initialState.toFen, moveHistory, tags)

  def importJson(json: String): Either[String, String] =
    JsonIO.importGame(json).flatMap { case (fen, uciMoves, jsonTags) =>
      val startState = FenParser.parse(fen).getOrElse(GameState.initial)

      replayMoves(startState, uciMoves.map { uci =>
        (_: GameState) => Move.parse(uci)
      }).map { (finalState, played) =>
        initialState = startState
        currentState = finalState
        undoStack    = buildUndoStack(startState, played)
        redoStack    = Nil
        tags         = tags ++ jsonTags
        notifyObservers(GameEvent.BoardChanged(currentState))
        notifyObservers(GameEvent.Status(currentState.status))
        s"JSON geladen: ${played.size} Züge"
      }
    }

  // --- Helpers ---

  /** Replays a list of move-resolvers (state ⇒ Either[err, Move]) from startState. */
  private def replayMoves(
      start: GameState,
      resolvers: List[GameState => Either[String, Move]]
  ): Either[String, (GameState, List[Move])] =
    resolvers.foldLeft[Either[String, (GameState, List[Move])]](Right((start, Nil))) {
      case (Left(err), _) => Left(err)
      case (Right((s, played)), resolver) =>
        for
          m    <- resolver(s)
          next <- s.applyMove(m)
        yield (next, played :+ m)
    }

  /** Rebuilds the undo stack from an initial state and a list of moves (oldest first). */
  private def buildUndoStack(start: GameState, moves: List[Move]): List[(GameState, Move)] =
    var s = start
    moves.map { m =>
      val before = s
      s = s.applyMove(m).getOrElse(s)
      (before, m)
    }.reverse
