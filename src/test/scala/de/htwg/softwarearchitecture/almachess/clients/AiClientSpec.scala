package de.htwg.softwarearchitecture.almachess.clients

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import de.htwg.softwarearchitecture.almachess.api.{BestMoveResponse, ErrorResponse, JsonFormats}
import de.htwg.softwarearchitecture.almachess.model.{Move, PieceType, Pos}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*

import JsonFormats.given

class AiClientSpec extends AnyWordSpec with Matchers with ScalaFutures with BeforeAndAfterAll:

  given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "ai-client-spec")
  given ec: ExecutionContext = system.executionContext

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(50, Millis))

  private val initialFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  override def afterAll(): Unit =
    system.terminate()
    Await.ready(system.whenTerminated, 10.seconds)

  "AiClient.moveToUci" should {
    "encode a regular move" in {
      AiClient.moveToUci(Move(Pos(1, 4), Pos(3, 4))) shouldBe "e2e4"
    }

    "encode promotion as a single lowercase letter" in {
      AiClient.moveToUci(Move(Pos(6, 4), Pos(7, 4), Some(PieceType.Queen)))  shouldBe "e7e8q"
      AiClient.moveToUci(Move(Pos(6, 0), Pos(7, 0), Some(PieceType.Rook)))   shouldBe "a7a8r"
      AiClient.moveToUci(Move(Pos(6, 1), Pos(7, 1), Some(PieceType.Bishop))) shouldBe "b7b8b"
      AiClient.moveToUci(Move(Pos(6, 2), Pos(7, 2), Some(PieceType.Knight))) shouldBe "c7c8n"
    }
  }

  "AiClient.Local" should {
    "return a legal best move for a normal position" in {
      val client = new AiClient.Local(ec)
      val result = client.bestMove(initialFen, depth = 1).futureValue
      result shouldBe a[Right[_, _]]
      result.toOption.get should fullyMatch regex """[a-h][1-8][a-h][1-8][qrbn]?"""
    }

    "return Left for malformed FEN" in {
      val client = new AiClient.Local(ec)
      client.bestMove("not a fen", depth = 1).futureValue shouldBe a[Left[_, _]]
    }

    "return Left('no move found') for a stalemated position" in {
      val client  = new AiClient.Local(ec)
      val mateFen = "4Q1k1/5ppp/8/8/8/8/8/4K3 b - - 0 1" // black is mated
      val result  = client.bestMove(mateFen, depth = 1).futureValue
      result shouldBe Left("no move found")
    }
  }

  "AiClient.Http" should {

    "return Right(uci) when the server responds with a move" in {
      val route: Route = path("ai" / "bestmove") {
        post { complete(BestMoveResponse(Some("e2e4"), None)) }
      }
      val (binding, baseUrl) = bindRoute(route)
      try
        val client = new AiClient.Http(baseUrl)
        client.bestMove(initialFen, depth = 2).futureValue shouldBe Right("e2e4")
      finally
        Await.ready(binding.unbind(), 5.seconds)
    }

    "return Left when the server reports no move" in {
      val route: Route = path("ai" / "bestmove") {
        post { complete(BestMoveResponse(None, Some("no move"))) }
      }
      val (binding, baseUrl) = bindRoute(route)
      try
        val client = new AiClient.Http(baseUrl)
        client.bestMove(initialFen, depth = 2).futureValue shouldBe Left("no move")
      finally
        Await.ready(binding.unbind(), 5.seconds)
    }

    "fall back to a default error message when the server returns null move and null error" in {
      val route: Route = path("ai" / "bestmove") {
        post { complete(BestMoveResponse(None, None)) }
      }
      val (binding, baseUrl) = bindRoute(route)
      try
        val client = new AiClient.Http(baseUrl)
        val r = client.bestMove(initialFen, depth = 2).futureValue
        r shouldBe Left("ai service returned no move")
      finally
        Await.ready(binding.unbind(), 5.seconds)
    }
  }

  private def bindRoute(route: Route): (Http.ServerBinding, String) =
    val binding = Await.result(Http().newServerAt("127.0.0.1", 0).bind(route), 10.seconds)
    val url     = s"http://127.0.0.1:${binding.localAddress.getPort}"
    (binding, url)
