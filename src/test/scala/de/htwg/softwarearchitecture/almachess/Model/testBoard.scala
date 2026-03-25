package de.htwg.softwarearchitecture.almachess.Model

import munit.FunSuite

class testBoard extends FunSuite:
  test("Board initial setup has correct dimensions") {
    val board = Board.initial
    assertEquals(board.squares.length, 8)
    assert(board.squares.forall(_.length == 8))
  }

  test("Board initial setup has white king at e1") {
    val board = Board.initial
    val piece = board.pieceAt(0, 4) // rank 1, file e (0-based)
    assert(piece.isDefined)
    assertEquals(piece.get, Piece(Color.White, PieceType.King))
  }

  test("Board initial setup has black king at e8") {
    val board = Board.initial
    val piece = board.pieceAt(7, 4) // rank 8, file e
    assert(piece.isDefined)
    assertEquals(piece.get, Piece(Color.Black, PieceType.King))
  }

  test("Board initial setup has empty squares in middle") {
    val board = Board.initial
    for (rank <- 2 to 5; file <- 0 to 7) {
      assert(board.pieceAt(rank, file).isEmpty)
    }
  }

  test("Board toAscii produces expected output") {
    val board = Board.initial
    val ascii = board.toAscii
    assert(ascii.contains("8 | r n b q k b n r"))
    assert(ascii.contains("7 | p p p p p p p p"))
    assert(ascii.contains("2 | P P P P P P P P"))
    assert(ascii.contains("1 | R N B Q K B N R"))
    assert(ascii.contains("  | a b c d e f g h"))
  }

  test("Board pieceAt returns correct pieces") {
    val board = Board.initial
    assertEquals(board.pieceAt(0, 0), Some(Piece(Color.White, PieceType.Rook)))
    assertEquals(board.pieceAt(7, 7), Some(Piece(Color.Black, PieceType.Rook)))
    assertEquals(board.pieceAt(3, 3), None)
  }

  test("illegal pawn move e2->e5 rejected") {
    val result = Board.initial.move("e2", "e5")
    assert(result.isLeft)
  }

  test("legal pawn move e2->e4 accepted") {
    val result = Board.initial.move("e2", "e4")
    assert(result.isRight)
  }