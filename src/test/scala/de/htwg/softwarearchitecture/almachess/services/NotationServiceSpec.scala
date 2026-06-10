package de.htwg.softwarearchitecture.almachess.services

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import de.htwg.softwarearchitecture.almachess.api.*
import de.htwg.softwarearchitecture.almachess.api.JsonFormats.given

class NotationServiceSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  private val routes = NotationService.route(None)

  "GET /health" should {
    "return ok" in {
      Get("/health") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[HealthResponse].status shouldBe "ok"
      }
    }
  }

  "POST /notation/fen/validate" should {
    "accept a valid FEN and echo the canonical form" in {
      val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
      Post("/notation/fen/validate", FenValidateRequest(fen)) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val r = responseAs[FenValidateResponse]
        r.valid shouldBe true
        r.fen   shouldBe Some(fen)
        r.error shouldBe None
      }
    }

    "report invalid FEN with an error message and 200 OK" in {
      Post("/notation/fen/validate", FenValidateRequest("not a fen")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val r = responseAs[FenValidateResponse]
        r.valid shouldBe false
        r.fen   shouldBe None
        r.error shouldBe defined
      }
    }

    "report invalid FEN for empty input" in {
      Post("/notation/fen/validate", FenValidateRequest("")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[FenValidateResponse].valid shouldBe false
      }
    }

    "report invalid FEN for board with wrong number of ranks" in {
      val bad = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP w KQkq - 0 1"
      Post("/notation/fen/validate", FenValidateRequest(bad)) ~> routes ~> check {
        responseAs[FenValidateResponse].valid shouldBe false
      }
    }
  }

  "POST /notation/pgn/parse" should {
    "parse a valid PGN with tags and moves" in {
      val pgn =
        """[Event "Test"]
          |[Site "Konstanz"]
          |
          |1. e4 e5 2. Nf3 Nc6 *""".stripMargin
      Post("/notation/pgn/parse", PgnParseRequest(pgn)) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val r = responseAs[PgnParseResponse]
        r.tags("Event") shouldBe "Test"
        r.tags("Site")  shouldBe "Konstanz"
        r.moves         shouldBe List("e4", "e5", "Nf3", "Nc6")
      }
    }

    "parse a PGN with only moves" in {
      Post("/notation/pgn/parse", PgnParseRequest("1. d4 d5 *")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[PgnParseResponse].moves shouldBe List("d4", "d5")
      }
    }

    "return an empty result for empty input" in {
      Post("/notation/pgn/parse", PgnParseRequest("")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val r = responseAs[PgnParseResponse]
        r.tags  shouldBe empty
        r.moves shouldBe empty
      }
    }

    "return 422 with an error message for malformed PGN" in {
      Post("/notation/pgn/parse", PgnParseRequest("[Event")) ~> routes ~> check {
        status shouldBe StatusCodes.UnprocessableEntity
        responseAs[ErrorResponse].error should not be empty
      }
    }
  }
