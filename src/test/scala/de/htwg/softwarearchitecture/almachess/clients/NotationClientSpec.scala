package de.htwg.softwarearchitecture.almachess.clients

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import de.htwg.softwarearchitecture.almachess.api.*
import de.htwg.softwarearchitecture.almachess.api.JsonFormats.given

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*

class NotationClientSpec extends AnyWordSpec with Matchers with ScalaFutures with BeforeAndAfterAll:

  given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "notation-client-spec")
  given ec: ExecutionContext = system.executionContext

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(50, Millis))

  private val validFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  override def afterAll(): Unit =
    system.terminate()
    Await.ready(system.whenTerminated, 10.seconds)

  "NotationClient.Local" should {
    "validate a correct FEN" in {
      val client = new NotationClient.Local(ec)
      client.validateFen(validFen).futureValue shouldBe Right(validFen)
    }

    "reject a malformed FEN" in {
      val client = new NotationClient.Local(ec)
      client.validateFen("garbage").futureValue shouldBe a[Left[_, _]]
    }

    "parse a valid PGN with tags and moves" in {
      val client = new NotationClient.Local(ec)
      val pgn =
        """[Event "T"]
          |
          |1. e4 e5 *""".stripMargin
      val result = client.parsePgn(pgn).futureValue
      result shouldBe a[Right[_, _]]
      val (tags, moves) = result.toOption.get
      tags("Event") shouldBe "T"
      moves shouldBe List("e4", "e5")
    }

    "return Left for a malformed PGN" in {
      val client = new NotationClient.Local(ec)
      client.parsePgn("[Event").futureValue shouldBe a[Left[_, _]]
    }
  }

  "NotationClient.Http" should {
    "return canonical FEN on success" in {
      val route: Route = path("notation" / "fen" / "validate") {
        post {
          entity(as[FenValidateRequest]) { req =>
            complete(FenValidateResponse(true, Some(req.fen), None))
          }
        }
      }
      val (binding, baseUrl) = bindRoute(route)
      try
        val client = new NotationClient.Http(baseUrl)
        client.validateFen(validFen).futureValue shouldBe Right(validFen)
      finally
        Await.ready(binding.unbind(), 5.seconds)
    }

    "return Left when the server reports invalid FEN" in {
      val route: Route = path("notation" / "fen" / "validate") {
        post { complete(FenValidateResponse(false, None, Some("bad fen"))) }
      }
      val (binding, baseUrl) = bindRoute(route)
      try
        val client = new NotationClient.Http(baseUrl)
        client.validateFen("nope").futureValue shouldBe Left("bad fen")
      finally
        Await.ready(binding.unbind(), 5.seconds)
    }

    "fall back to default error when the server reports invalid with no message" in {
      val route: Route = path("notation" / "fen" / "validate") {
        post { complete(FenValidateResponse(false, None, None)) }
      }
      val (binding, baseUrl) = bindRoute(route)
      try
        val client = new NotationClient.Http(baseUrl)
        client.validateFen("x").futureValue shouldBe Left("invalid fen")
      finally
        Await.ready(binding.unbind(), 5.seconds)
    }

    "parse a PGN response into tags and moves" in {
      val route: Route = path("notation" / "pgn" / "parse") {
        post {
          complete(PgnParseResponse(Map("Event" -> "T"), List("e4", "e5")))
        }
      }
      val (binding, baseUrl) = bindRoute(route)
      try
        val client = new NotationClient.Http(baseUrl)
        val result = client.parsePgn("ignored").futureValue
        result shouldBe Right((Map("Event" -> "T"), List("e4", "e5")))
      finally
        Await.ready(binding.unbind(), 5.seconds)
    }

    "return Left when the PGN endpoint returns a failure status" in {
      val route: Route = path("notation" / "pgn" / "parse") {
        post { complete(StatusCodes.UnprocessableEntity -> ErrorResponse("bad pgn")) }
      }
      val (binding, baseUrl) = bindRoute(route)
      try
        val client = new NotationClient.Http(baseUrl)
        val result = client.parsePgn("garbage").futureValue
        result shouldBe a[Left[_, _]]
        result.left.toOption.get should include("notation service error")
      finally
        Await.ready(binding.unbind(), 5.seconds)
    }
  }

  private def bindRoute(route: Route): (Http.ServerBinding, String) =
    val binding = Await.result(Http().newServerAt("127.0.0.1", 0).bind(route), 10.seconds)
    val url     = s"http://127.0.0.1:${binding.localAddress.getPort}"
    (binding, url)
