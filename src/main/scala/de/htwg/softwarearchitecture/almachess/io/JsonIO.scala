package de.htwg.softwarearchitecture.almachess.io

import de.htwg.softwarearchitecture.almachess.model.{Move, PieceType}
import upickle.default.*

private case class GameJson(
    initialFen: String,
    moves: List[String],
    tags: Map[String, String]
) derives ReadWriter

object JsonIO:

  def exportGame(initialFen: String, moves: List[Move], tags: Map[String, String]): String =
    val uciMoves = moves.map { m =>
      val promo = m.promotion.map {
        case PieceType.Queen  => "q"
        case PieceType.Rook   => "r"
        case PieceType.Bishop => "b"
        case PieceType.Knight => "n"
        case _                => ""
      }.getOrElse("")
      m.from.toAlgebraic + m.to.toAlgebraic + promo
    }
    write(GameJson(initialFen, uciMoves, tags), indent = 2)

  def importGame(json: String): Either[String, (String, List[String], Map[String, String])] =
    try
      val g = read[GameJson](json)
      Right((g.initialFen, g.moves, g.tags))
    catch
      case e: Exception => Left(s"JSON parse error: ${e.getMessage}")
