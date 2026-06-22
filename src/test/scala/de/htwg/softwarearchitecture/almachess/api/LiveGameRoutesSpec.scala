package de.htwg.softwarearchitecture.almachess.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import de.htwg.softwarearchitecture.almachess.control.Controller
import de.htwg.softwarearchitecture.almachess.persistence.{GameSaveDto, LiveGameStore}

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

import JsonFormats.given

class LiveGameRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  /** In-memory live store. */
  private class InMemoryStore(val ttlSeconds: Int = 60) extends LiveGameStore:
    val store = new TrieMap[String, GameSaveDto]()
    def name: String = "memory-live"
    def save(id: String, g: GameSaveDto): Future[Unit] =
      store.put(id, g); Future.successful(())
    def load(id: String): Future[Option[GameSaveDto]] = Future.successful(store.get(id))
    def delete(id: String): Future[Unit] = { store.remove(id); Future.successful(()) }

  /** Live store that always fails. */
  private class FailingStore(err: String) extends LiveGameStore:
    def name: String         = "failing-live"
    def ttlSeconds: Int      = 0
    def save(id: String, g: GameSaveDto): Future[Unit] = Future.failed(new RuntimeException(err))
    def load(id: String): Future[Option[GameSaveDto]]  = Future.failed(new RuntimeException(err))
    def delete(id: String): Future[Unit]               = Future.failed(new RuntimeException(err))

  private def buildRoutes(
      store: Option[LiveGameStore] = None,
      controller: Controller = new Controller()
  ) =
    given ExecutionContext = system.dispatcher
    new LiveGameRoutes(controller, store, new Object).all

  "GET /api/live/status" should {
    "report disabled when no store is configured" in {
      Get("/api/live/status") ~> buildRoutes() ~> check {
        status shouldBe StatusCodes.OK
        val r = responseAs[LiveStatusResponse]
        r.enabled    shouldBe false
        r.backend    shouldBe "none"
        r.ttlSeconds shouldBe 0
      }
    }

    "report enabled when a store is present" in {
      Get("/api/live/status") ~> buildRoutes(Some(new InMemoryStore(ttlSeconds = 120))) ~> check {
        status shouldBe StatusCodes.OK
        val r = responseAs[LiveStatusResponse]
        r.enabled    shouldBe true
        r.backend    shouldBe "memory-live"
        r.ttlSeconds shouldBe 120
      }
    }
  }

  "Live routes when disabled" should {
    "return 503 for save"   in { Post("/api/live/s1")   ~> buildRoutes() ~> check { status shouldBe StatusCodes.ServiceUnavailable } }
    "return 503 for load"   in { Get("/api/live/s1")    ~> buildRoutes() ~> check { status shouldBe StatusCodes.ServiceUnavailable } }
    "return 503 for delete" in { Delete("/api/live/s1") ~> buildRoutes() ~> check { status shouldBe StatusCodes.ServiceUnavailable } }
  }

  "POST /api/live/{sessionId}" should {
    "save the current snapshot" in {
      val controller = new Controller()
      controller.move("e2", "e4")
      val store  = new InMemoryStore()
      val routes = buildRoutes(Some(store), controller)
      Post("/api/live/s1") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val r = responseAs[LiveGameDto]
        r.gameId shouldBe "s1"
        r.moves  shouldBe List("e2e4")
      }
      store.store should contain key "s1"
    }

    "return 500 on store failure" in {
      val routes = buildRoutes(Some(new FailingStore("disk full")))
      Post("/api/live/s1") ~> routes ~> check {
        status shouldBe StatusCodes.InternalServerError
        responseAs[ErrorResponse].error should include("live save failed")
      }
    }
  }

  "GET /api/live/{sessionId}" should {
    "restore the controller state from the stored snapshot" in {
      val source = new Controller()
      source.move("d2", "d4");  source.move("d7", "d5")
      val store  = new InMemoryStore()

      Post("/api/live/s1") ~> buildRoutes(Some(store), source) ~> check {
        status shouldBe StatusCodes.OK
      }

      val target = new Controller()
      Get("/api/live/s1") ~> buildRoutes(Some(store), target) ~> check {
        status shouldBe StatusCodes.OK
        val dto = responseAs[LiveGameDto]
        dto.moves shouldBe List("d2d4", "d7d5")
      }
      target.toFen shouldBe source.toFen
      target.moveHistory should have size 2
    }

    "return 404 when nothing is stored for that session" in {
      Get("/api/live/missing") ~> buildRoutes(Some(new InMemoryStore())) ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }

    "return 422 if the stored snapshot cannot be applied" in {
      val store = new InMemoryStore()
      // Bad initialFen + bad currentFen — both restore paths fail.
      store.save("bad", GameSaveDto("bad", "garbage-fen", "", List("e2e4"), "x", 0L, "garbage-fen"))
      Get("/api/live/bad") ~> buildRoutes(Some(store)) ~> check {
        status shouldBe StatusCodes.UnprocessableEntity
        responseAs[ErrorResponse].error should include("snapshot rejected")
      }
    }

    "return 500 on store failure" in {
      Get("/api/live/s1") ~> buildRoutes(Some(new FailingStore("oops"))) ~> check {
        status shouldBe StatusCodes.InternalServerError
      }
    }
  }

  "DELETE /api/live/{sessionId}" should {
    "delete the live entry" in {
      val store = new InMemoryStore()
      store.save("s1", GameSaveDto("s1", "fen", "pgn", Nil, "x", 0L, "fen"))
      Delete("/api/live/s1") ~> buildRoutes(Some(store)) ~> check {
        status shouldBe StatusCodes.OK
        responseAs[SuccessResponse].message should include("s1")
      }
      store.store should not contain key("s1")
    }

    "return 500 on store failure" in {
      Delete("/api/live/s1") ~> buildRoutes(Some(new FailingStore("nope"))) ~> check {
        status shouldBe StatusCodes.InternalServerError
      }
    }
  }

  "Live snapshot restore branches" should {
    "fall back to GameState.initial when initialFen is empty" in {
      val store      = new InMemoryStore()
      val initialFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
      store.save("s1", GameSaveDto("s1", initialFen, "1. e4 *", List("e2e4"),
                                   "Black to move", savedAt = 1L, initialFen = ""))
      val controller = new Controller()
      Get("/api/live/s1") ~> buildRoutes(Some(store), controller) ~> check {
        status shouldBe StatusCodes.OK
        controller.moveHistory should have size 1
      }
    }

    "fall back to currentFen when snapshot moves can't be replayed" in {
      val store      = new InMemoryStore()
      val currentFen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
      store.save("s1", GameSaveDto("s1", currentFen, "", List("nonsense"),
                                   "Black to move", savedAt = 1L,
                                   initialFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"))
      val controller = new Controller()
      Get("/api/live/s1") ~> buildRoutes(Some(store), controller) ~> check {
        status shouldBe StatusCodes.OK
        controller.toFen shouldBe currentFen
        controller.moveHistory shouldBe empty
      }
    }
  }
