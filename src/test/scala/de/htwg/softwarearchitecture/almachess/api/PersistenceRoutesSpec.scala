package de.htwg.softwarearchitecture.almachess.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import de.htwg.softwarearchitecture.almachess.control.Controller
import de.htwg.softwarearchitecture.almachess.persistence.{GameListEntry, GameRepository, GameSaveDto}

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

import JsonFormats.given

class PersistenceRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  /** In-memory fake repository. */
  private class InMemoryRepo extends GameRepository:
    val store = new TrieMap[String, GameSaveDto]()
    def name: String = "memory"
    def save(g: GameSaveDto): Future[Unit] =
      store.put(g.gameId, g); Future.successful(())
    def load(id: String): Future[Option[GameSaveDto]] = Future.successful(store.get(id))
    def delete(id: String): Future[Unit] = { store.remove(id); Future.successful(()) }
    def list(): Future[List[GameListEntry]] =
      Future.successful(store.values.map(d => GameListEntry(d.gameId, d.savedAt)).toList)

  /** Repository that always fails. */
  private class FailingRepo(err: String) extends GameRepository:
    def name: String = "failing"
    def save(g: GameSaveDto): Future[Unit]            = Future.failed(new RuntimeException(err))
    def load(id: String): Future[Option[GameSaveDto]] = Future.failed(new RuntimeException(err))
    def delete(id: String): Future[Unit]              = Future.failed(new RuntimeException(err))
    def list(): Future[List[GameListEntry]]           = Future.failed(new RuntimeException(err))

  private def buildRoutes(
      repo: Option[GameRepository] = None,
      controller: Controller = new Controller()
  ) =
    given ExecutionContext = system.dispatcher
    new PersistenceRoutes(controller, repo, new Object).all

  "GET /api/persistence/status" should {
    "report disabled when no repo is configured" in {
      Get("/api/persistence/status") ~> buildRoutes() ~> check {
        status shouldBe StatusCodes.OK
        val r = responseAs[PersistenceStatusResponse]
        r.enabled shouldBe false
        r.backend shouldBe "none"
      }
    }

    "report enabled when a repo is present" in {
      Get("/api/persistence/status") ~> buildRoutes(repo = Some(new InMemoryRepo)) ~> check {
        status shouldBe StatusCodes.OK
        val r = responseAs[PersistenceStatusResponse]
        r.enabled shouldBe true
        r.backend shouldBe "memory"
      }
    }
  }

  "Persistence routes when disabled" should {
    "return 503 for list" in {
      Get("/api/persistence/games") ~> buildRoutes() ~> check {
        status shouldBe StatusCodes.ServiceUnavailable
      }
    }
    "return 503 for save" in {
      Post("/api/persistence/games/g1") ~> buildRoutes() ~> check {
        status shouldBe StatusCodes.ServiceUnavailable
      }
    }
    "return 503 for load" in {
      Get("/api/persistence/games/g1") ~> buildRoutes() ~> check {
        status shouldBe StatusCodes.ServiceUnavailable
      }
    }
    "return 503 for delete" in {
      Delete("/api/persistence/games/g1") ~> buildRoutes() ~> check {
        status shouldBe StatusCodes.ServiceUnavailable
      }
    }
  }

  "POST /api/persistence/games/{id}" should {
    "save a snapshot of the current game" in {
      val controller = new Controller()
      controller.move("e2", "e4")
      val repo = new InMemoryRepo
      val routes = buildRoutes(Some(repo), controller)
      Post("/api/persistence/games/g1") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val dto = responseAs[PersistenceGameDto]
        dto.gameId shouldBe "g1"
        dto.moves  shouldBe List("e2e4")
      }
      repo.store should contain key "g1"
    }

    "return 500 on repo failure" in {
      val routes = buildRoutes(Some(new FailingRepo("boom")))
      Post("/api/persistence/games/g1") ~> routes ~> check {
        status shouldBe StatusCodes.InternalServerError
        responseAs[ErrorResponse].error should include("save failed")
      }
    }
  }

  "GET /api/persistence/games/{id}" should {
    "load a saved game and restore controller state" in {
      val source = new Controller()
      source.move("e2", "e4");  source.move("e7", "e5")
      val repo = new InMemoryRepo
      val savingRoutes = buildRoutes(Some(repo), source)
      Post("/api/persistence/games/g1") ~> savingRoutes ~> check {
        status shouldBe StatusCodes.OK
      }

      val target = new Controller()
      val loadingRoutes = buildRoutes(Some(repo), target)
      Get("/api/persistence/games/g1") ~> loadingRoutes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[PersistenceGameDto].moves shouldBe List("e2e4", "e7e5")
      }
      target.moveHistory should have size 2
      target.toFen shouldBe source.toFen
    }

    "return 404 when no entry exists" in {
      val routes = buildRoutes(Some(new InMemoryRepo))
      Get("/api/persistence/games/missing") ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }

    "return 500 on repo failure" in {
      val routes = buildRoutes(Some(new FailingRepo("oops")))
      Get("/api/persistence/games/g1") ~> routes ~> check {
        status shouldBe StatusCodes.InternalServerError
        responseAs[ErrorResponse].error should include("load failed")
      }
    }
  }

  "DELETE /api/persistence/games/{id}" should {
    "delete an entry" in {
      val repo = new InMemoryRepo
      repo.save(GameSaveDto("g1", "fen", "pgn", Nil, "white", 0, "fen"))
      Delete("/api/persistence/games/g1") ~> buildRoutes(Some(repo)) ~> check {
        status shouldBe StatusCodes.OK
        responseAs[SuccessResponse].message should include("g1")
      }
      repo.store should not contain key("g1")
    }

    "return 500 on repo failure" in {
      val routes = buildRoutes(Some(new FailingRepo("nope")))
      Delete("/api/persistence/games/g1") ~> routes ~> check {
        status shouldBe StatusCodes.InternalServerError
      }
    }
  }

  "GET /api/persistence/games" should {
    "list saved games" in {
      val repo = new InMemoryRepo
      repo.save(GameSaveDto("a", "fen", "pgn", Nil, "white", 1L, "fen"))
      repo.save(GameSaveDto("b", "fen", "pgn", Nil, "white", 2L, "fen"))
      Get("/api/persistence/games") ~> buildRoutes(Some(repo)) ~> check {
        status shouldBe StatusCodes.OK
        val r = responseAs[PersistenceListResponse]
        r.games.map(_.gameId) should contain allOf("a", "b")
      }
    }

    "return 500 when listing fails" in {
      Get("/api/persistence/games") ~> buildRoutes(Some(new FailingRepo("bad"))) ~> check {
        status shouldBe StatusCodes.InternalServerError
      }
    }
  }

  "Persistence snapshot restore branches" should {
    "fall back to GameState.initial when initialFen is empty" in {
      import de.htwg.softwarearchitecture.almachess.model.GameState
      val repo = new InMemoryRepo
      // initialFen field deliberately empty, moves still valid from the standard start.
      repo.save(GameSaveDto("g1", "irrelevant-currentFen", "1. e4 *", List("e2e4"),
                            "Black to move", savedAt = 1L, initialFen = ""))
      val controller = new Controller()
      Get("/api/persistence/games/g1") ~> buildRoutes(Some(repo), controller) ~> check {
        status shouldBe StatusCodes.OK
        // Replay started from GameState.initial (the fallback) and applied e2e4.
        controller.initialFen shouldBe GameState.initial.toFen
        controller.moveHistory should have size 1
      }
    }

    "fall back to currentFen when the snapshot moves cannot be replayed" in {
      val repo = new InMemoryRepo
      // Bogus moves but a perfectly loadable currentFen — exercises the
      // `loadSnapshot fails → loadFen(currentFen)` branch.
      val currentFen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
      repo.save(GameSaveDto("g2", currentFen, "", List("totally-bogus-uci"),
                            "Black to move", savedAt = 1L,
                            initialFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"))
      val controller = new Controller()
      Get("/api/persistence/games/g2") ~> buildRoutes(Some(repo), controller) ~> check {
        status shouldBe StatusCodes.OK
        controller.toFen shouldBe currentFen
        controller.moveHistory shouldBe empty
      }
    }

    "return 422 when both replay and currentFen fallback fail" in {
      val repo = new InMemoryRepo
      // Bogus moves AND bogus currentFen — both restore paths fail.
      repo.save(GameSaveDto("g3", "garbage-fen", "", List("nope"),
                            "x", savedAt = 1L, initialFen = "garbage-fen"))
      Get("/api/persistence/games/g3") ~> buildRoutes(Some(repo)) ~> check {
        status shouldBe StatusCodes.UnprocessableEntity
        responseAs[ErrorResponse].error should include("snapshot rejected")
      }
    }
  }
