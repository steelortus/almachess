package de.htwg.softwarearchitecture.almachess.model

case class CastleRights(
    whiteKingSide: Boolean = true,
    whiteQueenSide: Boolean = true,
    blackKingSide: Boolean = true,
    blackQueenSide: Boolean = true
):
  def fen: String =
    val s =
      (if whiteKingSide then "K" else "") +
        (if whiteQueenSide then "Q" else "") +
        (if blackKingSide then "k" else "") +
        (if blackQueenSide then "q" else "")
    if s.isEmpty then "-" else s
