package de.htwg.softwarearchitecture.almachess.persistence

import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID
import scala.concurrent.ExecutionContext

/** Mongo integration test — runs only when ALMACHESS_TEST_MONGO_URI is set.
 *
 *  Example:
 *    ALMACHESS_TEST_MONGO_URI=mongodb://localhost:27017
 *    ALMACHESS_TEST_MONGO_DB=almachess_test
 *    sbt "testOnly de.htwg.softwarearchitecture.almachess.persistence.MongoGameRepositoryIT"
 */
class MongoGameRepositoryIT extends AnyWordSpec with Matchers with ScalaFutures with BeforeAndAfterAll:

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(20, Seconds), interval = Span(100, Millis))

  given ec: ExecutionContext = ExecutionContext.global

  private val uriOpt = sys.env.get("ALMACHESS_TEST_MONGO_URI").filter(_.nonEmpty)
  private val dbName = sys.env.getOrElse("ALMACHESS_TEST_MONGO_DB", "almachess_test")
  // Per-run collection name keeps parallel/concurrent runs from clobbering each other.
  private val collName = "games_" + UUID.randomUUID().toString.replace("-", "").take(8)

  private lazy val repo: MongoGameRepository =
    new MongoGameRepository(uriOpt.get, dbName, collName)

  override def afterAll(): Unit =
    if uriOpt.isDefined then repo.close()

  private def newId(): String = "test-" + UUID.randomUUID().toString.take(8)

  private def skipIfDisabled(): Unit =
    if uriOpt.isEmpty then cancel("ALMACHESS_TEST_MONGO_URI not set — skipping")

  "MongoGameRepository" should {
    "report its backend name" taggedAs IntegrationTest in {
      skipIfDisabled()
      repo.name shouldBe "mongo"
    }

    "round-trip save/load with all fields" taggedAs IntegrationTest in {
      skipIfDisabled()
      val id = newId()
      val dto = GameSaveDto(
        gameId     = id,
        currentFen = "fen-current",
        pgn        = "1. e4 *",
        moves      = List("e2e4", "e7e5"),
        status     = "Black to move",
        savedAt    = 1700000000L,
        initialFen = "fen-initial"
      )
      repo.save(dto).futureValue
      val loaded = repo.load(id).futureValue
      loaded shouldBe Some(dto)
      repo.delete(id).futureValue
    }

    "return None for a missing gameId" taggedAs IntegrationTest in {
      skipIfDisabled()
      repo.load("missing-" + UUID.randomUUID()).futureValue shouldBe None
    }

    "upsert on repeated save with the same gameId" taggedAs IntegrationTest in {
      skipIfDisabled()
      val id = newId()
      repo.save(GameSaveDto(id, "fen1", "pgn1", List("a2a3"),         "s1", 10L, "i1")).futureValue
      repo.save(GameSaveDto(id, "fen2", "pgn2", List("a2a3","a7a6"), "s2", 20L, "i2")).futureValue
      val loaded = repo.load(id).futureValue.get
      loaded.moves      shouldBe List("a2a3", "a7a6")
      loaded.currentFen shouldBe "fen2"
      loaded.savedAt    shouldBe 20L
      repo.delete(id).futureValue
    }

    "delete an existing entry" taggedAs IntegrationTest in {
      skipIfDisabled()
      val id = newId()
      repo.save(GameSaveDto(id, "fen", "pgn", Nil, "s", 1L, "fen")).futureValue
      repo.delete(id).futureValue
      repo.load(id).futureValue shouldBe None
    }

    "include saved games in list() ordered by savedAt desc" taggedAs IntegrationTest in {
      skipIfDisabled()
      val a = newId(); val b = newId(); val c = newId()
      repo.save(GameSaveDto(a, "f", "p", Nil, "s", 100L, "i")).futureValue
      repo.save(GameSaveDto(b, "f", "p", Nil, "s", 300L, "i")).futureValue
      repo.save(GameSaveDto(c, "f", "p", Nil, "s", 200L, "i")).futureValue
      val ids = repo.list().futureValue.map(_.gameId)
      ids should contain inOrderOnly(b, c, a)
      List(a, b, c).foreach(id => repo.delete(id).futureValue)
    }
  }
