package de.htwg.softwarearchitecture.almachess.persistence

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import spray.json.*

class LiveGameJsonSpec extends AnyWordSpec with Matchers:

  private val sampleDto = GameSaveDto(
    gameId     = "g-42",
    currentFen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
    pgn        = "1. e4 *",
    moves      = List("e2e4"),
    status     = "Black to move",
    savedAt    = 1700000000L,
    initialFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  )

  "LiveGameJson.encode" should {
    "produce JSON containing every DTO field" in {
      val json = LiveGameJson.encode(sampleDto)
      json should include("\"gameId\":\"g-42\"")
      json should include("\"currentFen\"")
      json should include("\"initialFen\"")
      json should include("\"pgn\":\"1. e4 *\"")
      json should include("\"moves\":[\"e2e4\"]")
      json should include("\"status\":\"Black to move\"")
      json should include("\"savedAt\":1700000000")
    }

    "use compact (no-whitespace) printing" in {
      val json = LiveGameJson.encode(sampleDto)
      json should not include "\n"
      json should not include "  "
    }
  }

  "LiveGameJson.decode" should {
    "round-trip an encoded DTO back to an equal value" in {
      val json    = LiveGameJson.encode(sampleDto)
      val decoded = LiveGameJson.decode(json)
      decoded shouldBe sampleDto
    }

    "decode a hand-written JSON object with all fields" in {
      val json =
        """{"gameId":"abc","currentFen":"fen1","initialFen":"fen0",
          | "pgn":"1. e4 *","moves":["e2e4","c7c5"],
          | "status":"Black to move","savedAt":42}""".stripMargin
      val dto = LiveGameJson.decode(json)
      dto.gameId     shouldBe "abc"
      dto.currentFen shouldBe "fen1"
      dto.initialFen shouldBe "fen0"
      dto.pgn        shouldBe "1. e4 *"
      dto.moves      shouldBe List("e2e4", "c7c5")
      dto.status     shouldBe "Black to move"
      dto.savedAt    shouldBe 42L
    }

    "use empty defaults for missing string fields" in {
      val dto = LiveGameJson.decode("""{"savedAt":1}""")
      dto.gameId     shouldBe ""
      dto.currentFen shouldBe ""
      dto.initialFen shouldBe ""
      dto.pgn        shouldBe ""
      dto.status     shouldBe ""
      dto.savedAt    shouldBe 1L
    }

    "use 0 default for missing savedAt" in {
      val dto = LiveGameJson.decode("""{"gameId":"x"}""")
      dto.savedAt shouldBe 0L
      dto.gameId  shouldBe "x"
    }

    "default moves to an empty list when the field is absent" in {
      val dto = LiveGameJson.decode("""{"gameId":"x"}""")
      dto.moves shouldBe Nil
    }

    "skip non-string elements inside the moves array" in {
      val json = """{"gameId":"x","moves":["e2e4",42,null,"c7c5"]}"""
      LiveGameJson.decode(json).moves shouldBe List("e2e4", "c7c5")
    }

    "treat a non-array moves field as empty" in {
      val json = """{"gameId":"x","moves":"e2e4"}"""
      LiveGameJson.decode(json).moves shouldBe Nil
    }

    "raise on malformed JSON input" in {
      a[spray.json.JsonParser.ParsingException] should be thrownBy
        LiveGameJson.decode("not json")
    }

    "raise when the top level is not a JSON object" in {
      a[Throwable] should be thrownBy LiveGameJson.decode("[1,2,3]")
    }
  }
