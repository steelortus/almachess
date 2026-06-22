package de.htwg.softwarearchitecture.almachess.persistence

import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{Eventually, PatienceConfiguration, ScalaFutures}
import org.scalatest.concurrent.PatienceConfiguration.{Interval, Timeout}
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID
import scala.concurrent.ExecutionContext

/** Redis integration test — runs only when ALMACHESS_TEST_REDIS_HOST is set.
 *
 *  Example:
 *    ALMACHESS_TEST_REDIS_HOST=localhost
 *    ALMACHESS_TEST_REDIS_PORT=6379
 *    sbt "testOnly de.htwg.softwarearchitecture.almachess.persistence.RedisLiveGameStoreIT"
 */
class RedisLiveGameStoreIT extends AnyWordSpec with Matchers
    with ScalaFutures with Eventually with BeforeAndAfterAll:

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(100, Millis))

  given ec: ExecutionContext = ExecutionContext.global

  private val hostOpt = sys.env.get("ALMACHESS_TEST_REDIS_HOST").filter(_.nonEmpty)
  private val port    = sys.env.get("ALMACHESS_TEST_REDIS_PORT").flatMap(_.toIntOption).getOrElse(6379)

  private lazy val store: RedisLiveGameStore =
    new RedisLiveGameStore(hostOpt.get, port, ttlSeconds = 60)

  override def afterAll(): Unit =
    if hostOpt.isDefined then store.close()

  private def newId(): String = "test-" + UUID.randomUUID().toString.take(8)

  private def skipIfDisabled(): Unit =
    if hostOpt.isEmpty then cancel("ALMACHESS_TEST_REDIS_HOST not set — skipping")

  private def sampleDto(id: String): GameSaveDto =
    GameSaveDto(
      gameId     = id,
      currentFen = "fen-current",
      pgn        = "1. e4 *",
      moves      = List("e2e4"),
      status     = "Black to move",
      savedAt    = 1700000000L,
      initialFen = "fen-initial"
    )

  "RedisLiveGameStore" should {
    "report its backend name and ttl" taggedAs IntegrationTest in {
      skipIfDisabled()
      store.name       shouldBe "redis"
      store.ttlSeconds shouldBe 60
    }

    "round-trip save/load with all fields" taggedAs IntegrationTest in {
      skipIfDisabled()
      val id  = newId()
      val dto = sampleDto(id)
      store.save(id, dto).futureValue
      store.load(id).futureValue shouldBe Some(dto)
      store.delete(id).futureValue
    }

    "return None for a missing sessionId" taggedAs IntegrationTest in {
      skipIfDisabled()
      store.load("missing-" + UUID.randomUUID()).futureValue shouldBe None
    }

    "overwrite a previous value on save" taggedAs IntegrationTest in {
      skipIfDisabled()
      val id = newId()
      store.save(id, sampleDto(id).copy(status = "v1")).futureValue
      store.save(id, sampleDto(id).copy(status = "v2")).futureValue
      store.load(id).futureValue.get.status shouldBe "v2"
      store.delete(id).futureValue
    }

    "delete an entry" taggedAs IntegrationTest in {
      skipIfDisabled()
      val id = newId()
      store.save(id, sampleDto(id)).futureValue
      store.delete(id).futureValue
      store.load(id).futureValue shouldBe None
    }

    "expire entries after the configured TTL" taggedAs IntegrationTest in {
      skipIfDisabled()
      val shortTtl = new RedisLiveGameStore(hostOpt.get, port, ttlSeconds = 1)
      try
        val id = newId()
        shortTtl.save(id, sampleDto(id)).futureValue
        eventually(Timeout(Span(5, Seconds)), Interval(Span(200, Millis))) {
          shortTtl.load(id).futureValue shouldBe None
        }
      finally
        shortTtl.close()
    }
  }
