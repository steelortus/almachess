package de.htwg.softwarearchitecture.almachess.messaging

import spray.json.*
import spray.json.DefaultJsonProtocol.*

/** One published game-move on Kafka topic `almachess.moves`.
  *
  *  - source: which endpoint emitted it ("api/move", "api/ai-move", …)
  *  - uci:    the actual move in UCI notation (e2e4)
  *  - fen:    board state *after* the move
  *  - gameId: groups the moves of one played game; rotates on /reset.
  *            Lets downstream analytics (Spark) aggregate per game.
  *  - status: game status *after* the move ("White to move",
  *            "checkmate - Black wins", "stalemate", …) so consumers can
  *            detect game endings without re-evaluating the FEN.
  *  - ts:     epoch millis at publish time
  */
final case class MoveEvent(
    source: String,
    uci:    String,
    fen:    String,
    gameId: String,
    status: String,
    ts:     Long
)

object MoveEvent:
  given RootJsonFormat[MoveEvent] = jsonFormat6(MoveEvent.apply)

  def encode(ev: MoveEvent): String = ev.toJson.compactPrint
  def decode(raw: String): Either[String, MoveEvent] =
    try Right(raw.parseJson.convertTo[MoveEvent])
    catch case ex: Throwable => Left(s"MoveEvent decode failed: ${ex.getMessage}")
