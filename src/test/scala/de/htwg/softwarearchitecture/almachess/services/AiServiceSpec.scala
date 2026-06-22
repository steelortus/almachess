package de.htwg.softwarearchitecture.almachess.services

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import de.htwg.softwarearchitecture.almachess.api.*
import de.htwg.softwarearchitecture.almachess.api.JsonFormats.given

class AiServiceSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  private val routes = AiService.route

  // Determine which backend the singleton picked at class-load time.
  // The local/CI default is "chess-ai" (no Stockfish on PATH); in the unlikely
  // case Stockfish is present we relax assertions or skip the affected test.
  private val stockfishActive: Boolean =
    Get("/ai/status") ~> routes ~> check {
      responseAs[AiStatusResponse].backend == "stockfish"
    }

  private val initialFen   = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val checkmateFen = "4Q1k1/5ppp/8/8/8/8/8/4K3 b - - 0 1" // Black mated

  "GET /health" should {
    "return ok" in {
      Get("/health") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[HealthResponse].status shouldBe "ok"
      }
    }
  }

  "GET /ai/status" should {
    "report backend metadata" in {
      Get("/ai/status") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val r = responseAs[AiStatusResponse]
        r.backend should (be("chess-ai") or be("stockfish"))
        r.engine should not be empty
      }
    }

    "report fallback=true and disabled when running without Stockfish" in {
      if stockfishActive then cancel("Stockfish detected — fallback test skipped")
      Get("/ai/status") ~> routes ~> check {
        val r = responseAs[AiStatusResponse]
        r.backend  shouldBe "chess-ai"
        r.engine   shouldBe "ChessAI (negamax)"
        r.enabled  shouldBe false
        r.fallback shouldBe true
      }
    }
  }

  "POST /ai/bestmove" should {
    "return a UCI move for a valid starting FEN" in {
      val req = BestMoveRequest(initialFen, depth = Some(2))
      Post("/ai/bestmove", req) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val r = responseAs[BestMoveResponse]
        r.move shouldBe defined
        r.move.get should fullyMatch regex """[a-h][1-8][a-h][1-8][qrbn]?"""
        r.error shouldBe None
      }
    }

    "honor a custom depth without errors" in {
      val req = BestMoveRequest(initialFen, depth = Some(1))
      Post("/ai/bestmove", req) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[BestMoveResponse].move shouldBe defined
      }
    }

    "reject an invalid FEN" in {
      val req = BestMoveRequest("not a fen", depth = Some(2))
      Post("/ai/bestmove", req) ~> routes ~> check {
        // ChessAI returns 400 BadRequest, Stockfish returns 422 — accept either.
        status.intValue should (be(400) or be(422))
        responseAs[BestMoveResponse].error shouldBe defined
      }
    }

    "report 'no legal moves' for a stalemate / checkmate position" in {
      val req = BestMoveRequest(checkmateFen, depth = Some(2))
      Post("/ai/bestmove", req) ~> routes ~> check {
        // ChessAI: 200 OK with move=None + error message.
        // Stockfish: 422 UnprocessableEntity with error.
        if stockfishActive then
          status shouldBe StatusCodes.UnprocessableEntity
          responseAs[BestMoveResponse].error shouldBe defined
        else
          status shouldBe StatusCodes.OK
          val r = responseAs[BestMoveResponse]
          r.move shouldBe None
          r.error shouldBe Some("no legal moves")
      }
    }
  }

  "POST /ai/evaluate" should {
    "return 501 Not Implemented when Stockfish is not available" in {
      if stockfishActive then cancel("Stockfish detected — fallback test skipped")
      val req = EvaluateRequest(initialFen, depth = Some(10))
      Post("/ai/evaluate", req) ~> routes ~> check {
        status shouldBe StatusCodes.NotImplemented
        val r = responseAs[EvaluateResponse]
        r.error shouldBe Some("evaluate requires Stockfish")
        r.centipawns shouldBe None
        r.bestMove   shouldBe None
      }
    }
  }
