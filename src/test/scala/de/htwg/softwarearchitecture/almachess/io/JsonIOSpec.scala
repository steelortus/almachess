package de.htwg.softwarearchitecture.almachess.io

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import de.htwg.softwarearchitecture.almachess.model.{Move, PieceType, Pos}

class JsonIOSpec extends AnyWordSpec with Matchers:

  private val initialFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  "JsonIO.exportGame" should {
    "encode initialFen, moves and tags as JSON" in {
      val moves = List(
        Move(Pos(1, 4), Pos(3, 4)),  // e2e4
        Move(Pos(6, 2), Pos(4, 2))   // c7c5
      )
      val json = JsonIO.exportGame(initialFen, moves, Map("Event" -> "Test"))
      json should include(initialFen)
      json should include("e2e4")
      json should include("c7c5")
      json should include("Event")
      json should include("Test")
    }

    "encode promotion suffix as a single lowercase letter" in {
      val moves = List(
        Move(Pos(6, 4), Pos(7, 4), Some(PieceType.Queen)),
        Move(Pos(6, 0), Pos(7, 0), Some(PieceType.Rook)),
        Move(Pos(6, 1), Pos(7, 1), Some(PieceType.Bishop)),
        Move(Pos(6, 2), Pos(7, 2), Some(PieceType.Knight))
      )
      val json = JsonIO.exportGame(initialFen, moves, Map.empty)
      json should include("e7e8q")
      json should include("a7a8r")
      json should include("b7b8b")
      json should include("c7c8n")
    }

    "produce valid JSON for an empty move list and empty tags" in {
      val json = JsonIO.exportGame(initialFen, Nil, Map.empty)
      val (fen, moves, tags) = JsonIO.importGame(json).toOption.get
      fen shouldBe initialFen
      moves shouldBe Nil
      tags shouldBe empty
    }
  }

  "JsonIO.importGame" should {
    "decode the same data that exportGame produced (round-trip)" in {
      val moves = List(
        Move(Pos(1, 4), Pos(3, 4)),
        Move(Pos(6, 4), Pos(4, 4)),
        Move(Pos(6, 0), Pos(7, 0), Some(PieceType.Knight))
      )
      val tags = Map("Event" -> "RT", "White" -> "Alice")
      val json = JsonIO.exportGame(initialFen, moves, tags)

      val result = JsonIO.importGame(json)
      result shouldBe a[Right[_, _]]
      val (fen, uciMoves, importedTags) = result.toOption.get
      fen shouldBe initialFen
      uciMoves shouldBe List("e2e4", "e7e5", "a7a8n")
      importedTags shouldBe tags
    }

    "reject malformed JSON with Left" in {
      JsonIO.importGame("not json")        shouldBe a[Left[_, _]]
      JsonIO.importGame("{")               shouldBe a[Left[_, _]]
      JsonIO.importGame("")                shouldBe a[Left[_, _]]
    }

    "reject JSON missing required fields" in {
      JsonIO.importGame("""{"initialFen":"x"}""") shouldBe a[Left[_, _]]
      JsonIO.importGame("""{"moves":[],"tags":{}}""") shouldBe a[Left[_, _]]
    }

    "decode JSON written by hand with all fields present" in {
      val json =
        """{"initialFen":"rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
          | "moves":["e2e4","c7c5"],
          | "tags":{"Site":"Konstanz"}}""".stripMargin
      val (fen, moves, tags) = JsonIO.importGame(json).toOption.get
      fen should startWith("rnbqkbnr")
      moves shouldBe List("e2e4", "c7c5")
      tags("Site") shouldBe "Konstanz"
    }
  }
