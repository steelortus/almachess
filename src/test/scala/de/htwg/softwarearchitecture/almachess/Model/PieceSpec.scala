package de.htwg.softwarearchitecture.almachess.model

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class PieceSpec extends AnyWordSpec with Matchers:

  "A Piece" should {
    "have correct toString for white pieces" in {
      Piece(Color.White, PieceType.King).toString shouldBe "K"
      Piece(Color.White, PieceType.Queen).toString shouldBe "Q"
      Piece(Color.White, PieceType.Rook).toString shouldBe "R"
      Piece(Color.White, PieceType.Bishop).toString shouldBe "B"
      Piece(Color.White, PieceType.Knight).toString shouldBe "N"
      Piece(Color.White, PieceType.Pawn).toString shouldBe "P"
    }

    "have correct toString for black pieces" in {
      Piece(Color.Black, PieceType.King).toString shouldBe "k"
      Piece(Color.Black, PieceType.Queen).toString shouldBe "q"
      Piece(Color.Black, PieceType.Rook).toString shouldBe "r"
      Piece(Color.Black, PieceType.Bishop).toString shouldBe "b"
      Piece(Color.Black, PieceType.Knight).toString shouldBe "n"
      Piece(Color.Black, PieceType.Pawn).toString shouldBe "p"
    }

    "have correct fenChar" in {
      Piece(Color.White, PieceType.King).fenChar shouldBe 'K'
      Piece(Color.White, PieceType.Queen).fenChar shouldBe 'Q'
      Piece(Color.White, PieceType.Rook).fenChar shouldBe 'R'
      Piece(Color.White, PieceType.Bishop).fenChar shouldBe 'B'
      Piece(Color.White, PieceType.Knight).fenChar shouldBe 'N'
      Piece(Color.White, PieceType.Pawn).fenChar shouldBe 'P'
      Piece(Color.Black, PieceType.King).fenChar shouldBe 'k'
      Piece(Color.Black, PieceType.Queen).fenChar shouldBe 'q'
      Piece(Color.Black, PieceType.Rook).fenChar shouldBe 'r'
      Piece(Color.Black, PieceType.Bishop).fenChar shouldBe 'b'
      Piece(Color.Black, PieceType.Knight).fenChar shouldBe 'n'
      Piece(Color.Black, PieceType.Pawn).fenChar shouldBe 'p'
    }

    "have correct unicode representation" in {
      Piece(Color.White, PieceType.King).unicode shouldBe "♔"
      Piece(Color.White, PieceType.Queen).unicode shouldBe "♕"
      Piece(Color.White, PieceType.Rook).unicode shouldBe "♖"
      Piece(Color.White, PieceType.Bishop).unicode shouldBe "♗"
      Piece(Color.White, PieceType.Knight).unicode shouldBe "♘"
      Piece(Color.White, PieceType.Pawn).unicode shouldBe "♙"
      Piece(Color.Black, PieceType.King).unicode shouldBe "♚"
      Piece(Color.Black, PieceType.Queen).unicode shouldBe "♛"
      Piece(Color.Black, PieceType.Rook).unicode shouldBe "♜"
      Piece(Color.Black, PieceType.Bishop).unicode shouldBe "♝"
      Piece(Color.Black, PieceType.Knight).unicode shouldBe "♞"
      Piece(Color.Black, PieceType.Pawn).unicode shouldBe "♟"
    }

    "support equality" in {
      val whiteKing1 = Piece(Color.White, PieceType.King)
      val whiteKing2 = Piece(Color.White, PieceType.King)
      val blackKing = Piece(Color.Black, PieceType.King)
      whiteKing1 shouldEqual whiteKing2
      whiteKing1 should not equal blackKing
    }
  }

  "Piece.fromFenChar" should {
    "parse valid FEN characters" in {
      Piece.fromFenChar('K') shouldBe Some(Piece(Color.White, PieceType.King))
      Piece.fromFenChar('q') shouldBe Some(Piece(Color.Black, PieceType.Queen))
      Piece.fromFenChar('p') shouldBe Some(Piece(Color.Black, PieceType.Pawn))
      Piece.fromFenChar('N') shouldBe Some(Piece(Color.White, PieceType.Knight))
    }

    "return None for invalid characters" in {
      Piece.fromFenChar('x') shouldBe None
      Piece.fromFenChar('1') shouldBe None
      Piece.fromFenChar('@') shouldBe None
    }
  }