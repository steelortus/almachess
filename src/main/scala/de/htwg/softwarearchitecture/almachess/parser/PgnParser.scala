package de.htwg.softwarearchitecture.almachess.parser

import fastparse.*
import MultiLineWhitespace.*

// Parses PGN (Portable Game Notation) into tags + list of raw SAN move tokens.
// Uses fastparse for the grammar.
object PgnParser:

  private def tagName[$: P]: P[String] =
    P(CharsWhile(c => c.isLetter || c.isDigit || c == '_')).!

  private def tagValue[$: P]: P[String] =
    P("\"" ~ CharsWhile(_ != '"').! ~ "\"")

  private def tag[$: P]: P[(String, String)] =
    P("[" ~ tagName ~ " " ~ tagValue ~ "]")

  // Game termination markers: "1-0", "0-1", "1/2-1/2", "*"
  private def result[$: P]: P[Unit] =
    P(StringIn("1-0", "0-1", "1/2-1/2", "*")).map(_ => ())

  // Move number: "1." or "1..." (black's continuation)
  private def moveNum[$: P]: P[Unit] =
    P(CharsWhile(_.isDigit) ~ "." ~ ".".rep).map(_ => ())

  // Comment: { ... }
  private def comment[$: P]: P[Unit] =
    P("{" ~ CharsWhile(_ != '}') ~ "}").map(_ => ())

  // Variation: ( ... ) — single-level only
  private def variation[$: P]: P[Unit] =
    P("(" ~ CharsWhile(c => c != '(' && c != ')').rep ~ ")").map(_ => ())

  // Valid characters inside a SAN token (after the first letter)
  private def isSanChar(c: Char): Boolean =
    c.isLetterOrDigit || c == '+' || c == '#' || c == '=' || c == '-' || c == 'x'

  // SAN move: starts with a letter (excludes move numbers and results)
  private def sanToken[$: P]: P[String] =
    P(!result ~ CharIn("a-zA-Z") ~ CharsWhile(isSanChar, 0)).!

  private def token[$: P]: P[Option[String]] = P(
    comment.map(_ => None)   |
    variation.map(_ => None) |
    result.map(_ => None)    |
    moveNum.map(_ => None)   |
    sanToken.map(Some(_))
  )

  private def pgnGame[$: P]: P[(Map[String, String], List[String])] =
    P(tag.rep ~ token.rep ~ End).map { case (tagSeq, tokens) =>
      (tagSeq.toMap, tokens.flatten.toList)
    }

  def parse(pgn: String): Either[String, (Map[String, String], List[String])] =
    fastparse.parse(pgn.trim, pgnGame(_)) match
      case Parsed.Success(value, _) => Right(value)
      case f: Parsed.Failure        => Left(s"PGN parse error: ${f.msg}")
