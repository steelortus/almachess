package de.htwg.softwarearchitecture.almachess.Model

import munit.FunSuite

class testPiece extends FunSuite:
  test("Piece toString for white pieces") {
    assertEquals(Piece(Color.White, PieceType.King).toString, "K")
    assertEquals(Piece(Color.White, PieceType.Queen).toString, "Q")
    assertEquals(Piece(Color.White, PieceType.Rook).toString, "R")
    assertEquals(Piece(Color.White, PieceType.Bishop).toString, "B")
    assertEquals(Piece(Color.White, PieceType.Knight).toString, "N")
    assertEquals(Piece(Color.White, PieceType.Pawn).toString, "P")
  }

  test("Piece toString for black pieces") {
    assertEquals(Piece(Color.Black, PieceType.King).toString, "k")
    assertEquals(Piece(Color.Black, PieceType.Queen).toString, "q")
    assertEquals(Piece(Color.Black, PieceType.Rook).toString, "r")
    assertEquals(Piece(Color.Black, PieceType.Bishop).toString, "b")
    assertEquals(Piece(Color.Black, PieceType.Knight).toString, "n")
    assertEquals(Piece(Color.Black, PieceType.Pawn).toString, "p")
  }

  test("Piece equality") {
    val whiteKing1 = Piece(Color.White, PieceType.King)
    val whiteKing2 = Piece(Color.White, PieceType.King)
    val blackKing = Piece(Color.Black, PieceType.King)
    assertEquals(whiteKing1, whiteKing2)
    assertNotEquals(whiteKing1, blackKing)
  }