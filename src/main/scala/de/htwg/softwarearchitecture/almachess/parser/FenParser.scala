package de.htwg.softwarearchitecture.almachess.parser

import de.htwg.softwarearchitecture.almachess.model.*

import scala.collection.immutable.VectorBuilder

object FenParser:

  // 128-entry lookup table for FEN piece chars. Index by char code for O(1)
  // dispatch and reuse the same Some(Piece) instance per char (no per-call alloc).
  private val pieceLookup: Array[Option[Piece]] =
    val arr = Array.fill[Option[Piece]](128)(None)
    for c <- "KQRBNPkqrbnp" do arr(c.toInt) = Piece.fromFenChar(c)
    arr

  private val emptySquare: Option[Piece] = None

  def parse(fen: String): Either[String, GameState] =
    val parts = fen.trim.split("\\s+")
    if parts.length != 6 then Left("FEN must contain 6 fields")
    else
      parseBoard(parts(0)) match
        case Left(err) => Left(err)
        case Right(board) =>
          parseTurn(parts(1)) match
            case Left(err) => Left(err)
            case Right(turn) =>
              parseCastleRights(parts(2)) match
                case Left(err) => Left(err)
                case Right(rights) =>
                  parseEnPassant(parts(3)) match
                    case Left(err) => Left(err)
                    case Right(ep) =>
                      parts(4).toIntOption match
                        case None => Left("invalid halfmove clock")
                        case Some(half) =>
                          parts(5).toIntOption match
                            case None => Left("invalid fullmove number")
                            case Some(full) =>
                              Right(GameState(board, turn, rights, ep, half, full, s"${turn} to move"))

  private def parseBoard(placement: String): Either[String, Board] =
    val rows = placement.split("/")
    if rows.length != 8 then Left("board placement must have 8 ranks")
    else
      // FEN ranks are top-down (rank 8 first), Board.squares is bottom-up
      // (index 0 == rank 1). Fill the array in reverse to match Board.initial.
      val ranks = new Array[Vector[Option[Piece]]](8)
      var i = 0
      var err: String = null
      while i < 8 && err == null do
        parseRank(rows(i)) match
          case Left(e)    => err = e
          case Right(row) => ranks(7 - i) = row
        i += 1
      if err != null then Left(err)
      else Right(Board(ranks.toVector))

  private def parseRank(rank: String): Either[String, Vector[Option[Piece]]] =
    val builder = new VectorBuilder[Option[Piece]]
    builder.sizeHint(8)
    var count = 0
    var i = 0
    var err: String = null
    val len = rank.length
    while i < len && err == null do
      val c = rank.charAt(i)
      if c >= '1' && c <= '8' then
        val n = c - '0'
        var k = 0
        while k < n do
          builder += emptySquare
          k += 1
        count += n
      else
        val p = if c.toInt < 128 then pieceLookup(c.toInt) else emptySquare
        if p.isEmpty then err = s"invalid FEN piece: $c"
        else
          builder += p
          count += 1
      i += 1
    if err != null then Left(err)
    else if count != 8 then Left(s"rank '$rank' does not have length 8")
    else Right(builder.result())

  private def parseTurn(s: String): Either[String, Color] = s match
    case "w" => Right(Color.White)
    case "b" => Right(Color.Black)
    case _   => Left("active color must be w or b")

  private def parseCastleRights(s: String): Either[String, CastleRights] =
    if s == "-" then Right(CastleRights(false, false, false, false))
    else
      Right(
        CastleRights(
          whiteKingSide = s.contains('K'),
          whiteQueenSide = s.contains('Q'),
          blackKingSide = s.contains('k'),
          blackQueenSide = s.contains('q')
        )
      )

  private def parseEnPassant(s: String): Either[String, Option[Pos]] =
    if s == "-" then Right(None)
    else Pos.fromAlgebraic(s).map(Some(_)).toRight("invalid en passant target")
