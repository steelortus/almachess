package de.htwg.softwarearchitecture.almachess.model

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class GameStateSpec extends AnyWordSpec with Matchers:

  "A GameState" should {
    "initialize with initial position" in {
      val state = GameState.initial
      state.board shouldBe Board.initial
      state.turn shouldBe Color.White
      state.castleRights shouldBe CastleRights()
      state.enPassantTarget shouldBe None
      state.halfMoveClock shouldBe 0
      state.fullMoveNumber shouldBe 1
      state.status shouldBe "White to move"
    }

    "produce correct FEN" in {
      val fen = GameState.initial.toFen
      fen shouldBe "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    }

    "find legal moves from position" in {
      val state = GameState.initial
      val moves = state.legalMovesFrom(Pos(1, 4)) // e2 pawn
      moves should not be empty
      moves should contain(Move(Pos(1, 4), Pos(3, 4))) // e2-e4
      moves should contain(Move(Pos(1, 4), Pos(2, 4))) // e2-e3
    }

    "find all legal moves" in {
      val state = GameState.initial
      val moves = state.allLegalMoves()
      moves.length shouldBe 20 // 16 pawns (8 can move 1 or 2), 4 knights
    }

    "detect check" in {
      // Set up a position where black king is in check
      val board = Board.initial.updated(Pos(7, 4), None) // remove black king
        .updated(Pos(3, 4), Some(Piece(Color.Black, PieceType.King))) // place king on e4
        .updated(Pos(3, 3), Some(Piece(Color.White, PieceType.Queen))) // place white queen on d4 (same rank)
      val state = GameState(board, Color.Black)
      state.isCheck(Color.Black) shouldBe true
    }

    "detect checkmate" in {
      // Checkmate: king on h8, queen on g7, king on g8 blocking escape
      val board = Board.empty
        .updated(Pos(7, 7), Some(Piece(Color.Black, PieceType.King))) // h8
        .updated(Pos(6, 6), Some(Piece(Color.White, PieceType.Queen))) // g7
        .updated(Pos(7, 6), Some(Piece(Color.White, PieceType.King))) // g8
      val state = GameState(board, Color.Black)
      state.isCheckmate(Color.Black) shouldBe true
    }

    "apply valid moves" in {
      val state = GameState.initial
      val move = Move(Pos(1, 4), Pos(3, 4)) // e2-e4
      val result = state.applyMove(move)
      result shouldBe a[Right[_, _]]
      val newState = result.right.get
      newState.board.pieceAt(Pos(3, 4)) shouldBe Some(Piece(Color.White, PieceType.Pawn))
      newState.board.pieceAt(Pos(1, 4)) shouldBe None
      newState.turn shouldBe Color.Black
    }

    "reject invalid moves" in {
      val state = GameState.initial
      val move = Move(Pos(1, 4), Pos(4, 4)) // e2-e5 (illegal pawn move)
      val result = state.applyMove(move)
      result shouldBe a[Left[_, _]]
    }
  }