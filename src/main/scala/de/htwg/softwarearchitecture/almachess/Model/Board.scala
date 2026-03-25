package de.htwg.softwarearchitecture.almachess.Model

import de.htwg.softwarearchitecture.almachess.util.{Observable, GameEvent}

case class Board(squares: Vector[Vector[Option[Piece]]]) extends Observable:
  require(squares.length == 8 && squares.forall(_.length == 8))

  def pieceAt(rank: Int, file: Int): Option[Piece] = squares(rank)(file)

  def toAscii: String =
    val ranks = (7 to 0 by -1).map { r =>
      val row = (0 until 8).map { f =>
        pieceAt(r, f).map(_.toString).getOrElse(".")
      }.mkString(" ")
      s"${r + 1} | $row"
    }
    val files = "  | " + (('a' to 'h').mkString(" "))
    (ranks :+ files).mkString("\n")

  def coords(square: String): Option[(Int, Int)] =
    if square.length != 2 then None
    else
      val file = square(0)
      val rank = square(1)
      val f = file - 'a'
      val r = rank - '1'
      if f >= 0 && f < 8 && r >= 0 && r < 8 then Some((r, f))
      else None

  def updated(rank: Int, file: Int, p: Option[Piece]): Board =
    copy(squares = squares.updated(rank, squares(rank).updated(file, p)))

  def move(from: String, to: String): Either[String, Board] =
    (coords(from), coords(to)) match
      case (Some((fr, ff)), Some((tr, tf))) =>
        pieceAt(fr, ff) match
          case None => Left(s"no piece at $from")
          case Some(piece) =>
            if !isInside(tr, tf) then Left("target off board")
            else if !validDestination(piece, fr, ff, tr, tf) then Left("illegal move for piece")
            else if pieceAt(tr, tf).exists(_.color == piece.color) then Left("cannot capture own piece")
            else
              val intermediate = updated(fr, ff, None)
              Right(intermediate.updated(tr, tf, Some(piece)))
      case _ => Left(s"invalid positions: $from -> $to")

  def isInside(r: Int, f: Int): Boolean = r >= 0 && r < 8 && f >= 0 && f < 8

  def isEmpty(r: Int, f: Int): Boolean =
    isInside(r, f) && pieceAt(r, f).isEmpty

  def isEnemy(r: Int, f: Int, color: Color): Boolean =
    pieceAt(r, f).exists(_.color != color)

  def clearPath(fr: Int, ff: Int, tr: Int, tf: Int): Boolean =
    val dr = Integer.signum(tr - fr)
    val df = Integer.signum(tf - ff)
    Iterator.iterate((fr + dr, ff + df))(p => (p._1 + dr, p._2 + df))
      .takeWhile(p => p != (tr, tf))
      .forall((r, f) => isEmpty(r, f))

  def validDestination(piece: Piece, fr: Int, ff: Int, tr: Int, tf: Int): Boolean =
    piece.tpe match
      case PieceType.Pawn =>
        val dir = if piece.color == Color.White then 1 else -1
        (tf == ff && tr == fr + dir && isEmpty(tr, tf)) ||
        (tf == ff && tr == fr + 2*dir && fr == (if piece.color == Color.White then 1 else 6) && isEmpty(fr+dir, ff) && isEmpty(tr, tf)) ||
        (math.abs(tf - ff) == 1 && tr == fr + dir && isEnemy(tr, tf, piece.color))
      case PieceType.Knight =>
        val dr = math.abs(tr - fr); val df = math.abs(tf - ff)
        (dr == 2 && df == 1) || (dr == 1 && df == 2)
      case PieceType.Bishop =>
        math.abs(tr - fr) == math.abs(tf - ff) && clearPath(fr, ff, tr, tf)
      case PieceType.Rook =>
        (fr == tr || ff == tf) && clearPath(fr, ff, tr, tf)
      case PieceType.Queen =>
        (fr == tr || ff == tf || math.abs(tr - fr) == math.abs(tf - ff)) && clearPath(fr, ff, tr, tf)
      case PieceType.King =>
        math.max(math.abs(tr - fr), math.abs(tf - ff)) == 1 
  
  def hasKing(color: Color): Boolean =
    squares.exists(_.exists(_.contains(Piece(color, PieceType.King))))

object Board:
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
    val rows = Vector(
      backRow(Color.White),
      pawnRow(Color.White),
      Vector.fill(8)(None),
      Vector.fill(8)(None),
      Vector.fill(8)(None),
      Vector.fill(8)(None),
      pawnRow(Color.Black),
      backRow(Color.Black)
    )
    Board(rows)