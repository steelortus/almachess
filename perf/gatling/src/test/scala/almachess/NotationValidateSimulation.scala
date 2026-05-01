package almachess

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._

/** Gatling load test mirroring the k6 scenario: pound /notation/fen/validate
  * with a fixed pool of FENs at constant concurrency.
  *
  * Run:
  *   sbt "gatlingTests/Gatling/testOnly almachess.NotationValidateSimulation"
  *
  * Tunables (system properties, sbt -D):
  *   -Dbase.url=http://localhost:8084
  *   -Dusers=30
  *   -Dduration=30
  */
class NotationValidateSimulation extends Simulation {

  private val baseUrl   = System.getProperty("base.url", "http://localhost:8084")
  private val users     = System.getProperty("users", "30").toInt
  private val durSecs   = System.getProperty("duration", "30").toInt

  // Same fixture set as the k6 script — keeps before/after comparable.
  private val fens = Vector(
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
    "r1bq1rk1/pp2bppp/2n1pn2/2pp4/3P4/2PBPN2/PP3PPP/RNBQ1RK1 w - - 2 7",
    "r2qkb1r/pp2nppp/3p4/2pNN1B1/2BnP3/3P4/PPP2PPP/R2bK2R w KQkq - 1 0",
    "8/8/8/8/8/8/8/8 w - - 0 1",
    "rnbqkb1r/ppp1pppp/5n2/3p4/3P4/5N2/PPP1PPPP/RNBQKB1R w KQkq - 0 3"
  )

  // Looping feeder so the simulation can run as long as `during(...)` requests.
  private val fenFeeder = Iterator.continually(fens).flatten.map(f => Map("fen" -> f))

  private val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .shareConnections

  private val scn = scenario("FenValidate")
    .feed(fenFeeder)
    .during(durSecs.seconds) {
      exec(
        http("POST /notation/fen/validate")
          .post("/notation/fen/validate")
          .body(StringBody("""{"fen":"#{fen}"}"""))
          .check(status.is(200))
          .check(jsonPath("$.valid").is("true"))
      )
    }

  setUp(
    scn.inject(atOnceUsers(users))
  ).protocols(httpProtocol)
   .assertions(
     // Mirror k6 thresholds.
     global.responseTime.percentile(95).lt(200),
     global.responseTime.percentile(99).lt(500),
     global.failedRequests.percent.lt(1.0)
   )
}
