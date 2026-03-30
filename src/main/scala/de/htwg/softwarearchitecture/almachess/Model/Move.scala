package de.htwg.softwarearchitecture.almachess.model

case class Move(
    from: Pos,
    to: Pos,
    promotion: Option[PieceType] = None
)

object Move:
  def parse(input: String): Either[String, Move] =
    val trimmed = input.trim
    if trimmed.length < 4 then Left("move must look like e2e4 or e7e8q")
    else
      val from = Pos.fromAlgebraic(trimmed.take(2))
      val to = Pos.fromAlgebraic(trimmed.slice(2, 4))
      val promo =
        if trimmed.length >= 5 then
          trimmed(4).toLower match
            case 'q' => Some(PieceType.Queen)
            case 'r' => Some(PieceType.Rook)
            case 'b' => Some(PieceType.Bishop)
            case 'n' => Some(PieceType.Knight)
            case _   => None
        else None
      (from, to) match
        case (Some(f), Some(t)) => Right(Move(f, t, promo))
        case _                  => Left(s"invalid move syntax: $input")
