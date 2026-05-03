package de.htwg.softwarearchitecture.almachess.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import de.htwg.softwarearchitecture.almachess.clients.{AiClient, NotationClient}
import de.htwg.softwarearchitecture.almachess.control.Controller
import de.htwg.softwarearchitecture.almachess.parser.{FenParser, PgnParser}
import spray.json.*

import scala.concurrent.{ExecutionContext, Future}

import JsonFormats.given

class RoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  // Local AiClient that uses ChessAI directly — avoids any external Stockfish dep.
  private def localAiClient(ec: ExecutionContext): AiClient = new AiClient.Local(ec)

  // Stub AiClient that always returns a fixed UCI move.
  private class FixedAiClient(uci: String) extends AiClient:
    def bestMove(fen: String, depth: Int, movetime: Option[Int], skill: Option[Int]): Future[Either[String, String]] =
      Future.successful(Right(uci))

  // Stub AiClient that always reports an error.
  private class FailingAiClient(err: String) extends AiClient:
    def bestMove(fen: String, depth: Int, movetime: Option[Int], skill: Option[Int]): Future[Either[String, String]] =
      Future.successful(Left(err))

  // Local NotationClient that uses parsers directly.
  private def localNotation(ec: ExecutionContext): NotationClient = new NotationClient.Local(ec)

  private def buildRoutes(
      ai: AiClient = new AiClient.Local(system.dispatcher),
      notation: NotationClient = new NotationClient.Local(system.dispatcher),
      controller: Controller = new Controller()
  ) =
    given ExecutionContext = system.dispatcher
    new Routes(controller, ai, notation).all

  "GET /health" should {
    "return ok" in {
      Get("/health") ~> buildRoutes() ~> check {
        status shouldBe StatusCodes.OK
        responseAs[HealthResponse].status shouldBe "ok"
      }
    }
  }

  "GET /api/game" should {
    "return the initial game state" in {
      Get("/api/game") ~> buildRoutes() ~> check {
        status shouldBe StatusCodes.OK
        val resp = responseAs[GameStateResponse]
        resp.fen      should startWith("rnbqkbnr")
        resp.turn     shouldBe "white"
        resp.canUndo  shouldBe false
        resp.canRedo  shouldBe false
        resp.gameOver shouldBe false
        resp.lastMove shouldBe None
      }
    }
  }

  "POST /api/game/reset" should {
    "reset the game and return state" in {
      val controller = new Controller()
      controller.move("e2", "e4")
      val routes = buildRoutes(controller = controller)
      Post("/api/game/reset") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[GameStateResponse].canUndo shouldBe false
      }
    }
  }

  "POST /api/game/move" should {
    "accept a legal move and return updated state" in {
      val routes = buildRoutes()
      val req = MoveRequest("e2", "e4")
      Post("/api/game/move", req) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val resp = responseAs[GameStateResponse]
        resp.turn     shouldBe "black"
        resp.canUndo  shouldBe true
        resp.lastMove shouldBe Some("e2e4")
      }
    }

    "reject an illegal move with 422" in {
      val routes = buildRoutes()
      Post("/api/game/move", MoveRequest("e2", "e5")) ~> routes ~> check {
        status shouldBe StatusCodes.UnprocessableEntity
        responseAs[ErrorResponse].error should not be empty
      }
    }

    "return 409 once the game is over" in {
      val controller = new Controller()
      // Scholar's mate
      controller.move("e2", "e4");  controller.move("e7", "e5")
      controller.move("f1", "c4");  controller.move("b8", "c6")
      controller.move("d1", "h5");  controller.move("g8", "f6")
      controller.move("h5", "f7")  // Qxf7#
      val routes = buildRoutes(controller = controller)
      Post("/api/game/move", MoveRequest("a2", "a3")) ~> routes ~> check {
        status shouldBe StatusCodes.Conflict
      }
    }
  }

  "POST /api/game/undo and /redo" should {
    "undo and redo a move" in {
      val controller = new Controller()
      val routes = buildRoutes(controller = controller)
      Post("/api/game/move", MoveRequest("e2", "e4")) ~> routes ~> check { status shouldBe StatusCodes.OK }

      Post("/api/game/undo") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val r = responseAs[GameStateResponse]
        r.canUndo shouldBe false
        r.canRedo shouldBe true
      }

      Post("/api/game/redo") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val r = responseAs[GameStateResponse]
        r.canUndo shouldBe true
        r.canRedo shouldBe false
      }
    }

    "return 409 when nothing to undo" in {
      Post("/api/game/undo") ~> buildRoutes() ~> check {
        status shouldBe StatusCodes.Conflict
        responseAs[ErrorResponse].error shouldBe "nothing to undo"
      }
    }

    "return 409 when nothing to redo" in {
      Post("/api/game/redo") ~> buildRoutes() ~> check {
        status shouldBe StatusCodes.Conflict
        responseAs[ErrorResponse].error shouldBe "nothing to redo"
      }
    }
  }

  "GET /api/game/legal-moves" should {
    "return all legal moves when no 'from' is given" in {
      Get("/api/game/legal-moves") ~> buildRoutes() ~> check {
        status shouldBe StatusCodes.OK
        val r = responseAs[LegalMovesResponse]
        r.from shouldBe None
        r.moves should have size 20 // initial position
      }
    }

    "return only moves from the requested square" in {
      Get("/api/game/legal-moves?from=e2") ~> buildRoutes() ~> check {
        status shouldBe StatusCodes.OK
        val r = responseAs[LegalMovesResponse]
        r.from  shouldBe Some("e2")
        r.moves should contain allOf("e2e3", "e2e4")
        r.moves should have size 2
      }
    }

    "return 400 for an invalid square" in {
      Get("/api/game/legal-moves?from=zz") ~> buildRoutes() ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }
  }

  "GET /api/game/history" should {
    "return moves in UCI" in {
      val controller = new Controller()
      controller.move("e2", "e4")
      controller.move("e7", "e5")
      val routes = buildRoutes(controller = controller)
      Get("/api/game/history") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[HistoryResponse].moves shouldBe List("e2e4", "e7e5")
      }
    }
  }

  "GET /api/fen" should {
    "return current FEN" in {
      Get("/api/fen") ~> buildRoutes() ~> check {
        status shouldBe StatusCodes.OK
        responseAs[FenResponse].fen should startWith("rnbqkbnr")
      }
    }
  }

  "POST /api/fen" should {
    "load a valid FEN" in {
      val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
      Post("/api/fen", FenLoadRequest(fen)) ~> buildRoutes() ~> check {
        status shouldBe StatusCodes.OK
        responseAs[SuccessResponse].fen shouldBe Some(fen)
      }
    }

    "reject an invalid FEN with 422" in {
      Post("/api/fen", FenLoadRequest("nope")) ~> buildRoutes() ~> check {
        status shouldBe StatusCodes.UnprocessableEntity
      }
    }
  }

  "GET /api/pgn" should {
    "return a PGN string" in {
      val controller = new Controller()
      controller.move("e2", "e4")
      val routes = buildRoutes(controller = controller)
      Get("/api/pgn") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[PgnResponse].pgn should include("1. e4")
      }
    }
  }

  "POST /api/pgn" should {
    "import a PGN" in {
      val pgn = """[Event "T"]

1. e4 e5 *"""
      Post("/api/pgn", PgnImportRequest(pgn)) ~> buildRoutes() ~> check {
        status shouldBe StatusCodes.OK
        responseAs[SuccessResponse].fen shouldBe defined
      }
    }

    "reject malformed PGN with 422" in {
      Post("/api/pgn", PgnImportRequest("[Event")) ~> buildRoutes() ~> check {
        status shouldBe StatusCodes.UnprocessableEntity
      }
    }
  }

  "POST /api/game/ai-move" should {
    "play a move returned by a fake AiClient" in {
      val controller = new Controller()
      val ai = new FixedAiClient("e2e4")
      val routes = buildRoutes(ai = ai, controller = controller)
      val body = HttpEntity(ContentTypes.`application/json`, """{"depth":2}""")
      Post("/api/game/ai-move", body) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val r = responseAs[AiMoveResponse]
        r.move shouldBe "e2e4"
        r.state.lastMove shouldBe Some("e2e4")
      }
    }

    "return 422 when the AI client reports an error" in {
      val ai = new FailingAiClient("no move")
      val routes = buildRoutes(ai = ai)
      val body = HttpEntity(ContentTypes.`application/json`, """{}""")
      Post("/api/game/ai-move", body) ~> routes ~> check {
        status shouldBe StatusCodes.UnprocessableEntity
        responseAs[ErrorResponse].error shouldBe "no move"
      }
    }

    "return 409 when the game is already over" in {
      val controller = new Controller()
      controller.move("e2", "e4");  controller.move("e7", "e5")
      controller.move("f1", "c4");  controller.move("b8", "c6")
      controller.move("d1", "h5");  controller.move("g8", "f6")
      controller.move("h5", "f7")
      val routes = buildRoutes(ai = new FixedAiClient("e2e4"), controller = controller)
      val body = HttpEntity(ContentTypes.`application/json`, """{}""")
      Post("/api/game/ai-move", body) ~> routes ~> check {
        status shouldBe StatusCodes.Conflict
      }
    }

    "return 422 when the AI returns an illegal move for the current position" in {
      val ai = new FixedAiClient("e7e5") // illegal: white to move
      val routes = buildRoutes(ai = ai)
      val body = HttpEntity(ContentTypes.`application/json`, """{}""")
      Post("/api/game/ai-move", body) ~> routes ~> check {
        status shouldBe StatusCodes.UnprocessableEntity
      }
    }
  }
