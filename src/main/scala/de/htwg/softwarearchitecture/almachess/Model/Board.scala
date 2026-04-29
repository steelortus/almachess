package de.htwg.softwarearchitecture.almachess.model

case class Board(squares: Vector[Vector[Option[Piece]]]):
  require(squares.length == 8 && squares.forall(_.length == 8), "board must be 8x8")

  def pieceAt(pos: Pos): Option[Piece] =
    if pos.isInside then squares(pos.rank)(pos.file) else None

  def updated(pos: Pos, piece: Option[Piece]): Board =
    copy(squares = squares.updated(pos.rank, squares(pos.rank).updated(pos.file, piece)))

  def map(f: Option[Piece] => Option[Piece]): Board =
    Board(squares.map(_.map(f)))
  def flatMap(f: Board => Board): Board = f(this)
  def foreach(f: Option[Piece] => Unit): Unit =
    squares.flatten.foreach(f)

  def positions: Vector[Pos] =
    (for
      r <- 0 until 8
      f <- 0 until 8
    yield Pos(r, f)).toVector

  def findKing(color: Color): Option[Pos] =
    positions.find(pieceAt(_).contains(Piece(color, PieceType.King)))

  def hasKing(color: Color): Boolean = findKing(color).nonEmpty

  def isEmpty(pos: Pos): Boolean = pieceAt(pos).isEmpty

  def isEnemy(pos: Pos, color: Color): Boolean = pieceAt(pos).exists(_.color != color)

  def clearPath(from: Pos, to: Pos): Boolean =
    val dr = Integer.signum(to.rank - from.rank)
    val df = Integer.signum(to.file - from.file)
    Iterator
      .iterate(from + (dr, df))(_ + (dr, df))
      .takeWhile(_ != to)
      .forall(isEmpty)

  def toAscii: String =
    val ranks = (7 to 0 by -1).map { r =>
      val row = (0 until 8).map { f =>
        pieceAt(Pos(r, f)).map(_.toString).getOrElse(".")
      }.mkString(" ")
      s"${r + 1} | $row"
    }
    val files = "   +----------------\n     a b c d e f g h"
    (ranks :+ files).mkString("\n")

  def toFenPlacement: String =
    (7 to 0 by -1).map { rank =>
      val row = (0 until 8).foldLeft((new StringBuilder, 0)) { case ((sb, empties), file) =>
        pieceAt(Pos(rank, file)) match
          case Some(piece) =>
            if empties > 0 then sb.append(empties)
            sb.append(piece.fenChar)
            (sb, 0)
          case None =>
            (sb, empties + 1)
      }
      val (sb, empties) = row
      if empties > 0 then sb.append(empties)
      sb.toString
    }.mkString("/")

object Board:
  val empty: Board = Board(Vector.fill(8, 8)(None))

  def initial: Board =
    def pawnRow(color: Color) = Vector.fill(8)(Some(Piece(color, PieceType.Pawn)))
    def backRow(color: Color) = Vector(
      Some(Piece(color, PieceType.Rook)),
      Some(Piece(color, PieceType.Knight)),
      Some(Piece(color, PieceType.Bishop)),
      Some(Piece(color, PieceType.Queen)),
      Some(Piece(color, PieceType.King)),
      Some(Piece(color, PieceType.Bishop)),
      Some(Piece(color, PieceType.Knight)),
      Some(Piece(color, PieceType.Rook))
    )
    Board(
      Vector(
        backRow(Color.White),
        pawnRow(Color.White),
        Vector.fill(8)(None),
        Vector.fill(8)(None),
        Vector.fill(8)(None),
        Vector.fill(8)(None),
        pawnRow(Color.Black),
        backRow(Color.Black)
      )
    )
