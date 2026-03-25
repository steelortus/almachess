package de.htwg.softwarearchitecture.almachess.Model

enum Color:
  case White, Black

enum PieceType:
  case King, Queen, Rook, Bishop, Knight, Pawn

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