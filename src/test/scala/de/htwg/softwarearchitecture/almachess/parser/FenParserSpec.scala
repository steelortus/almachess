package de.htwg.softwarearchitecture.almachess.parser

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import de.htwg.softwarearchitecture.almachess.model._

class FenParserSpec extends AnyWordSpec with Matchers:

  "FenParser.parse" should {
    "parse complete FEN string" in {
      val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
      val result = FenParser.parse(fen)
      result shouldBe a[Right[_, _]]
      val state = result.right.get
      state.board shouldBe Board.initial
      state.turn shouldBe Color.White
      state.castleRights shouldBe CastleRights()
      state.enPassantTarget shouldBe None
      state.halfMoveClock shouldBe 0
      state.fullMoveNumber shouldBe 1
      state.status shouldBe "White to move"
    }

    "handle different turns" in {
      val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
      val result = FenParser.parse(fen)
      result.right.get.turn shouldBe Color.Black
      result.right.get.status shouldBe "Black to move"
    }

    "handle partial castle rights" in {
      val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w Kq - 0 1"
      val result = FenParser.parse(fen)
      val rights = result.right.get.castleRights
      rights.whiteKingSide shouldBe true
      rights.whiteQueenSide shouldBe false
      rights.blackKingSide shouldBe false
      rights.blackQueenSide shouldBe true
    }

    "handle en passant target" in {
      val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
      val result = FenParser.parse(fen)
      result.right.get.enPassantTarget shouldBe Some(Pos(2, 4))
    }

    "handle halfmove and fullmove clocks" in {
      val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 5 10"
      val result = FenParser.parse(fen)
      result.right.get.halfMoveClock shouldBe 5
      result.right.get.fullMoveNumber shouldBe 10
    }

    "reject malformed FEN" in {
      FenParser.parse("") shouldBe a[Left[_, _]]
      FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR") shouldBe a[Left[_, _]]
      FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 extra") shouldBe a[Left[_, _]]
    }
  }