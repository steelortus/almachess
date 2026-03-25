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

    // Test coords method
  test("Board coords parses valid squares") {
    val board = Board.initial
    assertEquals(board.coords("a1"), Some((0, 0)))
    assertEquals(board.coords("h8"), Some((7, 7)))
    assertEquals(board.coords("e4"), Some((3, 4)))
  }

  test("Board coords rejects invalid squares") {
    val board = Board.initial
    assertEquals(board.coords(""), None)
    assertEquals(board.coords("a"), None)
    assertEquals(board.coords("a9"), None)
    assertEquals(board.coords("i1"), None)
    assertEquals(board.coords("aa"), None)
  }

  // Test updated method
  test("Board updated changes square correctly") {
    val board = Board.initial
    val newBoard = board.updated(0, 0, Some(Piece(Color.Black, PieceType.Queen)))
    assertEquals(newBoard.pieceAt(0, 0), Some(Piece(Color.Black, PieceType.Queen)))
    assertEquals(board.pieceAt(0, 0), Some(Piece(Color.White, PieceType.Rook))) // original unchanged
  }

  // Test isInside
  test("Board isInside checks bounds") {
    val board = Board.initial
    assert(board.isInside(0, 0))
    assert(board.isInside(7, 7))
    assert(!board.isInside(-1, 0))
    assert(!board.isInside(0, 8))
    assert(!board.isInside(8, 0))
  }

  // Test isEmpty
  test("Board isEmpty checks empty squares") {
    val board = Board.initial
    assert(board.isEmpty(3, 3)) // middle empty
    assert(!board.isEmpty(0, 0)) // has piece
    assert(!board.isEmpty(-1, 0)) // out of bounds
  }

  // Test isEnemy
  test("Board isEnemy detects enemy pieces") {
    val board = Board.initial
    assert(board.isEnemy(7, 0, Color.White)) // black rook
    assert(!board.isEnemy(0, 0, Color.White)) // own piece
    assert(!board.isEnemy(3, 3, Color.White)) // empty
  }

  test("Board clearPath blocked") {
    val board = Board.initial.updated(0, 1, Some(Piece(Color.White, PieceType.Pawn))) // block a1-b1
    assert(!board.clearPath(0, 0, 0, 7)) // blocked
  }

  // Test validDestination for each piece type
  test("Board validDestination pawn moves") {
    val board = Board.initial
    val pawn = Piece(Color.White, PieceType.Pawn)
    assert(board.validDestination(pawn, 1, 0, 2, 0)) // forward 1
    assert(board.validDestination(pawn, 1, 0, 3, 0)) // forward 2 from start
    assert(!board.validDestination(pawn, 1, 0, 4, 0)) // too far
    assert(!board.validDestination(pawn, 1, 0, 2, 1)) // diagonal without capture
    // Add capture: place enemy
    val boardWithEnemy = board.updated(2, 1, Some(Piece(Color.Black, PieceType.Pawn)))
    assert(boardWithEnemy.validDestination(pawn, 1, 0, 2, 1)) // capture diagonal
  }

  test("Board validDestination knight moves") {
    val board = Board.initial
    val knight = Piece(Color.White, PieceType.Knight)
    assert(board.validDestination(knight, 0, 1, 2, 0)) // L-shape
    assert(board.validDestination(knight, 0, 1, 2, 2))
    assert(!board.validDestination(knight, 0, 1, 1, 1)) // not L
  }

  test("Board validDestination bishop moves") {
    val emptyBoard = Board(Vector.fill(8, 8)(None))
    val bishop = Piece(Color.White, PieceType.Bishop)
    assert(emptyBoard.validDestination(bishop, 0, 0, 7, 7)) // diagonal
    assert(!emptyBoard.validDestination(bishop, 0, 0, 0, 7)) // not diagonal
  }

  test("Board validDestination rook moves") {
    val emptyBoard = Board(Vector.fill(8, 8)(None))
    val rook = Piece(Color.White, PieceType.Rook)
    assert(emptyBoard.validDestination(rook, 0, 0, 0, 7)) // horizontal
    assert(emptyBoard.validDestination(rook, 0, 0, 7, 0)) // vertical
    assert(!emptyBoard.validDestination(rook, 0, 0, 7, 7)) // diagonal
  }

  test("Board validDestination queen moves") {
    val emptyBoard = Board(Vector.fill(8, 8)(None))
    val queen = Piece(Color.White, PieceType.Queen)
    assert(emptyBoard.validDestination(queen, 0, 0, 0, 7)) // horizontal
    assert(emptyBoard.validDestination(queen, 0, 0, 7, 7)) // diagonal
  }

  test("Board validDestination king moves") {
    val board = Board.initial
    val king = Piece(Color.White, PieceType.King)
    assert(board.validDestination(king, 0, 4, 0, 5)) // adjacent
    assert(board.validDestination(king, 0, 4, 1, 4))
    assert(!board.validDestination(king, 0, 4, 0, 6)) // too far
  }

  // Test hasKing
  test("Board hasKing detects kings") {
    val board = Board.initial
    assert(board.hasKing(Color.White))
    assert(board.hasKing(Color.Black))
    val noWhiteKing = board.updated(0, 4, None)
    assert(!noWhiteKing.hasKing(Color.White))
    assert(noWhiteKing.hasKing(Color.Black))
  }

  // Test move invalid cases more
  test("Board move off board") {
    val result = Board.initial.move("e2", "e9")
    assert(result.isLeft)
  }

  test("Board move to own piece") {
    val result = Board.initial.move("e1", "e2") // king to own pawn
    assert(result.isLeft)
  }

  // Test require in Board constructor
  test("Board constructor rejects invalid size") {
    intercept[IllegalArgumentException] {
      Board(Vector.fill(7, 8)(None))
    }
    intercept[IllegalArgumentException] {
      Board(Vector.fill(8, 7)(None))
    }
  }