package de.htwg.softwarearchitecture.almachess.view

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import de.htwg.softwarearchitecture.almachess.control.Controller
import de.htwg.softwarearchitecture.almachess.model.{Color, Pos, Piece, PieceType}

import java.io.{BufferedReader, ByteArrayOutputStream, PrintStream, StringReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

class TuiSpec extends AnyWordSpec with Matchers:

  /** Runs the TUI with the given input and returns (captured stdout, controller). */
  private def runTui(
      input: String,
      controller: Controller = new Controller()
  ): (String, Controller) =
    val baos = new ByteArrayOutputStream()
    val ps   = new PrintStream(baos, true, "UTF-8")
    val rdr  = new BufferedReader(new StringReader(input))
    Console.withIn(rdr) {
      Console.withOut(ps) {
        new Tui(controller).run()
      }
    }
    (new String(baos.toByteArray, StandardCharsets.UTF_8), controller)

  private def tempPath(suffix: String): Path =
    val p = Files.createTempFile("almachess-tui-test", suffix)
    p.toFile.deleteOnExit()
    p

  "Tui REPL lifecycle" should {
    "print help on startup and terminate cleanly on EOF" in {
      val (out, _) = runTui("")
      out should include("AlmaChess TUI")
      out should include("Befehle:")
    }

    "exit on 'exit'" in {
      val (out, _) = runTui("exit\n")
      out should include("AlmaChess TUI")
    }

    "exit on 'quit'" in {
      val (_, _) = runTui("quit\n")
    }

    "ignore empty lines" in {
      val (out, c) = runTui("\n\n\nexit\n")
      out should include("AlmaChess TUI")
      c.moveHistory shouldBe empty
    }

    "report unknown commands" in {
      val (out, _) = runTui("frobnicate\nexit\n")
      out should include("[error]")
      out should include("unknown command")
    }
  }

  "Tui core display commands" should {
    "print the board on 'show'" in {
      val (out, _) = runTui("show\nexit\n")
      out should include("8 | r n b q k b n r")
    }

    "print FEN on 'fen'" in {
      val (out, _) = runTui("fen\nexit\n")
      out should include("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")
    }

    "print PGN on 'pgn'" in {
      val (out, _) = runTui("pgn\nexit\n")
      out should include("[Event")
    }

    "reprint help on 'help'" in {
      val (out, _) = runTui("help\nexit\n")
      out.split("AlmaChess TUI").length should be > 2 // initial + help command
    }
  }

  "Tui move commands" should {
    "execute a legal move" in {
      val (_, c) = runTui("move e2 e4\nexit\n")
      c.state.board.pieceAt(Pos(3, 4)) shouldBe Some(Piece(Color.White, PieceType.Pawn))
      c.moveHistory should have size 1
    }

    "report an error for an illegal move" in {
      val (out, c) = runTui("move e2 e5\nexit\n")
      out should include("[error]")
      c.moveHistory shouldBe empty
    }

    "execute a promotion move with promo piece" in {
      val controller = new Controller()
      controller.loadFen("8/4P3/8/8/8/8/8/k3K3 w - - 0 1")
      val (_, c) = runTui("move e7 e8 q\nexit\n", controller)
      c.state.board.pieceAt(Pos(7, 4)) shouldBe Some(Piece(Color.White, PieceType.Queen))
    }
  }

  "Tui undo/redo" should {
    "report an error when there's nothing to undo" in {
      val (out, _) = runTui("undo\nexit\n")
      out should include("[error]")
      out should include("nothing to undo")
    }

    "undo a previous move" in {
      val (out, c) = runTui("move e2 e4\nundo\nexit\n")
      out should include("[ok]")
      c.moveHistory shouldBe empty
    }

    "redo an undone move" in {
      val (_, c) = runTui("move e2 e4\nundo\nredo\nexit\n")
      c.moveHistory should have size 1
    }

    "report an error when there's nothing to redo" in {
      val (out, _) = runTui("redo\nexit\n")
      out should include("[error]")
      out should include("nothing to redo")
    }
  }

  "Tui load fen" should {
    "load a valid FEN" in {
      val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
      val (out, c) = runTui(s"load fen $fen\nexit\n")
      out should not include "[error]"
      c.toFen shouldBe fen
    }

    "report an error for an invalid FEN" in {
      val (out, _) = runTui("load fen garbage\nexit\n")
      out should include("[error]")
    }
  }

  "Tui PGN export/import" should {
    "export PGN to file and import it back" in {
      val path = tempPath(".pgn")

      val source = new Controller()
      source.move("e2", "e4")
      source.move("e7", "e5")

      val (_, _)        = runTui(s"""export pgn "$path"\nexit\n""", source)
      val written       = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
      written should include("1. e4")

      val target        = new Controller()
      val (out, loaded) = runTui(s"""import pgn "$path"\nexit\n""", target)
      out          should include("[ok]")
      loaded.moveHistory should have size 2
    }

    "report an error when importing a missing PGN file" in {
      val (out, _) = runTui("import pgn no-such-file.pgn\nexit\n")
      out should include("[error]")
    }
  }

  "Tui JSON export/import" should {
    "export JSON to file and import it back" in {
      val path   = tempPath(".json")
      val source = new Controller()
      source.move("d2", "d4")

      runTui(s"""export json "$path"\nexit\n""", source)
      val written = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
      written should include("d2d4")

      val target        = new Controller()
      val (out, loaded) = runTui(s"""import json "$path"\nexit\n""", target)
      out          should include("[ok]")
      loaded.moveHistory should have size 1
    }

    "report an error when importing a missing JSON file" in {
      val (out, _) = runTui("import json no-such-file.json\nexit\n")
      out should include("[error]")
    }
  }

  "Tui AI commands" should {
    "set AI depth" in {
      val (out, c) = runTui("set ai depth 4\nexit\n")
      out          should include("[ok]")
      c.currentAiDepth shouldBe 4
    }

    "enable AI for white" in {
      val (out, c) = runTui("ai on white\nexit\n")
      out          should include("[ok]")
      c.aiMode shouldBe Some(Color.White)
    }

    "enable AI for black" in {
      val (_, c) = runTui("ai on black\nexit\n")
      c.aiMode shouldBe Some(Color.Black)
    }

    "disable AI" in {
      val controller = new Controller()
      controller.enableAi(Color.White)
      val (_, c) = runTui("ai off\nexit\n", controller)
      c.aiMode shouldBe None
    }
  }
