package de.htwg.softwarearchitecture.almachess.api

import akka.http.scaladsl.model.{ContentType, HttpEntity, MediaTypes, StatusCodes}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import akka.util.ByteString
import de.htwg.softwarearchitecture.almachess.tournament.TournamentManager
import spray.json.*
import spray.json.DefaultJsonProtocol.*

import scala.concurrent.duration.*

import JsonFormats.given

// HTTP shell for the in-process TournamentManager:
//   POST   /api/tournament/start    {tournamentBaseUrl, tournamentId?, botName, engine, depth, movetimeMs?, skill?, aiServiceUrl?}
//   POST   /api/tournament/stop
//   GET    /api/tournament/status   -> {phase, ...}
//   GET    /api/tournament/log      -> JSON snapshot of last N lines
//   GET    /api/tournament/log/stream -> SSE of live status + log events
//
// Note: the path "/api/tournament" itself is taken on the central tournament
// server; we never proxy through here. The browser UI talks to /api/tournament/*
// for *our* in-process bot, and to the tournament server directly for the
// list of available tournaments (different host).
final case class TournamentStartRequest(
    tournamentBaseUrl: String,
    tournamentId: Option[String],
    botName: String,
    engine: String,                 // "stockfish" | "integrated"
    depth: Int,
    movetimeMs: Option[Int],
    skill: Option[Int],
    aiServiceUrl: Option[String]
)

final case class TournamentQuickstartRequest(
    tournamentBaseUrl: String,
    tournamentName: String,
    nbRounds: Int,
    clockLimit: Int,
    botName: String,
    engine: String,
    depth: Int,
    movetimeMs: Option[Int],
    skill: Option[Int],
    aiServiceUrl: Option[String],
    withSparring: Boolean
)

final case class TournamentQuickstartResponse(tournamentId: String, message: String)

object TournamentJsonFormats extends DefaultJsonProtocol:
  given RootJsonFormat[TournamentStartRequest]         = jsonFormat8(TournamentStartRequest.apply)
  given RootJsonFormat[TournamentQuickstartRequest]    = jsonFormat11(TournamentQuickstartRequest.apply)
  given RootJsonFormat[TournamentQuickstartResponse]   = jsonFormat2(TournamentQuickstartResponse.apply)

import TournamentJsonFormats.given

