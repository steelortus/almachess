package almachess

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._
import scala.util.Random

/** Mirrors `perf/k6/persistence_mix.js`: 60/25/10/5 GET/LIST/POST/DELETE
  * against the persistence API backed by Mongo. Run with the same
  * pre-seeded dataset (perf/mongo/seed.js).
  */
class PersistenceMixSimulation extends Simulation {

  private val baseUrl = System.getProperty("base.url", "http://localhost:8083")
  private val users   = System.getProperty("users", "20").toInt
  private val durSecs = System.getProperty("duration", "45").toInt
  private val seedTotal = 10000

  private val rng = new Random(42L)
  private def pad(n: Int, w: Int): String = ("%0" + w + "d").format(n)
  private def randomSeedId: String = "seed-" + pad(rng.nextInt(seedTotal), 5)

  private val idFeeder = Iterator.continually(Map(
    "id"   -> randomSeedId,
    "txId" -> ("seed-tx-" + pad(rng.nextInt(50000), 5))
  ))

  private val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .shareConnections

  private val getById = exec(
    http("GET /games/{id}")
      .get("/api/persistence/games/#{id}")
      .check(status.is(200))
  )

  private val listAll = exec(
    http("GET /games (list)")
      .get("/api/persistence/games")
      .check(status.is(200))
      .check(jsonPath("$.games").exists)
  )

  private val upsert = exec(
    http("POST /games/{id}")
      .post("/api/persistence/games/#{id}")
      .check(status.is(200))
  )

  private val deleteAndRestore = exec(
    http("POST seed tx (setup)")
      .post("/api/persistence/games/#{txId}")
      .check(status.is(200))
  ).exec(
    http("DELETE /games/{txId}")
      .delete("/api/persistence/games/#{txId}")
      .check(status.is(200))
  )

  private val mix = randomSwitch(
    60.0 -> getById,
    25.0 -> listAll,
    10.0 -> upsert,
     5.0 -> deleteAndRestore
  )

  private val scn = scenario("PersistenceMix")
    .feed(idFeeder)
    .during(durSecs.seconds)(mix)

  setUp(scn.inject(atOnceUsers(users)))
    .protocols(httpProtocol)
    .assertions(
      global.responseTime.percentile(95).lt(800),
      global.failedRequests.percent.lt(1.0),
      details("GET /games/{id}").responseTime.percentile(95).lt(200),
      details("POST /games/{id}").responseTime.percentile(95).lt(300),
      details("GET /games (list)").responseTime.percentile(95).lt(800)
    )
}
