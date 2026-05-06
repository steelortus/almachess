package de.htwg.softwarearchitecture.almachess.parser

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class CommandParserSpec extends AnyWordSpec with Matchers:

  "CommandParser.parse" should {

    "parse a simple move" in {
      CommandParser.parse("move e2 e4") shouldBe Right(Command.MoveCmd("e2", "e4", None))
    }

    "parse a move with promotion" in {
      CommandParser.parse("move e7 e8 q") shouldBe Right(Command.MoveCmd("e7", "e8", Some("q")))
      CommandParser.parse("move a7 a8 N") shouldBe Right(Command.MoveCmd("a7", "a8", Some("N")))
    }

    "reject invalid move squares" in {
      CommandParser.parse("move z2 e4") shouldBe a[Left[_, _]]
      CommandParser.parse("move e9 e4") shouldBe a[Left[_, _]]
      CommandParser.parse("move e2")    shouldBe a[Left[_, _]]
    }

    "parse undo / redo / show / fen / pgn" in {
      CommandParser.parse("undo") shouldBe Right(Command.Undo)
      CommandParser.parse("redo") shouldBe Right(Command.Redo)
      CommandParser.parse("show") shouldBe Right(Command.Show)
      CommandParser.parse("fen")  shouldBe Right(Command.ShowFen)
      CommandParser.parse("pgn")  shouldBe Right(Command.ShowPgn)
    }

    "parse exit and help (with aliases)" in {
      CommandParser.parse("exit") shouldBe Right(Command.Exit)
      CommandParser.parse("quit") shouldBe Right(Command.Exit)
      CommandParser.parse("help") shouldBe Right(Command.Help)
      CommandParser.parse("?")    shouldBe Right(Command.Help)
    }

    "parse load with an explicit 'fen' keyword" in {
      val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
      CommandParser.parse(s"load fen $fen") shouldBe Right(Command.LoadFen(fen))
    }

    "parse load without the 'fen' keyword (legacy form)" in {
      val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
      CommandParser.parse(s"load $fen") shouldBe Right(Command.LoadFen(fen))
    }

    "parse load fen with a quoted string" in {
      val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
      CommandParser.parse(s"""load fen "$fen"""") shouldBe Right(Command.LoadFen(fen))
    }

    "parse export/import pgn and json" in {
      CommandParser.parse("export pgn game.pgn")  shouldBe Right(Command.ExportPgn("game.pgn"))
      CommandParser.parse("export json game.json") shouldBe Right(Command.ExportJson("game.json"))
      CommandParser.parse("import pgn game.pgn")  shouldBe Right(Command.ImportPgn("game.pgn"))
      CommandParser.parse("import json game.json") shouldBe Right(Command.ImportJson("game.json"))
    }

    "parse quoted paths containing spaces" in {
      CommandParser.parse("""export pgn "my games/game 1.pgn"""") shouldBe
        Right(Command.ExportPgn("my games/game 1.pgn"))
    }

    "parse set ai depth" in {
      CommandParser.parse("set ai depth 4")  shouldBe Right(Command.SetAiDepth(4))
      CommandParser.parse("set ai depth 10") shouldBe Right(Command.SetAiDepth(10))
    }

    "parse enable/disable ai" in {
      CommandParser.parse("ai on white") shouldBe Right(Command.EnableAi("white"))
      CommandParser.parse("ai on black") shouldBe Right(Command.EnableAi("black"))
      CommandParser.parse("ai off")      shouldBe Right(Command.DisableAi)
    }

    "tolerate leading / trailing whitespace" in {
      CommandParser.parse("   undo   ")       shouldBe Right(Command.Undo)
      CommandParser.parse("  move e2 e4  ")   shouldBe Right(Command.MoveCmd("e2", "e4", None))
    }

    "reject empty input" in {
      CommandParser.parse("")    shouldBe a[Left[_, _]]
      CommandParser.parse("   ") shouldBe a[Left[_, _]]
    }

    "reject unknown commands" in {
      CommandParser.parse("dance") shouldBe a[Left[_, _]]
      CommandParser.parse("move")  shouldBe a[Left[_, _]]
      CommandParser.parse("ai on green") shouldBe a[Left[_, _]]
    }

    "reject extra tokens after a complete command" in {
      CommandParser.parse("undo extra")             shouldBe a[Left[_, _]]
      CommandParser.parse("ai off bogus")           shouldBe a[Left[_, _]]
      CommandParser.parse("set ai depth 4 garbage") shouldBe a[Left[_, _]]
    }

    "reject an unbalanced quoted argument" in {
      CommandParser.parse("""export pgn "missing-close""") shouldBe a[Left[_, _]]
    }

    "reject an invalid promotion character" in {
      CommandParser.parse("move e7 e8 z") shouldBe a[Left[_, _]]
      CommandParser.parse("move e7 e8 5") shouldBe a[Left[_, _]]
    }

    "reject a non-numeric ai depth" in {
      CommandParser.parse("set ai depth deep") shouldBe a[Left[_, _]]
    }

    "accept an empty quoted path (parser-level)" in {
      // The parser doesn't validate the path content — only the grammar.
      CommandParser.parse("""export pgn """"") shouldBe Right(Command.ExportPgn(""))
    }

    "reject export/import without a path argument" in {
      CommandParser.parse("export pgn")  shouldBe a[Left[_, _]]
      CommandParser.parse("import json") shouldBe a[Left[_, _]]
    }
  }