final class TournamentRoutes(manager: TournamentManager):

  // The manager owns its own JSON encoding for Status to avoid a separate
  // codec layer; here we wrap it in an HttpEntity directly.
  private def statusEntity: HttpEntity.Strict =
    val s = manager.status
    val phase = s.phase.toString
    val parts = List(
      Some(s""""phase":"$phase""""),
      s.tournamentId.map(v => s""""tournamentId":"$v""""),
      s.botId.map(v => s""""botId":"$v""""),
      s.botName.map(v => s""""botName":"${escape(v)}""""),
      s.message.map(v => s""""message":"${escape(v)}""""),
      Some(s""""lastUpdated":${s.lastUpdated}""")
    ).flatten
    HttpEntity(ContentType(MediaTypes.`application/json`), parts.mkString("{", ",", "}"))

  private def escape(s: String) =
    s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

  private val startRoute: Route =
    path("start") {
      post {
        entity(as[TournamentStartRequest]) { req =>
          val engine = req.engine.toLowerCase match
            case "stockfish"  => Right(TournamentManager.Engine.Stockfish)
            case "integrated" => Right(TournamentManager.Engine.Integrated)
            case other        => Left(s"unknown engine: $other")
          engine match
            case Left(err) =>
              complete(StatusCodes.BadRequest -> ErrorResponse(err))
            case Right(eng) =>
              val mreq = TournamentManager.StartRequest(
                tournamentBaseUrl = req.tournamentBaseUrl.trim.stripSuffix("/"),
                tournamentId      = req.tournamentId.map(_.trim).filter(_.nonEmpty),
                botName           = req.botName.trim,
                engine            = eng,
                depth             = req.depth.max(1).min(30),
                movetimeMs        = req.movetimeMs.filter(_ > 0),
                skill             = req.skill.filter(s => s >= 0 && s <= 20),
                aiServiceUrl      = req.aiServiceUrl.map(_.trim).filter(_.nonEmpty)
              )
              manager.start(mreq) match
                case Right(_)  => complete(StatusCodes.Accepted -> SuccessResponse("starting", None))
                case Left(err) => complete(StatusCodes.Conflict -> ErrorResponse(err))
        }
      }
    }

  private val stopRoute: Route =
    path("stop") {
      post {
        manager.stop()
        complete(SuccessResponse("stop requested", None))
      }
    }

  private val statusRoute: Route =
    path("status") {
      get { complete(statusEntity) }
    }

  private val logSnapshotRoute: Route =
    path("log") {
      get {
        val lines = manager.recentLog
        val arr = lines.map(l => s""""${escape(l)}"""").mkString("[", ",", "]")
        complete(HttpEntity(ContentType(MediaTypes.`application/json`), s"""{"lines":$arr}"""))
      }
    }

  // Live tail. Prefixed with a snapshot of the existing buffer so a fresh
  // subscriber sees recent history immediately. keepAlive prevents idle
  // proxies (nginx default 60s) from dropping the connection during long
  // games.
  private val logStreamRoute: Route =
    path("log" / "stream") {
      get {
        val snapshot = akka.stream.scaladsl.Source(manager.recentLog).map(l =>
          ByteString(s"event: log\ndata: ${escape(l)}\n\n"))
        val statusFrame = ByteString(s"event: status\ndata: ${dropEntityJson()}\n\n")
        val initial = akka.stream.scaladsl.Source.single(statusFrame).concat(snapshot)
        val live = initial.concat(manager.logSource)
          .keepAlive(15.seconds, () => ByteString(": keepalive\n\n"))
        complete(HttpEntity(ContentType(MediaTypes.`text/event-stream`), live))
      }
    }

  private def dropEntityJson(): String = statusEntity.data.utf8String

  private def parseEngine(name: String): Either[String, TournamentManager.Engine] =
    name.toLowerCase match
      case "stockfish"  => Right(TournamentManager.Engine.Stockfish)
      case "integrated" => Right(TournamentManager.Engine.Integrated)
      case other        => Left(s"unknown engine: $other")

  // Self-contained one-click test: backend orchestrates director registration,
  // bot registration, optional sparring opponent, tournament creation, both
  // /joins, AND the director's /start. Browser only needs to display the
  // returned TID and tail the existing log stream.
  private val quickstartRoute: Route =
    path("quickstart") {
      post {
        entity(as[TournamentQuickstartRequest]) { req =>
          parseEngine(req.engine) match
            case Left(err) => complete(StatusCodes.BadRequest -> ErrorResponse(err))
            case Right(eng) =>
              val mreq = TournamentManager.QuickstartRequest(
                tournamentBaseUrl = req.tournamentBaseUrl.trim.stripSuffix("/"),
                tournamentName    = req.tournamentName.trim,
                nbRounds          = req.nbRounds.max(1).min(20),
                clockLimit        = req.clockLimit.max(0).min(3600),
                botName           = req.botName.trim,
                engine            = eng,
                depth             = req.depth.max(1).min(30),
                movetimeMs        = req.movetimeMs.filter(_ > 0),
                skill             = req.skill.filter(s => s >= 0 && s <= 20),
                aiServiceUrl      = req.aiServiceUrl.map(_.trim).filter(_.nonEmpty),
                withSparring      = req.withSparring
              )
              onSuccess(manager.quickstart(mreq)) {
                case Right(tid) =>
                  complete(TournamentQuickstartResponse(tid, "started"))
                case Left(err) =>
                  complete(StatusCodes.Conflict -> ErrorResponse(err))
              }
        }
      }
    }

  val all: Route =
    pathPrefix("api" / "tournament") {
      concat(startRoute, quickstartRoute, stopRoute, statusRoute, logSnapshotRoute, logStreamRoute)
    }
