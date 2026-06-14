package de.htwg.softwarearchitecture.almachess.tools

import de.htwg.softwarearchitecture.almachess.control.Controller
import de.htwg.softwarearchitecture.almachess.messaging.MoveEvent

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

/** Generates `analytics/data/moves.jsonl` — sample input for the Spark
  * batch job (analytics/spark, GameStatsBatch).
  *
  * Each line is one MoveEvent JSON document, exactly the format the API
  * publishes on the Kafka topic `almachess.moves`. The games are played
  * through the real Controller, so FENs and status strings are guaranteed
  * to match what the live system would emit.
  *
  * Run: sbt "runMain de.htwg.softwarearchitecture.almachess.tools.AnalyticsSampleData"
  */
object AnalyticsSampleData:

  /** expectedEnding: prefix the final status must have — guards against
    * typos in the hardcoded move lists. None = game intentionally ongoing. */
  private final case class SampleGame(
      gameId:         String,
      whiteSource:    String,
      blackSource:    String,
      uciMoves:       List[String],
      expectedEnding: Option[String]
  )

  private val human = "api/move"
  private val ai    = "api/ai-move"

  private val games = List(
    // Fool's mate — Black (human) mates in 4 plies.
    SampleGame("game-fools-mate", ai, human,
      List("f2f3", "e7e5", "g2g4", "d8h4"),
      Some("checkmate - Black wins")),

    // Fool's mate variant — Black (AI) mates in 4 plies.
    SampleGame("game-fools-mate-2", human, ai,
      List("f2f4", "e7e6", "g2g4", "d8h4"),
      Some("checkmate - Black wins")),

    // Scholar's mate — White (human) mates in 7 plies.
    SampleGame("game-scholars-mate", human, ai,
      List("e2e4", "e7e5", "d1h5", "b8c6", "f1c4", "g8f6", "h5f7"),
      Some("checkmate - White wins")),

    // Légal's mate — White (AI) mates in 13 plies.
    SampleGame("game-legals-mate", ai, human,
      List("e2e4", "e7e5", "g1f3", "d7d6", "f1c4", "c8g4", "b1c3", "g7g6",
           "f3e5", "g4d1", "c4f7", "e8e7", "c3d5"),
      Some("checkmate - White wins")),

    // Sam Loyd's 10-move stalemate (shortest known) — 19 plies.
    SampleGame("game-loyd-stalemate", human, human,
      List("e2e3", "a7a5", "d1h5", "a8a6", "h5a5", "h7h5", "a5c7", "a6h6",
           "h2h4", "f7f6", "c7d7", "e8f7", "d7b7", "d8d3", "b7b8", "d3h7",
           "b8c8", "f7g6", "c8e6"),
      Some("stalemate")),

    // Sicilian, still running — shows up in move counts but not in victories.
    SampleGame("game-sicilian-ongoing", human, ai,
      List("e2e4", "c7c5", "g1f3", "d7d6", "d2d4", "c5d4", "f3d4", "g8f6"),
      None),

    // Queen's Gambit Declined, still running.
    SampleGame("game-qgd-ongoing", human, human,
      List("d2d4", "d7d5", "c2c4", "e7e6", "b1c3", "g8f6"),
      None)
  )

  def main(args: Array[String]): Unit =
    val out = Paths.get(args.headOption.getOrElse("analytics/data/moves.jsonl"))
    var ts  = 1735689600000L // 2025-01-01T00:00:00Z, +30s per ply

    val lines = games.flatMap { game =>
      val controller = new Controller()
      val events = game.uciMoves.zipWithIndex.map { case (uci, ply) =>
        controller.move(uci) match
          case Left(err) => sys.error(s"${game.gameId}: illegal move $uci (ply ${ply + 1}): $err")
          case Right(_)  => ()
        ts += 30000
        MoveEvent.encode(MoveEvent(
          source = if ply % 2 == 0 then game.whiteSource else game.blackSource,
          uci    = uci,
          fen    = controller.toFen,
          gameId = game.gameId,
          status = controller.state.status,
          ts     = ts
        ))
      }
      game.expectedEnding match
        case Some(expected) if !controller.state.status.startsWith(expected) =>
          sys.error(s"${game.gameId}: expected '$expected' but got '${controller.state.status}'")
        case None if controller.isGameOver =>
          sys.error(s"${game.gameId}: expected ongoing but game is over: ${controller.state.status}")
        case _ => ()
      events
    }

    Files.createDirectories(out.toAbsolutePath.getParent)
    Files.write(out, lines.asJava, StandardCharsets.UTF_8)
    println(s"${lines.size} MoveEvents (${games.size} Partien) -> ${out.toAbsolutePath}")
