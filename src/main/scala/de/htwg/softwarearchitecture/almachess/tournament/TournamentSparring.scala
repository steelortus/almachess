package de.htwg.softwarearchitecture.almachess.tournament

import de.htwg.softwarearchitecture.almachess.clients.AiClient
import de.htwg.softwarearchitecture.almachess.control.Controller
import de.htwg.softwarearchitecture.almachess.model.Color
import spray.json.*

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal
import scala.util.Random

// Minimal random-move sparring bot used by the UI's "Quickstart"
// self-contained test. Joins the same tournament as AlMaChess and plays
// any legal move on its turn — purpose is to make the tournament actually
// progress so the operator can verify the whole pipeline works.
//
// Stays separate from TournamentBot on purpose:
//   - No engine call, no depth/skill knobs.
//   - Lightweight: one Controller per game, identical dedup pattern.
//   - Single-bot lifecycle (no multi-game per round across rounds is fine for a 1-round demo).
final class TournamentSparring(
    client: TournamentClient,
    log: TournamentLog,
    botId: String,
    token: String,
    tournamentId: String
)(using ec: ExecutionContext):

  private val sessions = ConcurrentHashMap.newKeySet[String]()
  private val done     = Promise[Unit]()
  private val rng      = new Random()

  def runUntilFinished(): Future[Unit] =
    log.info(s"[sparring] subscribing to /api/tournament/$tournamentId/stream as $botId")
    client.stream(s"/api/tournament/$tournamentId/stream", token)(handleLine)
      .foreach {
        case Left(err) if !done.isCompleted =>
          log.info(s"[sparring] tour stream closed: $err"); done.trySuccess(())
        case _ => done.trySuccess(())
      }
    done.future

  private def handleLine(line: String): Unit =
    try
      val ev = line.parseJson.asJsObject.fields
      ev.get("type").collect { case JsString(v) => v } match
        case Some("gameStart") =>
          val gid = ev.get("gameId").collect { case JsString(v) => v }.getOrElse("")
          val rnd = ev.get("round").collect { case JsNumber(n) => n.toInt }.getOrElse(0)
          if gid.nonEmpty && sessions.add(gid) then onGameStart(gid, rnd)
        case Some("tournamentFinished") => done.trySuccess(())
        case _ => ()
    catch case NonFatal(_) => ()

  private def onGameStart(gid: String, rnd: Int): Unit =
    client.getRound(tournamentId, rnd).foreach {
      case Right(pairings) =>
        val ours = pairings.find(_.gameIds.contains(gid)).flatMap { p =>
          if p.white == botId then Some("white")
          else if p.black == botId then Some("black")
          else None
        }
        ours match
          case Some(color) =>
            log.info(s"[sparring] play $gid as $color")
            playGame(gid, color)
          case None => sessions.remove(gid)
      case Left(_) => sessions.remove(gid)
    }

  private def playGame(gid: String, ourColor: String): Unit =
    client.getGameState(tournamentId, gid).foreach {
      case Right(gs) =>
        val controller = new Controller()
        controller.loadFen(gs.fen)
        maybeMove(controller, gid, ourColor)
        client.stream(s"/api/tournament/$tournamentId/game/$gid/stream", token) { line =>
          try
            val ev = line.parseJson.asJsObject.fields
            ev.get("type").collect { case JsString(v) => v } match
              case Some("move") | Some("gameState") =>
                ev.get("fen").collect { case JsString(f) => f }.foreach { fen =>
                  controller.loadFen(fen)
                  maybeMove(controller, gid, ourColor)
                }
              case Some("gameEnd") => () // gameStream closes on its own
              case _ => ()
          catch case NonFatal(_) => ()
        }.foreach(_ => ())
      case Left(err) => log.info(s"[sparring] could not fetch state for $gid: $err")
    }

  private def maybeMove(controller: Controller, gid: String, ourColor: String): Unit =
    val ourTurn = (if controller.state.turn == Color.White then "white" else "black") == ourColor
    if !ourTurn || controller.isGameOver then return
    val legal = controller.state.allLegalMoves().toList
    if legal.isEmpty then return
    val mv  = legal(rng.nextInt(legal.size))
    val uci = AiClient.moveToUci(mv)
    client.makeMove(token, tournamentId, gid, uci).foreach {
      case Right(_)  => () // server will echo on the stream; no log spam
      case Left(err) => log.info(s"[sparring] move $uci rejected: $err")
    }
