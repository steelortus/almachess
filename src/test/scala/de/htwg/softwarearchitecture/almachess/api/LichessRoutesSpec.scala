package de.htwg.softwarearchitecture.almachess.api

import akka.Done
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import de.htwg.softwarearchitecture.almachess.clients.{AiClient, LichessChallengeCreated, LichessClientApi}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

import JsonFormats.given

class LichessRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  // Fake Lichess client. The HTTP stream is faked by feeding pre-canned NDJSON
  // lines into the onLine callback synchronously; onClosed is intentionally not
  // called, so the auto-reattach branch in LichessRoutes.startStream never fires
  // and tests don't fight reconnection logic.
  private class FakeLichessClient(
      override val baseUrl: String = "https://lichess.test",
      override val hasBoardToken: Boolean = true,
      override val hasBotToken: Boolean = true,
      challengeResult: Either[String, LichessChallengeCreated] =
        Right(LichessChallengeCreated("g123", "https://lichess.test/g123", "{}")),
      moveResult: Either[String, String] = Right("ok"),
      resignResult: Either[String, String] = Right("ok"),
      streamLines: List[String] = Nil
  ) extends LichessClientApi:
    val challengeCalls = ListBuffer.empty[(String, String, String, Int, Int, Option[Int], Boolean)]
    val moveCalls      = ListBuffer.empty[(String, String, String)]
    val resignCalls    = ListBuffer.empty[(String, String)]

    def createChallenge(
        username: String,
        mode: String,
        color: String,
        clockLimitSeconds: Int,
        clockIncrementSeconds: Int,
        daysOpt: Option[Int],
        rated: Boolean
    ): Future[Either[String, LichessChallengeCreated]] =
      challengeCalls += ((username, mode, color, clockLimitSeconds, clockIncrementSeconds, daysOpt, rated))
      Future.successful(challengeResult)

    def streamGameWithRetry(
        gameId: String,
        mode: String,
        attempts: Int = 30,
        delay: FiniteDuration = 1.second
    )(
        onLine: String => Unit,
        onClosed: Either[String, Done] => Unit
    ): Unit =
      streamLines.foreach(onLine)
      // Deliberately do NOT call onClosed — the route's reattach path would
      // otherwise recurse forever during synchronous test setup.

    def makeMove(gameId: String, mode: String, uci: String): Future[Either[String, String]] =
      moveCalls += ((gameId, mode, uci))
      Future.successful(moveResult)

    def resign(gameId: String, mode: String): Future[Either[String, String]] =
      resignCalls += ((gameId, mode))
      Future.successful(resignResult)

    def getChallengeState(challengeId: String, mode: String): Future[Either[String, (String, Option[String])]] =
      Future.successful(Right(("accepted", None)))

  private class FixedAi(uci: String) extends AiClient:
    def bestMove(fen: String, depth: Int, movetime: Option[Int], skill: Option[Int]) =
      Future.successful(Right(uci))

  private class FailingAi(err: String) extends AiClient:
    def bestMove(fen: String, depth: Int, movetime: Option[Int], skill: Option[Int]) =
      Future.successful(Left(err))

  private def routesWith(
      client: Option[LichessClientApi],
      ai: AiClient = new FixedAi("e2e4")
  ) =
    given ExecutionContext = system.dispatcher
    new LichessRoutes(client, ai).all

  private def challengeBody(
      mode: String = "board",
      color: String = "white",
      username: String = "maia1",
      timeControl: String = "5+0",
      rated: Boolean = false
  ): LichessChallengeRequest =
    LichessChallengeRequest(username, mode, color, timeControl, rated)

  "GET /api/lichess/status" should {
    "report disabled when no client is configured" in {
      Get("/api/lichess/status") ~> routesWith(None) ~> check {
        status shouldBe StatusCodes.OK
        val r = responseAs[LichessStatusResponse]
        r.enabled    shouldBe false
        r.boardToken shouldBe false
        r.botToken   shouldBe false
        r.session    shouldBe None
      }
    }

    "expose token flags from the configured client" in {
      val c = new FakeLichessClient(hasBoardToken = true, hasBotToken = false)
      Get("/api/lichess/status") ~> routesWith(Some(c)) ~> check {
        status shouldBe StatusCodes.OK
        val r = responseAs[LichessStatusResponse]
        r.enabled    shouldBe true
        r.boardToken shouldBe true
        r.botToken   shouldBe false
      }
    }
  }

  "POST /api/lichess/challenge" should {
    "return 503 when the integration is disabled" in {
      Post("/api/lichess/challenge", challengeBody()) ~> routesWith(None) ~> check {
        status shouldBe StatusCodes.ServiceUnavailable
      }
    }

    "reject an empty username with 400" in {
      val c = new FakeLichessClient()
      Post("/api/lichess/challenge", challengeBody(username = "")) ~> routesWith(Some(c)) ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }

    "reject a malformed time control with 400" in {
      val c = new FakeLichessClient()
      Post("/api/lichess/challenge", challengeBody(timeControl = "five-min")) ~> routesWith(Some(c)) ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }

    "reject bot mode when no bot token is configured" in {
      val c = new FakeLichessClient(hasBotToken = false)
      Post("/api/lichess/challenge", challengeBody(mode = "bot")) ~> routesWith(Some(c)) ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }

    "create a buffered session on success" in {
      val c = new FakeLichessClient()
      Post("/api/lichess/challenge", challengeBody()) ~> routesWith(Some(c)) ~> check {
        status shouldBe StatusCodes.OK
        val r = responseAs[LichessStatusResponse]
        r.session.isDefined shouldBe true
        val s = r.session.get
        s.gameId    shouldBe "g123"
        s.mode      shouldBe "board"
        s.yourColor shouldBe "white"
        s.opponent  shouldBe Some("maia1")
        s.moves     shouldBe Nil
        c.challengeCalls.size shouldBe 1
      }
    }

    "propagate Lichess errors as 502" in {
      val c = new FakeLichessClient(challengeResult = Left("lichess /api/challenge/maia1 -> 429"))
      Post("/api/lichess/challenge", challengeBody()) ~> routesWith(Some(c)) ~> check {
        status shouldBe StatusCodes.BadGateway
      }
    }

    "accept correspondence time control with 'Xd' syntax" in {
      val c = new FakeLichessClient()
      Post("/api/lichess/challenge", challengeBody(timeControl = "3d")) ~> routesWith(Some(c)) ~> check {
        status shouldBe StatusCodes.OK
        c.challengeCalls.size shouldBe 1
        val call = c.challengeCalls.head
        call._4 shouldBe 0          // clock.limit zeroed
        call._5 shouldBe 0          // clock.increment zeroed
        call._6 shouldBe Some(3)    // days
      }
    }

    "reject correspondence with out-of-range days" in {
      val c = new FakeLichessClient()
      Post("/api/lichess/challenge", challengeBody(timeControl = "30d")) ~> routesWith(Some(c)) ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }
  }

  "POST /api/lichess/move" should {
    "return 409 when no session is active" in {
      val c = new FakeLichessClient()
      Post("/api/lichess/move", LichessMoveRequest("e2", "e4")) ~> routesWith(Some(c)) ~> check {
        status shouldBe StatusCodes.Conflict
      }
    }

    "reject manual moves in bot mode with 400" in {
      val c = new FakeLichessClient()
      val routes = routesWith(Some(c))
      Post("/api/lichess/challenge", challengeBody(mode = "bot")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
      Post("/api/lichess/move", LichessMoveRequest("e2", "e4")) ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }

    "append a move on success and forward it to Lichess" in {
      val c = new FakeLichessClient()
      val routes = routesWith(Some(c))
      Post("/api/lichess/challenge", challengeBody()) ~> routes ~> check { status shouldBe StatusCodes.OK }
      Post("/api/lichess/move", LichessMoveRequest("e2", "e4")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val r = responseAs[LichessStatusResponse]
        r.session.get.moves shouldBe List("e2e4")
        c.moveCalls.size shouldBe 1
        c.moveCalls.head._3 shouldBe "e2e4"
      }
    }

    "reject malformed UCI input with 400" in {
      val c = new FakeLichessClient()
      val routes = routesWith(Some(c))
      Post("/api/lichess/challenge", challengeBody()) ~> routes ~> check { status shouldBe StatusCodes.OK }
      Post("/api/lichess/move", LichessMoveRequest("zz", "ee")) ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
      }
      c.moveCalls.isEmpty shouldBe true
    }

    "return 502 when Lichess rejects the move" in {
      val c = new FakeLichessClient(moveResult = Left("lichess move -> 400"))
      val routes = routesWith(Some(c))
      Post("/api/lichess/challenge", challengeBody()) ~> routes ~> check { status shouldBe StatusCodes.OK }
      Post("/api/lichess/move", LichessMoveRequest("e2", "e4")) ~> routes ~> check {
        status shouldBe StatusCodes.BadGateway
      }
    }
  }

  "POST /api/lichess/auto-move" should {
    "return 409 when no session is active" in {
      val c = new FakeLichessClient()
      Post("/api/lichess/auto-move", LichessAutoMoveRequest()) ~> routesWith(Some(c)) ~> check {
        status shouldBe StatusCodes.Conflict
      }
    }

    "reject auto-move in board mode with 400 (Fair Play)" in {
      val c = new FakeLichessClient()
      val routes = routesWith(Some(c))
      Post("/api/lichess/challenge", challengeBody(mode = "board")) ~> routes ~> check { status shouldBe StatusCodes.OK }
      Post("/api/lichess/auto-move", LichessAutoMoveRequest()) ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }

    "return 409 when it is not our turn" in {
      val c = new FakeLichessClient()
      val routes = routesWith(Some(c))
      // bot mode, color=black: we are black but white moves first → not our turn
      Post("/api/lichess/challenge", challengeBody(mode = "bot", color = "black")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
      Post("/api/lichess/auto-move", LichessAutoMoveRequest()) ~> routes ~> check {
        status shouldBe StatusCodes.Conflict
      }
      c.moveCalls.isEmpty shouldBe true
    }

    "ask the AI client and forward the UCI to Lichess" in {
      val c = new FakeLichessClient()
      val routes = routesWith(Some(c), ai = new FixedAi("d2d4"))
      Post("/api/lichess/challenge", challengeBody(mode = "bot", color = "white")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
      Post("/api/lichess/auto-move", LichessAutoMoveRequest()) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val r = responseAs[LichessAutoMoveResponse]
        r.move shouldBe "d2d4"
        r.status.session.get.moves shouldBe List("d2d4")
        c.moveCalls.size shouldBe 1
        c.moveCalls.head._2 shouldBe "bot"
        c.moveCalls.head._3 shouldBe "d2d4"
      }
    }

    "surface AI errors as 422" in {
      val c = new FakeLichessClient()
      val routes = routesWith(Some(c), ai = new FailingAi("no move found"))
      Post("/api/lichess/challenge", challengeBody(mode = "bot", color = "white")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
      Post("/api/lichess/auto-move", LichessAutoMoveRequest()) ~> routes ~> check {
        status shouldBe StatusCodes.UnprocessableEntity
      }
    }
  }

  "POST /api/lichess/resign" should {
    "return 409 without a session" in {
      Post("/api/lichess/resign") ~> routesWith(Some(new FakeLichessClient())) ~> check {
        status shouldBe StatusCodes.Conflict
      }
    }

    "mark the session as resigned on success" in {
      val c = new FakeLichessClient()
      val routes = routesWith(Some(c))
      Post("/api/lichess/challenge", challengeBody()) ~> routes ~> check { status shouldBe StatusCodes.OK }
      Post("/api/lichess/resign") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val r = responseAs[LichessStatusResponse]
        r.session.get.gameOver shouldBe true
        c.resignCalls.size shouldBe 1
      }
    }
  }

  "POST /api/lichess/disconnect" should {
    "clear an active session" in {
      val c = new FakeLichessClient()
      val routes = routesWith(Some(c))
      Post("/api/lichess/challenge", challengeBody()) ~> routes ~> check { status shouldBe StatusCodes.OK }
      Post("/api/lichess/disconnect") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[LichessStatusResponse].session shouldBe None
      }
    }
  }
