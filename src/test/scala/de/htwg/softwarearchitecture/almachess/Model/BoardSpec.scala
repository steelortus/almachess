package de.htwg.softwarearchitecture.almachess.model

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class BoardSpec extends AnyWordSpec with Matchers:

  "A Board" should {
    "have correct initial setup dimensions" in {
      val board = Board.initial
      board.squares.length shouldBe 8
      board.squares.forall(_.length == 8) shouldBe true
    }

    "have white king at e1 in initial setup" in {
      val board = Board.initial
      val piece = board.pieceAt(Pos(0, 4)) // rank 1, file e (0-based)
      piece shouldBe defined
      piece.get shouldEqual Piece(Color.White, PieceType.King)
    }

    "have black king at e8 in initial setup" in {
      val board = Board.initial
      val piece = board.pieceAt(Pos(7, 4)) // rank 8, file e
      piece shouldBe defined
      piece.get shouldEqual Piece(Color.Black, PieceType.King)
    }

    "have empty squares in the middle in initial setup" in {
      val board = Board.initial
      for (rank <- 2 to 5; file <- 0 to 7) {
        board.pieceAt(Pos(rank, file)) shouldBe None
      }
    }

    "produce correct ASCII representation" in {
      val board = Board.initial
      val ascii = board.toAscii
      ascii should include("8 | r n b q k b n r")
      ascii should include("7 | p p p p p p p p")
      ascii should include("2 | P P P P P P P P")
      ascii should include("1 | R N B Q K B N R")
      ascii should include("     a b c d e f g h")
    }

    "return correct pieces at positions" in {
      val board = Board.initial
      board.pieceAt(Pos(0, 0)) shouldBe Some(Piece(Color.White, PieceType.Rook))
      board.pieceAt(Pos(7, 7)) shouldBe Some(Piece(Color.Black, PieceType.Rook))
      board.pieceAt(Pos(3, 3)) shouldBe None
    }

    "update squares correctly" in {
      val board = Board.initial
      val newBoard = board.updated(Pos(0, 0), Some(Piece(Color.Black, PieceType.Queen)))
      newBoard.pieceAt(Pos(0, 0)) shouldBe Some(Piece(Color.Black, PieceType.Queen))
      board.pieceAt(Pos(0, 0)) shouldBe Some(Piece(Color.White, PieceType.Rook)) // original unchanged
    }

    "check bounds with isInside" in {
      Pos(0, 0).isInside shouldBe true
      Pos(7, 7).isInside shouldBe true
      Pos(-1, 0).isInside shouldBe false
      Pos(0, 8).isInside shouldBe false
      Pos(8, 0).isInside shouldBe false
    }

    "check empty squares with isEmpty" in {
      val board = Board.initial
      board.isEmpty(Pos(3, 3)) shouldBe true // middle empty
      board.isEmpty(Pos(0, 0)) shouldBe false // has piece
      board.isEmpty(Pos(-1, 0)) shouldBe true // out of bounds (considered empty)
    }

    "provide access to positions" in {
      val board = Board.initial
      board.positions.length shouldBe 64
      board.positions should contain(Pos(0, 0))
      board.positions should contain(Pos(7, 7))
    }

    "find kings correctly" in {
      val board = Board.initial
      board.findKing(Color.White) shouldBe Some(Pos(0, 4))
      board.findKing(Color.Black) shouldBe Some(Pos(7, 4))
    }

    "check for king presence" in {
      val board = Board.initial
      board.hasKing(Color.White) shouldBe true
      board.hasKing(Color.Black) shouldBe true
    }

    "check enemy pieces" in {
      val board = Board.initial
      board.isEnemy(Pos(0, 0), Color.Black) shouldBe true
      board.isEnemy(Pos(0, 0), Color.White) shouldBe false
      board.isEnemy(Pos(3, 3), Color.White) shouldBe false
    }

    "check clear paths" in {
      val board = Board.initial
      board.clearPath(Pos(0, 0), Pos(0, 7)) shouldBe false // rook path blocked by pieces
      board.clearPath(Pos(3, 3), Pos(3, 5)) shouldBe true // empty path
    }

    "produce correct FEN placement" in {
      val board = Board.initial
      val fen = board.toFenPlacement
      fen shouldBe "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
    }

    "support map operation" in {
      val board = Board.initial
      val mapped = board.map {
        case Some(piece) if piece.color == Color.White => Some(Piece(Color.Black, piece.tpe))
        case other => other
      }
      mapped.pieceAt(Pos(0, 4)) shouldBe Some(Piece(Color.Black, PieceType.King))
      mapped.pieceAt(Pos(7, 4)) shouldBe Some(Piece(Color.Black, PieceType.King))
    }

    "support flatMap operation" in {
      val board = Board.initial
      val flatMapped = board.flatMap(_ => Board.empty)
      flatMapped.squares.flatten.forall(_ == None) shouldBe true
    }

    "support foreach operation" in {
      val board = Board.initial
      var count = 0
      board.foreach {
        case Some(_) => count += 1
        case None => // do nothing
      }
      count shouldBe 32 // 16 pieces per side
    }
  }

  "Board.empty" should {
    "contain no pieces" in {
      val board = Board.empty
      board.squares.flatten.forall(_ == None) shouldBe true
    }
  }

  "Board.initial" should {
    "be different from empty" in {
      Board.initial should not equal Board.empty
    }
  }