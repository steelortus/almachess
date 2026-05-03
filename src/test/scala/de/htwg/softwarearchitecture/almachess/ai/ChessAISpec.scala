package de.htwg.softwarearchitecture.almachess.ai

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import de.htwg.softwarearchitecture.almachess.model.*
import de.htwg.softwarearchitecture.almachess.parser.FenParser

class ChessAISpec extends AnyWordSpec with Matchers:

  private def parseFen(fen: String): GameState =
    FenParser.parse(fen).toOption.get

  "ChessAI.evaluate" should {
    "return 0 for the initial symmetric position" in {
      ChessAI.evaluate(GameState.initial) shouldBe 0
    }

    "return a positive score when White has a material advantage" in {
      // White has an extra queen.
      val state = parseFen("4k3/8/8/8/8/8/8/3QK3 w - - 0 1")
      ChessAI.evaluate(state) should be > 0
    }

    "return a negative score when Black has a material advantage" in {
      val state = parseFen("3qk3/8/8/8/8/8/8/4K3 w - - 0 1")
      ChessAI.evaluate(state) should be < 0
    }

    "rate two extra pawns higher than one extra pawn" in {
      val onePawn  = parseFen("4k3/8/8/8/8/8/4P3/4K3 w - - 0 1")
      val twoPawns = parseFen("4k3/8/8/8/8/8/3PP3/4K3 w - - 0 1")
      ChessAI.evaluate(twoPawns) should be > ChessAI.evaluate(onePawn)
    }

    "rate a queen higher than a knight" in {
      val withQueen  = parseFen("4k3/8/8/8/8/8/8/3QK3 w - - 0 1")
      val withKnight = parseFen("4k3/8/8/8/8/8/8/3NK3 w - - 0 1")
      ChessAI.evaluate(withQueen) should be > ChessAI.evaluate(withKnight)
    }

    "be sign-symmetric: mirroring colors flips the sign" in {
      val white = parseFen("4k3/8/8/8/8/8/8/3QK3 w - - 0 1")
      val black = parseFen("3qk3/8/8/8/8/8/8/4K3 w - - 0 1")
      ChessAI.evaluate(white) shouldBe -ChessAI.evaluate(black)
    }
  }

  "ChessAI.bestMove" should {
    "return None when there are no legal moves (checkmate)" in {
      // Black is checkmated (back-rank mate). Black to move, no legal moves.
      val state = parseFen("4Q1k1/5ppp/8/8/8/8/8/4K3 b - - 0 1")
      state.isCheckmate(Color.Black) shouldBe true
      ChessAI.bestMove(state, depth = 2) shouldBe None
    }

    "return None when there are no legal moves (stalemate)" in {
      val state = parseFen("7k/5K2/6Q1/8/8/8/8/8 b - - 0 1")
      state.isStalemate(Color.Black) shouldBe true
      ChessAI.bestMove(state, depth = 2) shouldBe None
    }

    "return Some legal move from the initial position" in {
      val move = ChessAI.bestMove(GameState.initial, depth = 2)
      move shouldBe defined
      GameState.initial.allLegalMoves() should contain(move.get)
    }

    "return a move that the engine considers playable (applyMove succeeds)" in {
      val state = GameState.initial
      val move  = ChessAI.bestMove(state, depth = 2).get
      state.applyMove(move) shouldBe a[Right[_, _]]
    }

    "prefer capturing a hanging queen with material gain" in {
      // White rook on a1 can capture an undefended black queen on a8.
      // FEN: black king h8, black queen a8 (hanging), white rook a1, white king h1.
      val state = parseFen("q6k/8/8/8/8/8/8/R6K w - - 0 1")
      val move  = ChessAI.bestMove(state, depth = 2).get
      move shouldBe Move(Pos(0, 0), Pos(7, 0)) // Rxa8
    }

    "find a mate-in-one when one is available" in {
      // White Qe1 → Qe8# (back-rank mate against Black Kg8 with f7/g7/h7 pawns).
      val state = parseFen("6k1/5ppp/8/8/8/8/8/4Q1K1 w - - 0 1")
      val move  = ChessAI.bestMove(state, depth = 2).get
      move shouldBe Move(Pos(0, 4), Pos(7, 4)) // Qe8#
      val ns = state.applyMove(move).toOption.get
      ns.status should startWith("checkmate")
    }

    "always pick a legal move regardless of depth" in {
      for d <- 1 to 3 do
        val m = ChessAI.bestMove(GameState.initial, d).get
        GameState.initial.allLegalMoves() should contain(m)
    }
  }
