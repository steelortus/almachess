package de.htwg.softwarearchitecture.almachess.model

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class FenSpec extends AnyWordSpec with Matchers:

  "Fen.parse" should {
    "parse initial position" in {
      val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
      val result = Fen.parse(fen)
      result shouldBe a[Right[_, _]]
      val state = result.right.get
      state.board shouldBe Board.initial
      state.turn shouldBe Color.White
      state.castleRights shouldBe CastleRights()
      state.enPassantTarget shouldBe None
      state.halfMoveClock shouldBe 0
      state.fullMoveNumber shouldBe 1
    }

    "parse position after e4" in {
      val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
      val result = Fen.parse(fen)
      result shouldBe a[Right[_, _]]
      val state = result.right.get
      state.board.pieceAt(Pos(3, 4)) shouldBe Some(Piece(Color.White, PieceType.Pawn))
      state.turn shouldBe Color.Black
      state.enPassantTarget shouldBe Some(Pos(2, 4))
    }

    "reject invalid FEN" in {
      Fen.parse("") shouldBe a[Left[_, _]]
      Fen.parse("invalid") shouldBe a[Left[_, _]]
      Fen.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR") shouldBe a[Left[_, _]] // too few fields
    }
  }

  "Fen.render" should {
    "render initial position" in {
      val state = GameState.initial
      Fen.render(state) shouldBe "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    }
  }