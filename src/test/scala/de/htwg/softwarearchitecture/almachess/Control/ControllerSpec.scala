package de.htwg.softwarearchitecture.almachess.control

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import de.htwg.softwarearchitecture.almachess.model.{GameState, Board, Color, Piece, PieceType, Pos, Move}

class ControllerSpec extends AnyWordSpec with Matchers:

  "A Controller" should {
    "be instantiable" in {
      val controller = new Controller()
      controller shouldBe a[Controller]
    }

    "provide access to the current game state" in {
      val controller = new Controller()
      controller.state shouldBe a[GameState]
      controller.state.board shouldBe Board.initial
      controller.state.turn shouldBe Color.White
    }

    "provide ASCII representation" in {
      val controller = new Controller()
      val ascii = controller.ascii
      ascii should include("8 | r n b q k b n r")
      ascii should include("1 | R N B Q K B N R")
    }

    "provide FEN representation" in {
      val controller = new Controller()
      val fen = controller.toFen
      fen should startWith("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
    }

    "report game not over initially" in {
      val controller = new Controller()
      controller.isGameOver shouldBe false
    }

    "reset to initial state" in {
      val controller = new Controller()
      // Make some change if possible, but since Controller is immutable, reset creates new state
      controller.reset()
      controller.state shouldBe GameState.initial
    }

    "load FEN position" in {
      val controller = new Controller()
      val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
      val result = controller.loadFen(fen)
      result shouldBe a[Right[_, _]]
      result.right.get should include("FEN geladen")
    }

    "reject invalid FEN" in {
      val controller = new Controller()
      val result = controller.loadFen("invalid")
      result shouldBe a[Left[_, _]]
    }

    "parse and apply valid moves" in {
      val controller = new Controller()
      val result = controller.move("e2", "e4")
      result shouldBe a[Right[_, _]]
      controller.state.board.pieceAt(Pos(3, 4)) shouldBe Some(Piece(Color.White, PieceType.Pawn))
      controller.state.board.pieceAt(Pos(1, 4)) shouldBe None
    }

    "reject invalid moves" in {
      val controller = new Controller()
      val result = controller.move("e2", "e5")
      result shouldBe a[Left[_, _]]
    }

    "handle move with promotion" in {
      // Set up a position where pawn can promote
      val controller = new Controller()
      // Load a FEN where white pawn is on 7th rank and b8 is empty
      val fen = "r1bqkbnr/1Ppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
      controller.loadFen(fen)
      val result = controller.move("b7", "b8", Some("Q"))
      result shouldBe a[Right[_, _]]
      controller.state.board.pieceAt(Pos(7, 1)) shouldBe Some(Piece(Color.White, PieceType.Queen))
    }

    "parse move strings" in {
      val controller = new Controller()
      val result = controller.move("e2e4")
      result shouldBe a[Right[_, _]]
    }
  }