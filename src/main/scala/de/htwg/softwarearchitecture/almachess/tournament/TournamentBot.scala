package de.htwg.softwarearchitecture.almachess.tournament

import de.htwg.softwarearchitecture.almachess.clients.AiClient
import spray.json.*

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

// Top-level orchestrator. Subscribes to the tournament event stream,
// dispatches `gameStart` events to per-game TournamentSessions, and
// completes the returned Promise once a `tournamentFinished` event
// arrives (so a caller can await the run).
//
// Key conventions baked in (see tournament/README for the why):
//   - The server broadcasts every `gameStart` (both colours) to every
//     stream subscriber, so we filter via GET /round/{n} to decide
//     whether THIS bot is in the just-started game.
//   - Sessions are deduped by gameId (each game emits two gameStart
//     events — one per colour).
final class TournamentBot(
    client: TournamentClient,
    aiClient: AiClient,
    cfg: TournamentConfig,
    log: TournamentLog,
    botId: String,        // our bot id (identity == registry on current server)
    token: String,
    tournamentId: String
)(using ec: ExecutionContext):

  private val sessions = ConcurrentHashMap.newKeySet[String]()
  private val done     = Promise[Unit]()

  // Future that completes when the tournament emits `tournamentFinished`
  // or the upstream stream closes.
  //
  // Also runs a one-shot "adopt currently-ongoing games" pass before the
  // stream subscription. The server has stream-catch-up for late
  // subscribers, but adopting via the round endpoint is independent of
  // whatever the stream replays — belt-and-braces for the case where the
  // bot starts after the tournament is already running.
  def runUntilFinished(): Future[Unit] =
    adoptOngoingGames()
    log.info(s"[tournament] subscribing to /api/tournament/$tournamentId/stream")
    client.stream(s"/api/tournament/$tournamentId/stream", token)(handleLine)
      .foreach {
        case Left(err) if !done.isCompleted =>
          log.info(s"[tournament] event stream closed: $err")
          done.trySuccess(())
        case _ => done.trySuccess(())
      }
    done.future

  private def adoptOngoingGames(): Unit =
    client.listTournaments().foreach {
      case Right(obj) =>
        val started = obj.fields.get("started").collect { case JsArray(xs) => xs }.getOrElse(Vector.empty)
        val ours    = started.find(_.asJsObject.fields.get("id").contains(JsString(tournamentId)))
        ours.foreach { tj =>
          val round = tj.asJsObject.fields.get("round").collect { case JsNumber(n) => n.toInt }.getOrElse(0)
          if round > 0 then
            client.getRound(tournamentId, round).foreach {
              case Right(pairings) =>
                pairings.foreach { p =>
                  p.gameIds.foreach { gid =>
                    if !sessions.add(gid) then ()
                    else if p.white == botId then
                      log.info(s"[tournament] adopt $gid as white (catch-up)")
                      new TournamentSession(client, aiClient, cfg, log, token, tournamentId, gid, "white").start()
                    else if p.black == botId then
                      log.info(s"[tournament] adopt $gid as black (catch-up)")
                      new TournamentSession(client, aiClient, cfg, log, token, tournamentId, gid, "black").start()
                    else
                      sessions.remove(gid)
                  }
                }
              case Left(_) => ()
            }
        }
      case Left(_) => ()
    }

  private def handleLine(line: String): Unit =
    try
      val ev    = line.parseJson.asJsObject.fields
      val etype = ev.get("type").collect { case JsString(v) => v }.getOrElse("")
      etype match
        case "tournamentStarted"  => log.info("[tournament] tournamentStarted")
        case "roundStarted"       => log.info(s"[tournament] roundStarted r=${rawIntField(ev, "round")}")
        case "gameStart"          => onGameStart(ev)
        case "roundFinished"      => log.info(s"[tournament] roundFinished r=${rawIntField(ev, "round")}")
        case "tournamentFinished" =>
          val w = ev.get("winner").collect { case JsObject(fs) => fs.get("name").collect { case JsString(n) => n } }.flatten
          log.info(s"[tournament] tournamentFinished winner=${w.getOrElse("?")}")
          done.trySuccess(())
        case "heartbeat" => ()
        case other       => log.info(s"[tournament] unhandled event type=$other")
    catch case NonFatal(ex) =>
      log.info(s"[tournament] bad event line: ${ex.getMessage} | $line")

  private def onGameStart(ev: Map[String, JsValue]): Unit =
    val gid    = ev.get("gameId").collect { case JsString(v) => v }.getOrElse("")
    val round  = ev.get("round").collect { case JsNumber(n) => n.toInt }.getOrElse(0)
    val color  = ev.get("color").collect { case JsString(v) => v }.getOrElse("")
    val evBot  = ev.get("botId").collect { case JsString(v) => v }
    if gid.isEmpty then return

    // Fast path: server includes botId in gameStart (post-commit 756d64c).
    // Each event is per-bot per-colour, so an exact botId match is our game
    // and no pairing lookup is needed. Dedup on gid is a safety belt in case
    // the server ever re-emits.
    evBot match
      case Some(id) =>
        if id != botId then return
        if !sessions.add(gid) then return
        log.info(s"[tournament] gameStart $gid as $color")
        new TournamentSession(client, aiClient, cfg, log, token, tournamentId, gid, color).start()
      case None =>
        // Legacy path: pre-756d64c the event omitted botId and was broadcast
        // to every subscriber. We used to fetch the pairing to figure out our
        // colour. Kept so the bot still works against older server builds.
        if !sessions.add(gid) then return
        client.getRound(tournamentId, round).foreach {
          case Right(pairings) =>
            pairings.find(_.gameIds.contains(gid)) match
              case Some(p) if p.white == botId =>
                log.info(s"[tournament] gameStart $gid as white (via pairing)")
                new TournamentSession(client, aiClient, cfg, log, token, tournamentId, gid, "white").start()
              case Some(p) if p.black == botId =>
                log.info(s"[tournament] gameStart $gid as black (via pairing)")
                new TournamentSession(client, aiClient, cfg, log, token, tournamentId, gid, "black").start()
              case _ =>
                sessions.remove(gid)
                log.info(s"[tournament] gameStart $gid not ours, skipping")
          case Left(err) =>
            sessions.remove(gid)
            log.info(s"[tournament] could not load round $round: $err")
        }

  private def rawIntField(ev: Map[String, JsValue], name: String): String =
    ev.get(name).collect { case JsNumber(n) => n.toString }.getOrElse("?")
