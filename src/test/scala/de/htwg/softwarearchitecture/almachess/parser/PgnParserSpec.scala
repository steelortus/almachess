package de.htwg.softwarearchitecture.almachess.parser

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class PgnParserSpec extends AnyWordSpec with Matchers:

  "PgnParser.parse" should {
    "parse PGN tags" in {
      val pgn = """[Event "Test"][Site "Online"] 1. e4 e5 *"""
      val result = PgnParser.parse(pgn)
      result shouldBe a[Right[_, _]]
      val (tags, moves) = result.toOption.get
      tags("Event") shouldBe "Test"
      tags("Site") shouldBe "Online"
      moves shouldBe List("e4", "e5")
    }

    "parse multiple tags across lines and a full move list" in {
      val pgn =
        """[Event "Test"]
          |[Site "Online"]
          |[Date "2026.01.01"]
          |
          |1. e4 e5 2. Nf3 Nc6 1-0""".stripMargin
      val (tags, moves) = PgnParser.parse(pgn).toOption.get
      tags should have size 3
      tags("Event") shouldBe "Test"
      tags("Date") shouldBe "2026.01.01"
      moves shouldBe List("e4", "e5", "Nf3", "Nc6")
    }

    "parse a simple move list without tags" in {
      val (_, moves) = PgnParser.parse("1. e4 e5 2. Nf3 Nc6 *").toOption.get
      moves shouldBe List("e4", "e5", "Nf3", "Nc6")
    }

    "accept all four result markers" in {
      PgnParser.parse("1. e4 1-0").toOption.get._2 shouldBe List("e4")
      PgnParser.parse("1. e4 0-1").toOption.get._2 shouldBe List("e4")
      PgnParser.parse("1. e4 1/2-1/2").toOption.get._2 shouldBe List("e4")
      PgnParser.parse("1. e4 *").toOption.get._2 shouldBe List("e4")
    }

    "ignore comments inside braces" in {
      val pgn = "1. e4 { strong central pawn } e5 *"
      val (_, moves) = PgnParser.parse(pgn).toOption.get
      moves shouldBe List("e4", "e5")
    }

    "ignore single-level variations in parentheses" in {
      val pgn = "1. e4 (1. d4 d5) e5 *"
      val (_, moves) = PgnParser.parse(pgn).toOption.get
      moves shouldBe List("e4", "e5")
    }

    "parse black continuation move numbers (1...)" in {
      val pgn = "1. e4 e5 2. Nf3 1... Nc6 *"
      val (_, moves) = PgnParser.parse(pgn).toOption.get
      moves shouldBe List("e4", "e5", "Nf3", "Nc6")
    }

    "parse castling moves" in {
      val pgn = "1. e4 e5 2. Nf3 Nc6 3. Bc4 Bc5 4. O-O O-O-O *"
      val (_, moves) = PgnParser.parse(pgn).toOption.get
      moves should contain("O-O")
      moves should contain("O-O-O")
    }

    "parse capture moves" in {
      val pgn = "1. e4 d5 2. exd5 Qxd5 *"
      val (_, moves) = PgnParser.parse(pgn).toOption.get
      moves shouldBe List("e4", "d5", "exd5", "Qxd5")
    }

    "parse promotion moves" in {
      val pgn = "1. e8=Q+ Kxe8 *"
      val (_, moves) = PgnParser.parse(pgn).toOption.get
      moves shouldBe List("e8=Q+", "Kxe8")
    }

    "parse check and mate suffixes on SAN tokens" in {
      val pgn = "1. Qh5 Nc6 2. Bc4 Nf6 3. Qxf7# *"
      val (_, moves) = PgnParser.parse(pgn).toOption.get
      moves should contain("Qxf7#")
      moves should contain("Qh5")
    }

    "parse disambiguated piece moves" in {
      val pgn = "1. Nbd2 Nxe5 2. R1e2 *"
      val (_, moves) = PgnParser.parse(pgn).toOption.get
      moves shouldBe List("Nbd2", "Nxe5", "R1e2")
    }

    "return Right with empty tags and moves for empty input" in {
      val result = PgnParser.parse("")
      result shouldBe a[Right[_, _]]
      val (tags, moves) = result.toOption.get
      tags shouldBe empty
      moves shouldBe empty
    }

    "reject malformed PGN with unterminated tag" in {
      PgnParser.parse("[Event") shouldBe a[Left[_, _]]
      PgnParser.parse("[Event \"x\"") shouldBe a[Left[_, _]]
    }

    "reject input that contains stray punctuation outside the grammar" in {
      PgnParser.parse("1. e4 ??? *") shouldBe a[Left[_, _]]
    }
  }
