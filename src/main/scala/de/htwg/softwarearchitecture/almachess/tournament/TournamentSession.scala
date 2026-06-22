package de.htwg.softwarearchitecture.almachess.tournament

import de.htwg.softwarearchitecture.almachess.clients.AiClient
import de.htwg.softwarearchitecture.almachess.control.Controller
import de.htwg.softwarearchitecture.almachess.model.Color
import spray.json.*

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

// One TournamentSession per game we play. Wraps a fresh AlMaChess
// Controller (single-game by design) so the existing Controller stays
// untouched while we still get multi-game support at the tournament
// layer: many parallel TournamentSessions, each with its own Controller.
//
// Responsibilities:
//   1. Sync the local Controller's FEN with each `move` event from the
//      server so allLegalMoves() reflects the right position.
//   2. When it's our colour to move, ask the AiClient for a UCI move and
//      POST it back to the server.
//   3. Tear down on `gameEnd`.
final class TournamentSession(
    client: TournamentClient,
    aiClient: AiClient,
    cfg: TournamentConfig,
    log: TournamentLog,
    token: String,
    tournamentId: String,
    gameId: String,
    ourColor: String
)(using ec: ExecutionContext):

  // Each session has its own Controller — preserves the "Controller is
  // single-game" invariant the rest of the codebase relies on.
  private val controller = new Controller()
  private val lock       = new Object
  @volatile private var done = false

  // Used to suppress double-submitting on the same position. The server
  // also enforces this with 403 "not your turn", but skipping the round
  // trip when we already moved avoids noise in the logs.
  @volatile private var lastSubmittedFen: String = ""

  def start(): Unit =
    client.getGameState(tournamentId, gameId).foreach {
      case Right(state) =>
        lock.synchronized {
          controller.loadFen(state.fen).left.foreach(err =>
            log.info(s"[tournament $gameId] load initial FEN failed: $err"))
        }
        maybeMakeMove()
        // Now subscribe to the game-event stream. Any move that flips
        // the turn to us triggers a fresh engine call.
        client.stream(s"/api/tournament/$tournamentId/game/$gameId/stream", token)(handleLine)
          .foreach {
            case Left(err) if !done => log.info(s"[tournament $gameId] stream closed: $err")
            case _                  => ()
          }
      case Left(err) =>
        log.info(s"[tournament $gameId] could not fetch initial state: $err")
    }

  private def handleLine(line: String): Unit =
    if done then return
    try
      val ev    = line.parseJson.asJsObject.fields
      val etype = ev.get("type").collect { case JsString(v) => v }.getOrElse("")
      etype match
        case "move" =>
          ev.get("fen").collect { case JsString(f) => f }.foreach { fen =>
            lock.synchronized {
              controller.loadFen(fen).left.foreach(err =>
                log.info(s"[tournament $gameId] FEN sync failed: $err"))
            }
            maybeMakeMove()
          }
        case "gameState" =>
          ev.get("fen").collect { case JsString(f) => f }.foreach { fen =>
            lock.synchronized(controller.loadFen(fen))
            maybeMakeMove()
          }
        case "gameEnd" =>
          val winner = ev.get("winner").collect { case JsString(v) => v }.getOrElse("?")
          val status = ev.get("status").collect { case JsString(v) => v }.getOrElse("?")
          log.info(s"[tournament $gameId] gameEnd winner=$winner status=$status")
          done = true
        case "heartbeat" => ()  // harmless keepalive
        case _ => ()
    catch case NonFatal(ex) =>
      log.info(s"[tournament $gameId] bad event: ${ex.getMessage} line=$line")

  private def turnString: String =
    if controller.state.turn == Color.White then "white" else "black"

  private def maybeMakeMove(): Unit =
    if done then return
    val (shouldMove, fenForEngine) = lock.synchronized {
      val fen = controller.toFen
      val ours = turnString == ourColor
      val notReplaying = fen != lastSubmittedFen
      (ours && notReplaying && !controller.isGameOver, fen)
    }
    if !shouldMove then return
    // Mark *before* the async ai call so a re-entry from a concurrent
    // event flip doesn't fire a second request for the same position.
    lastSubmittedFen = fenForEngine
    val askT0 = System.currentTimeMillis()
    log.info(s"[tournament $gameId] asking engine (depth=${cfg.aiDepth}, movetime=${cfg.movetimeMs.getOrElse(-1)}) for FEN ${fenForEngine.take(40)}...")
    aiClient.bestMove(fenForEngine, cfg.aiDepth, cfg.movetimeMs, cfg.skill).foreach { result =>
      val took = System.currentTimeMillis() - askT0
      log.info(s"[tournament $gameId] engine returned after ${took}ms")
      result match
        case Right(uci) =>
          client.makeMove(token, tournamentId, gameId, uci).foreach {
            case Right(_)  => log.info(s"[tournament $gameId] -> $uci")
            case Left(err) =>
              log.info(s"[tournament $gameId] move $uci rejected: $err")
              // Clear the gate so we re-evaluate on the next event.
              lastSubmittedFen = ""
          }
        case Left(err) =>
          log.info(s"[tournament $gameId] engine: $err")
          lastSubmittedFen = ""
    }
