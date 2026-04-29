package de.htwg.softwarearchitecture.almachess.model

case class Piece(color: Color, tpe: PieceType):
  override def toString: String =
    val char = tpe match
      case PieceType.King   => 'K'
      case PieceType.Queen  => 'Q'
      case PieceType.Rook   => 'R'
      case PieceType.Bishop => 'B'
      case PieceType.Knight => 'N'
      case PieceType.Pawn   => 'P'
    if color == Color.White then char.toString else char.toLower.toString

  def fenChar: Char =
    val base = tpe match
      case PieceType.King   => 'k'
      case PieceType.Queen  => 'q'
      case PieceType.Rook   => 'r'
      case PieceType.Bishop => 'b'
      case PieceType.Knight => 'n'
      case PieceType.Pawn   => 'p'
    if color == Color.White then base.toUpper else base

  def unicode: String = (color, tpe) match
    case (Color.White, PieceType.King)   => "♔"
    case (Color.White, PieceType.Queen)  => "♕"
    case (Color.White, PieceType.Rook)   => "♖"
    case (Color.White, PieceType.Bishop) => "♗"
    case (Color.White, PieceType.Knight) => "♘"
    case (Color.White, PieceType.Pawn)   => "♙"
    case (Color.Black, PieceType.King)   => "♚"
    case (Color.Black, PieceType.Queen)  => "♛"
    case (Color.Black, PieceType.Rook)   => "♜"
    case (Color.Black, PieceType.Bishop) => "♝"
    case (Color.Black, PieceType.Knight) => "♞"
    case (Color.Black, PieceType.Pawn)   => "♟"

object Piece:
  def fromFenChar(c: Char): Option[Piece] =
    val color = if c.isUpper then Color.White else Color.Black
    c.toLower match
      case 'k' => Some(Piece(color, PieceType.King))
      case 'q' => Some(Piece(color, PieceType.Queen))
      case 'r' => Some(Piece(color, PieceType.Rook))
      case 'b' => Some(Piece(color, PieceType.Bishop))
      case 'n' => Some(Piece(color, PieceType.Knight))
      case 'p' => Some(Piece(color, PieceType.Pawn))
      case _   => None
