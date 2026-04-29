package de.htwg.softwarearchitecture.almachess.ai

import de.htwg.softwarearchitecture.almachess.model.*
import scala.annotation.tailrec

object ChessAI:

  // ── Material values (centipawns) ──────────────────────────────────────────
  private val pieceValue: Map[PieceType, Int] = Map(
    PieceType.Pawn   ->   100,
    PieceType.Knight ->   320,
    PieceType.Bishop ->   330,
    PieceType.Rook   ->   500,
    PieceType.Queen  ->   900,
    PieceType.King   -> 20000,
  )

  // ── Piece-square tables ───────────────────────────────────────────────────
  // Layout: row 0 = rank 8 (Black's back), row 7 = rank 1 (White's back)
  // Access for White: (7 - rank) * 8 + file
  // Access for Black: rank * 8 + file   (mirrored automatically)

  private val pawnPST = Array[Int](
     0,  0,  0,  0,  0,  0,  0,  0,  // rank 8
    50, 50, 50, 50, 50, 50, 50, 50,  // rank 7
    10, 10, 20, 30, 30, 20, 10, 10,  // rank 6
     5,  5, 10, 25, 25, 10,  5,  5,  // rank 5
     0,  0,  0, 20, 20,  0,  0,  0,  // rank 4
     5, -5,-10,  0,  0,-10, -5,  5,  // rank 3
     5, 10, 10,-20,-20, 10, 10,  5,  // rank 2 ← White pawns start here
     0,  0,  0,  0,  0,  0,  0,  0,  // rank 1
  )

  private val knightPST = Array[Int](
    -50,-40,-30,-30,-30,-30,-40,-50,
    -40,-20,  0,  0,  0,  0,-20,-40,
    -30,  0, 10, 15, 15, 10,  0,-30,
    -30,  5, 15, 20, 20, 15,  5,-30,
    -30,  0, 15, 20, 20, 15,  0,-30,
    -30,  5, 10, 15, 15, 10,  5,-30,
    -40,-20,  0,  5,  5,  0,-20,-40,
    -50,-40,-30,-30,-30,-30,-40,-50,
  )

  private val bishopPST = Array[Int](
    -20,-10,-10,-10,-10,-10,-10,-20,
    -10,  0,  0,  0,  0,  0,  0,-10,
    -10,  0,  5, 10, 10,  5,  0,-10,
    -10,  5,  5, 10, 10,  5,  5,-10,
    -10,  0, 10, 10, 10, 10,  0,-10,
    -10, 10, 10, 10, 10, 10, 10,-10,
    -10,  5,  0,  0,  0,  0,  5,-10,
    -20,-10,-10,-10,-10,-10,-10,-20,
  )

  private val rookPST = Array[Int](
     0,  0,  0,  0,  0,  0,  0,  0,
     5, 10, 10, 10, 10, 10, 10,  5,
    -5,  0,  0,  0,  0,  0,  0, -5,
    -5,  0,  0,  0,  0,  0,  0, -5,
    -5,  0,  0,  0,  0,  0,  0, -5,
    -5,  0,  0,  0,  0,  0,  0, -5,
    -5,  0,  0,  0,  0,  0,  0, -5,
     0,  0,  0,  5,  5,  0,  0,  0,
  )

  private val queenPST = Array[Int](
    -20,-10,-10, -5, -5,-10,-10,-20,
    -10,  0,  0,  0,  0,  0,  0,-10,
    -10,  0,  5,  5,  5,  5,  0,-10,
     -5,  0,  5,  5,  5,  5,  0, -5,
      0,  0,  5,  5,  5,  5,  0, -5,
    -10,  5,  5,  5,  5,  5,  0,-10,
    -10,  0,  5,  0,  0,  0,  0,-10,
    -20,-10,-10, -5, -5,-10,-10,-20,
  )

  private val kingMidPST = Array[Int](   // King safety in middlegame
    -30,-40,-40,-50,-50,-40,-40,-30,
    -30,-40,-40,-50,-50,-40,-40,-30,
    -30,-40,-40,-50,-50,-40,-40,-30,
    -30,-40,-40,-50,-50,-40,-40,-30,
    -20,-30,-30,-40,-40,-30,-30,-20,
    -10,-20,-20,-20,-20,-20,-20,-10,
     20, 20,  0,  0,  0,  0, 20, 20,  // King prefers corners in middlegame
     20, 30, 10,  0,  0, 10, 30, 20,
  )

  private def pst(piece: Piece, pos: Pos): Int =
    val tbl = piece.tpe match
      case PieceType.Pawn   => pawnPST
      case PieceType.Knight => knightPST
      case PieceType.Bishop => bishopPST
      case PieceType.Rook   => rookPST
      case PieceType.Queen  => queenPST
      case PieceType.King   => kingMidPST
    val idx =
      if piece.color == Color.White then (7 - pos.rank) * 8 + pos.file
      else pos.rank * 8 + pos.file
    tbl(idx)

  // ── Static evaluation (positive = good for White) ─────────────────────────
  def evaluate(state: GameState): Int =
    var score = 0
    for
      rank <- 0 until 8
      file <- 0 until 8
    do
      val pos = Pos(rank, file)
      state.board.pieceAt(pos).foreach { piece =>
        val v = pieceValue(piece.tpe) + pst(piece, pos)
        if piece.color == Color.White then score += v else score -= v
      }
    score

  // ── Move ordering: captures and promotions first ──────────────────────────
  private val rng = scala.util.Random()

  private def orderMoves(moves: Vector[Move], state: GameState): List[Move] =
    // Shuffle equal moves for variety, then stable-sort: captures first
    rng.shuffle(moves).sortBy { m =>
      val capVal   = state.board.pieceAt(m.to).map(p => -pieceValue(p.tpe)).getOrElse(0)
      val promoVal = m.promotion.map(p => -pieceValue(p)).getOrElse(0)
      capVal + promoVal
    }.toList

  // ── Negamax with alpha-beta pruning ───────────────────────────────────────
  // Returns score from the perspective of the side to move.
  private def negamax(state: GameState, depth: Int, alpha: Int, beta: Int): Int =
    val moves = state.allLegalMoves()
    if moves.isEmpty then
      // No legal moves: checkmate or stalemate
      if state.isCheck(state.turn) then -(100_000 + depth)   // prefer later mates for defender
      else 0
    else if depth == 0 then
      val sign = if state.turn == Color.White then 1 else -1
      evaluate(state) * sign
    else
      searchMoves(state, orderMoves(moves, state), depth, alpha, beta)

  @tailrec
  private def searchMoves(
      state: GameState,
      moves: List[Move],
      depth: Int,
      alpha: Int,
      beta: Int
  ): Int =
    moves match
      case Nil => alpha
      case move :: rest =>
        if alpha >= beta then alpha   // β-cutoff
        else
          val score = state.applyMove(move).fold(
            _ => Int.MinValue + 1,
            next => -negamax(next, depth - 1, -beta, -alpha)
          )
          searchMoves(state, rest, depth, math.max(alpha, score), beta)

  // ── Public API ────────────────────────────────────────────────────────────
  /** Returns the best move found within the given search depth. */
  def bestMove(state: GameState, depth: Int): Option[Move] =
    val moves = state.allLegalMoves()
    if moves.isEmpty then None
    else
      var best  = moves.head
      var alpha = Int.MinValue + 1
      val beta  = Int.MaxValue
      for move <- orderMoves(moves, state) do
        val score = state.applyMove(move).fold(
          _ => Int.MinValue + 1,
          next => -negamax(next, depth - 1, -beta, -alpha)
        )
        if score > alpha then
          alpha = score
          best  = move
      Some(best)
