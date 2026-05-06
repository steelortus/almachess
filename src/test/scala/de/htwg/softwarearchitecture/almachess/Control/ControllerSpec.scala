package de.htwg.softwarearchitecture.almachess.control

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import de.htwg.softwarearchitecture.almachess.model.{GameState, Board, Color, Piece, PieceType, Pos, Move}
import de.htwg.softwarearchitecture.almachess.util.{GameEvent, Observer}

class ControllerSpec extends AnyWordSpec with Matchers:

  /** Observer that records every event for inspection. */
  private class RecordingObserver extends Observer:
    var events: List[GameEvent] = Nil
    def update(e: GameEvent): Unit = events = events :+ e

  "A Controller" should {
    "be instantiable" in {
      val controller = new Controller()
      controller shouldBe a[Controller]
    }

    "provide access to the current game state" in {
      val controller = new Controller()
      controller.state shouldBe a[GameState]
      controller.state.board shouldBe Board.initial
      controller.state.turn shouldBe Color.White
    }

    "provide ASCII representation" in {
      val controller = new Controller()
      val ascii = controller.ascii
      ascii should include("8 | r n b q k b n r")
      ascii should include("1 | R N B Q K B N R")
    }

    "provide FEN representation" in {
      val controller = new Controller()
      val fen = controller.toFen
      fen should startWith("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
    }

    "report game not over initially" in {
      val controller = new Controller()
      controller.isGameOver shouldBe false
    }

    "reset to initial state" in {
      val controller = new Controller()
      // Make some change if possible, but since Controller is immutable, reset creates new state
      controller.reset()
      controller.state shouldBe GameState.initial
    }

    "load FEN position" in {
      val controller = new Controller()
      val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
      val result = controller.loadFen(fen)
      result shouldBe a[Right[_, _]]
      result.right.get should include("FEN geladen")
    }

    "reject invalid FEN" in {
      val controller = new Controller()
      val result = controller.loadFen("invalid")
      result shouldBe a[Left[_, _]]
    }

    "parse and apply valid moves" in {
      val controller = new Controller()
      val result = controller.move("e2", "e4")
      result shouldBe a[Right[_, _]]
      controller.state.board.pieceAt(Pos(3, 4)) shouldBe Some(Piece(Color.White, PieceType.Pawn))
      controller.state.board.pieceAt(Pos(1, 4)) shouldBe None
    }

    "reject invalid moves" in {
      val controller = new Controller()
      val result = controller.move("e2", "e5")
      result shouldBe a[Left[_, _]]
    }

    "handle move with promotion" in {
      // Set up a position where pawn can promote
      val controller = new Controller()
      // Load a FEN where white pawn is on 7th rank and b8 is empty
      val fen = "r1bqkbnr/1Ppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
      controller.loadFen(fen)
      val result = controller.move("b7", "b8", Some("Q"))
      result shouldBe a[Right[_, _]]
      controller.state.board.pieceAt(Pos(7, 1)) shouldBe Some(Piece(Color.White, PieceType.Queen))
    }

    "parse move strings" in {
      val controller = new Controller()
      val result = controller.move("e2e4")
      result shouldBe a[Right[_, _]]
    }
  }

  "Controller undo/redo" should {
    "report canUndo and canRedo correctly" in {
      val c = new Controller()
      c.canUndo shouldBe false
      c.canRedo shouldBe false
      c.move("e2", "e4")
      c.canUndo shouldBe true
      c.canRedo shouldBe false
      c.undo()
      c.canUndo shouldBe false
      c.canRedo shouldBe true
    }

    "restore the previous position on undo" in {
      val c = new Controller()
      c.move("e2", "e4")
      c.undo() shouldBe a[Right[_, _]]
      c.state shouldBe GameState.initial
      c.state.board.pieceAt(Pos(1, 4)) shouldBe Some(Piece(Color.White, PieceType.Pawn))
      c.state.board.pieceAt(Pos(3, 4)) shouldBe None
    }

    "replay the move on redo" in {
      val c = new Controller()
      c.move("e2", "e4")
      c.undo()
      c.redo() shouldBe a[Right[_, _]]
      c.state.board.pieceAt(Pos(3, 4)) shouldBe Some(Piece(Color.White, PieceType.Pawn))
    }

    "fail undo when there is nothing to undo" in {
      new Controller().undo() shouldBe a[Left[_, _]]
    }

    "fail redo when there is nothing to redo" in {
      new Controller().redo() shouldBe a[Left[_, _]]
    }

    "clear the redo stack after a new move" in {
      val c = new Controller()
      c.move("e2", "e4")
      c.undo()
      c.canRedo shouldBe true
      c.move("d2", "d4")
      c.canRedo shouldBe false
    }

    "track moveHistory and lastMove" in {
      val c = new Controller()
      c.lastMove shouldBe None
      c.moveHistory shouldBe Nil
      c.move("e2", "e4")
      c.move("e7", "e5")
      c.moveHistory shouldBe List(
        Move(Pos(1, 4), Pos(3, 4)),
        Move(Pos(6, 4), Pos(4, 4))
      )
      c.lastMove shouldBe Some(Move(Pos(6, 4), Pos(4, 4)))
    }
  }

  "Controller loadSnapshot" should {
    "replay UCI moves from a starting FEN" in {
      val c = new Controller()
      val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
      val result = c.loadSnapshot(fen, List("e2e4", "e7e5", "g1f3"))
      result shouldBe a[Right[_, _]]
      result.toOption.get should include("3")
      c.moveHistory should have size 3
      c.initialFen shouldBe fen
    }

    "reject snapshot with malformed UCI move" in {
      val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
      new Controller().loadSnapshot(fen, List("e2e4", "??")) shouldBe a[Left[_, _]]
    }

    "reject snapshot with bad starting FEN" in {
      new Controller().loadSnapshot("not a fen", List("e2e4")) shouldBe a[Left[_, _]]
    }
  }

  "Controller PGN export/import" should {
    "produce a PGN containing tags and SAN moves" in {
      val c = new Controller()
      c.move("e2", "e4")
      c.move("e7", "e5")
      val pgn = c.exportPgn()
      pgn should include("[Event")
      pgn should include("1. e4 e5")
      pgn should include("*")
    }

    "round-trip PGN export then import" in {
      val source = new Controller()
      source.move("e2", "e4")
      source.move("e7", "e5")
      source.move("g1", "f3")
      val pgn = source.exportPgn()

      val target = new Controller()
      target.importPgn(pgn) shouldBe a[Right[_, _]]
      target.moveHistory should have size 3
      target.state.toFen shouldBe source.state.toFen
    }

    "import PGN with FEN tag and SetUp marker" in {
      val pgn =
        """[Event "Test"]
          |[SetUp "1"]
          |[FEN "8/4P3/8/8/8/8/8/k3K3 w - - 0 1"]
          |
          |1. e8=Q *""".stripMargin
      val c = new Controller()
      val result = c.importPgn(pgn)
      result shouldBe a[Right[_, _]]
      c.state.board.pieceAt(Pos(7, 4)) shouldBe Some(Piece(Color.White, PieceType.Queen))
    }

    "reject malformed PGN on import" in {
      new Controller().importPgn("[Event") shouldBe a[Left[_, _]]
    }

    "reject PGN with an illegal SAN move" in {
      val pgn = """[Event "Bad"]

1. Kxe5 *"""
      new Controller().importPgn(pgn) shouldBe a[Left[_, _]]
    }

    "show 1-0 result in PGN after black is checkmated" in {
      val c = new Controller()
      // Fool's mate (in 4 plies): 1.f3 e5 2.g4 Qh4#  — but that's black mating white.
      // Use: white delivers mate. Scholar's mate — 1.e4 e5 2.Bc4 Nc6 3.Qh5 Nf6?? 4.Qxf7#
      c.move("e2", "e4");  c.move("e7", "e5")
      c.move("f1", "c4");  c.move("b8", "c6")
      c.move("d1", "h5");  c.move("g8", "f6")
      c.move("h5", "f7")   // Qxf7#
      c.state.status should startWith("checkmate")
      c.exportPgn() should include("1-0")
    }
  }

  "Controller JSON export/import" should {
    "round-trip JSON export then import" in {
      val source = new Controller()
      source.move("e2", "e4")
      source.move("c7", "c5")
      val json = source.exportJson()
      json should include("e2e4")
      json should include("c7c5")

      val target = new Controller()
      target.importJson(json) shouldBe a[Right[_, _]]
      target.state.toFen shouldBe source.state.toFen
      target.moveHistory should have size 2
    }

    "reject malformed JSON" in {
      new Controller().importJson("not json") shouldBe a[Left[_, _]]
    }

    "reject JSON with an illegal UCI move" in {
      val json =
        """{"initialFen":"rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1","moves":["e2e5"],"tags":{}}"""
      new Controller().importJson(json) shouldBe a[Left[_, _]]
    }
  }

  "Controller AI mode" should {
    "default to no AI" in {
      val c = new Controller()
      c.aiMode shouldBe None
      c.isAiTurn shouldBe false
    }

    "enable and disable AI for a chosen color" in {
      val c = new Controller()
      c.enableAi(Color.Black, depth = 2)
      c.aiMode shouldBe Some(Color.Black)
      c.currentAiDepth shouldBe 2
      c.isAiTurn shouldBe false       // White to move
      c.move("e2", "e4")
      c.isAiTurn shouldBe true        // Black to move + AI is Black
      c.disableAi()
      c.aiMode shouldBe None
      c.isAiTurn shouldBe false
    }

    "update AI depth via setAiDepth" in {
      val c = new Controller()
      c.enableAi(Color.Black)
      c.setAiDepth(5)
      c.currentAiDepth shouldBe 5
    }

    "have aiMove play a legal move when it is the AI's turn" in {
      val c = new Controller()
      c.enableAi(Color.White, depth = 1)
      c.aiMove() shouldBe a[Right[_, _]]
      c.moveHistory should have size 1
    }
  }

  "Controller observer events" should {
    "fire BoardChanged and Status on a legal move" in {
      val c = new Controller()
      val obs = new RecordingObserver
      c.add(obs)
      c.move("e2", "e4")
      obs.events.collect { case e: GameEvent.BoardChanged => e } should have size 1
      obs.events.collect { case e: GameEvent.Status       => e } should have size 1
      obs.events.collect { case e: GameEvent.IllegalMove  => e } shouldBe empty
    }

    "fire IllegalMove on a rejected move" in {
      val c = new Controller()
      val obs = new RecordingObserver
      c.add(obs)
      c.move("e2", "e5")
      obs.events.collect { case e: GameEvent.IllegalMove => e } should have size 1
      obs.events.collect { case e: GameEvent.BoardChanged => e } shouldBe empty
    }

    "fire GameOver event on checkmate" in {
      val c = new Controller()
      c.move("e2", "e4");  c.move("e7", "e5")
      c.move("f1", "c4");  c.move("b8", "c6")
      c.move("d1", "h5");  c.move("g8", "f6")
      val obs = new RecordingObserver
      c.add(obs)
      c.move("h5", "f7")  // Qxf7#
      obs.events.collect { case e: GameEvent.GameOver => e } should have size 1
      obs.events.collect { case e: GameEvent.Status   => e } shouldBe empty
    }

    "fire BoardChanged + Status on undo and redo" in {
      val c = new Controller()
      c.move("e2", "e4")
      val obs = new RecordingObserver
      c.add(obs)
      c.undo()
      c.redo()
      obs.events.count(_.isInstanceOf[GameEvent.BoardChanged]) shouldBe 2
      obs.events.count(_.isInstanceOf[GameEvent.Status])       shouldBe 2
    }

    "fire BoardChanged + Status on reset()" in {
      val c = new Controller()
      val obs = new RecordingObserver
      c.add(obs)
      c.reset()
      obs.events.collect { case e: GameEvent.BoardChanged => e } should have size 1
      obs.events.collect { case e: GameEvent.Status       => e } should have size 1
    }

    "stop notifying a removed observer" in {
      val c = new Controller()
      val obs = new RecordingObserver
      c.add(obs)
      c.remove(obs)
      c.move("e2", "e4")
      obs.events shouldBe empty
    }

    "reject moves once the game is over" in {
      val c = new Controller()
      c.move("e2", "e4");  c.move("e7", "e5")
      c.move("f1", "c4");  c.move("b8", "c6")
      c.move("d1", "h5");  c.move("g8", "f6")
      c.move("h5", "f7")  // mate
      c.isGameOver shouldBe true
      c.move("a2", "a3") shouldBe a[Left[_, _]]
    }
  }

  "Controller promotion synonyms" should {
    "accept 'queen' / 'rook' / 'bishop' / 'knight' (case-insensitive)" in {
      val cases = List(
        "queen"  -> PieceType.Queen,
        "Queen"  -> PieceType.Queen,
        "ROOK"   -> PieceType.Rook,
        "bishop" -> PieceType.Bishop,
        "knight" -> PieceType.Knight
      )
      for (input, expected) <- cases do
        val c = new Controller()
        c.loadFen("8/4P3/8/8/8/8/8/k3K3 w - - 0 1")
        c.move("e7", "e8", Some(input)) shouldBe a[Right[_, _]]
        c.state.board.pieceAt(Pos(7, 4)) shouldBe Some(Piece(Color.White, expected))
    }

    "accept single letters Q / R / B / N (case-insensitive)" in {
      val cases = List(
        "Q" -> PieceType.Queen,
        "q" -> PieceType.Queen,
        "r" -> PieceType.Rook,
        "B" -> PieceType.Bishop,
        "n" -> PieceType.Knight
      )
      for (input, expected) <- cases do
        val c = new Controller()
        c.loadFen("8/4P3/8/8/8/8/8/k3K3 w - - 0 1")
        c.move("e7", "e8", Some(input)) shouldBe a[Right[_, _]]
        c.state.board.pieceAt(Pos(7, 4)) shouldBe Some(Piece(Color.White, expected))
    }

    "treat an unknown promotion string as default-to-queen" in {
      // parsePromotion returns None for an unrecognised string; applyMove then
      // promotes to Queen by default for a pawn reaching the last rank.
      val c = new Controller()
      c.loadFen("8/4P3/8/8/8/8/8/k3K3 w - - 0 1")
      c.move("e7", "e8", Some("unicorn")) shouldBe a[Right[_, _]]
      c.state.board.pieceAt(Pos(7, 4)) shouldBe Some(Piece(Color.White, PieceType.Queen))
    }
  }

  "Controller PGN result tag" should {
    "render 1/2-1/2 when the game ended in stalemate" in {
      val c = new Controller()
      // White Kf7, Qb6, Black Kh8. Move Qb6-g6 → stalemates Black.
      c.loadFen("7k/5K2/1Q6/8/8/8/8/8 w - - 0 1")
      c.move("b6", "g6") shouldBe a[Right[_, _]]
      c.state.status shouldBe "stalemate"
      c.exportPgn() should include("1/2-1/2")
    }

    "render 0-1 when White is checkmated (fool's mate)" in {
      val c = new Controller()
      c.move("f2", "f3");  c.move("e7", "e5")
      c.move("g2", "g4");  c.move("d8", "h4") // Qh4#
      c.state.status should startWith("checkmate")
      c.state.turn   shouldBe Color.White
      c.exportPgn() should include("0-1")
    }

    "render * for an in-progress game" in {
      val c = new Controller()
      c.move("e2", "e4")
      c.exportPgn() should include("*")
    }
  }

  "Controller importJson" should {
    "fall back to GameState.initial when the start FEN is invalid" in {
      val json = """{"initialFen":"NOT-A-FEN","moves":["e2e4"],"tags":{"Event":"X"}}"""
      val c    = new Controller()
      val r    = c.importJson(json)
      r shouldBe a[Right[_, _]]
      // Move was replayed from the standard initial position despite the bogus FEN.
      c.state.board.pieceAt(Pos(3, 4)) shouldBe Some(Piece(Color.White, PieceType.Pawn))
      c.moveHistory should have size 1
    }

    "reject when the moves are illegal from the fallback initial position" in {
      val json = """{"initialFen":"NOT-A-FEN","moves":["e2e9"],"tags":{}}"""
      new Controller().importJson(json) shouldBe a[Left[_, _]]
    }
  }