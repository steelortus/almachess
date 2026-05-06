package de.htwg.softwarearchitecture.almachess.persistence

import org.scalatest.{BeforeAndAfterAll, Tag}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID
import scala.concurrent.ExecutionContext

object IntegrationTest extends Tag("de.htwg.softwarearchitecture.almachess.IntegrationTest")

/** Postgres integration test — runs only when ALMACHESS_TEST_POSTGRES_URL is set.
 *
 *  Example:
 *    ALMACHESS_TEST_POSTGRES_URL=jdbc:postgresql://localhost:5432/almachess_test
 *    ALMACHESS_TEST_POSTGRES_USER=almachess
 *    ALMACHESS_TEST_POSTGRES_PASSWORD=almachess
 *    sbt "testOnly de.htwg.softwarearchitecture.almachess.persistence.PostgresGameRepositoryIT"
 */
class PostgresGameRepositoryIT extends AnyWordSpec with Matchers with ScalaFutures with BeforeAndAfterAll:

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(20, Seconds), interval = Span(100, Millis))

  given ec: ExecutionContext = ExecutionContext.global

  private val urlOpt  = sys.env.get("ALMACHESS_TEST_POSTGRES_URL").filter(_.nonEmpty)
  private val user    = sys.env.getOrElse("ALMACHESS_TEST_POSTGRES_USER", "almachess")
  private val pass    = sys.env.getOrElse("ALMACHESS_TEST_POSTGRES_PASSWORD", "almachess")

  private lazy val repo: PostgresGameRepository =
    new PostgresGameRepository(urlOpt.get, user, pass)

  override def afterAll(): Unit =
    if urlOpt.isDefined then repo.close()

  private def newId(): String = "test-" + UUID.randomUUID().toString.take(8)

  private def skipIfDisabled(): Unit =
    if urlOpt.isEmpty then cancel("ALMACHESS_TEST_POSTGRES_URL not set — skipping")

  "PostgresGameRepository" should {
    "report its backend name" taggedAs IntegrationTest in {
      skipIfDisabled()
      repo.name shouldBe "postgres"
    }

    "round-trip save/load with all fields" taggedAs IntegrationTest in {
      skipIfDisabled()
      val id = newId()
      val dto = GameSaveDto(
        gameId     = id,
        currentFen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
        pgn        = "1. e4 *",
        moves      = List("e2e4"),
        status     = "Black to move",
        savedAt    = 1700000000L,
        initialFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
      )
      repo.save(dto).futureValue
      val loaded = repo.load(id).futureValue
      loaded shouldBe defined
      loaded.get.gameId     shouldBe id
      loaded.get.currentFen shouldBe dto.currentFen
      loaded.get.initialFen shouldBe dto.initialFen
      loaded.get.pgn        shouldBe dto.pgn
      loaded.get.moves      shouldBe dto.moves
      loaded.get.status     shouldBe dto.status
      loaded.get.savedAt    shouldBe 1700000000L
      repo.delete(id).futureValue
    }

    "return None for a missing gameId" taggedAs IntegrationTest in {
      skipIfDisabled()
      repo.load("does-not-exist-" + UUID.randomUUID()).futureValue shouldBe None
    }

    "overwrite an existing game on save (upsert behavior)" taggedAs IntegrationTest in {
      skipIfDisabled()
      val id = newId()
      val v1 = GameSaveDto(id, "fen1", "pgn1", List("e2e4"),       "s1", 1L, "fen0")
      val v2 = GameSaveDto(id, "fen2", "pgn2", List("e2e4","e7e5"), "s2", 2L, "fen0")
      repo.save(v1).futureValue
      repo.save(v2).futureValue
      val loaded = repo.load(id).futureValue.get
      loaded.moves  shouldBe List("e2e4", "e7e5")
      loaded.status shouldBe "s2"
      loaded.pgn    shouldBe "pgn2"
      repo.delete(id).futureValue
    }

    "delete an existing entry" taggedAs IntegrationTest in {
      skipIfDisabled()
      val id = newId()
      repo.save(GameSaveDto(id, "fen", "pgn", Nil, "s", 1L, "fen")).futureValue
      repo.delete(id).futureValue
      repo.load(id).futureValue shouldBe None
    }

    "include saved games in list()" taggedAs IntegrationTest in {
      skipIfDisabled()
      val id = newId()
      repo.save(GameSaveDto(id, "fen", "pgn", Nil, "s", 99L, "fen")).futureValue
      val entries = repo.list().futureValue
      entries.map(_.gameId) should contain(id)
      repo.delete(id).futureValue
    }
  }
