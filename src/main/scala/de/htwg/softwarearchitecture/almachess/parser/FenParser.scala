package de.htwg.softwarearchitecture.almachess.parser

import de.htwg.softwarearchitecture.almachess.model.*

object FenParser:
  def parse(fen: String): Either[String, GameState] =
    val parts = fen.trim.split("\\s+")
    if parts.length != 6 then Left("FEN must contain 6 fields")
    else
      for
        board <- parseBoard(parts(0))
        turn <- parseTurn(parts(1))
        rights <- parseCastleRights(parts(2))
        ep <- parseEnPassant(parts(3))
        half <- parts(4).toIntOption.toRight("invalid halfmove clock")
        full <- parts(5).toIntOption.toRight("invalid fullmove number")
      yield GameState(board, turn, rights, ep, half, full, s"${turn} to move")

  private def parseBoard(placement: String): Either[String, Board] =
    val rows = placement.split("/")
    if rows.length != 8 then Left("board placement must have 8 ranks")
    else
      val parsedRows = rows.toVector.reverse.map(parseRank)
      if parsedRows.exists(_.isLeft) then parsedRows.collectFirst { case Left(err) => Left(err) }.get
      else Right(Board(parsedRows.collect { case Right(row) => row }))

  private def parseRank(rank: String): Either[String, Vector[Option[Piece]]] =
    rank.foldLeft[Either[String, Vector[Option[Piece]]]](Right(Vector.empty)) {
      case (Left(err), _) => Left(err)
      case (Right(acc), c) if c.isDigit =>
        val n = c.asDigit
        Right(acc ++ Vector.fill(n)(None))
      case (Right(acc), c) =>
        Piece.fromFenChar(c).toRight(s"invalid FEN piece: $c").map(p => acc :+ Some(p))
    }.flatMap { row =>
      Either.cond(row.length == 8, row, s"rank '$rank' does not have length 8")
    }

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
