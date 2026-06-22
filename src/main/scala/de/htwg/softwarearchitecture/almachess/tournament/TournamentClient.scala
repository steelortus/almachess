package de.htwg.softwarearchitecture.almachess.tournament

import akka.Done
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.Framing
import akka.util.ByteString
import spray.json.*

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

// Snapshot of a tournament game returned by GET /api/tournament/{id}/game/{gid}.
// The fields mirror what the server actually emits — see the JSON probe in
// the tournament test scripts. Decoded with spray-json directly to keep the
// dependency surface small (no extra codecs file).
final case class TournamentGameState(
    gameId: String,
    fen: String,
    turn: String,           // "white" | "black"
    status: String,         // "ongoing" | "checkmate" | "timeout" | ...
    moves: String,          // space-separated UCI
    winner: Option[String]
)

final case class TournamentPairing(
    white: String,          // bot id (registry == identity on current server)
    black: String,
    gameIds: List[String]
)

// Client for the central tournament server. Mirrors LichessClient's
// HTTP/streaming pattern (Akka HTTP + Framing.delimiter on \n) so the rest
// of the codebase has only one way to consume an NDJSON stream.
//
// Two key conventions baked in:
//   - `/api/auth/register` issues a JWT; `/api/bots` is idempotent on
//     identity (registry id == JWT sub on the current server).
//   - Bots add themselves via `/join`. We never use the director-side
//     `bots:` form param at create time — that was unreliable on older
//     builds and is not needed when the bot itself joins.
final class TournamentClient(baseUrl: String)(using system: ActorSystem[?]):
  given ExecutionContext = system.executionContext

  private val http = Http()(system)

  private def authed(token: String) = Authorization(OAuth2BearerToken(token))

  private def readBody(resp: HttpResponse): Future[String] =
    Unmarshal(resp.entity).to[String]

  private def readError(resp: HttpResponse, endpoint: String): Future[Left[String, Nothing]] =
    readBody(resp).recover { case _ => "" }.map { body =>
      val detail = body.trim.take(240)
      val suffix = if detail.nonEmpty then s": $detail" else ""
      Left(s"tournament $endpoint -> ${resp.status.intValue}$suffix")
    }

  // Register an identity on the server. Self-service, no password.
  // Returns (identityId, jwt) — the identity id == JWT `sub` claim and
  // is also the bot id once we POST /api/bots.
  def register(name: String, isBot: Boolean): Future[Either[String, (String, String)]] =
    val body = JsObject("name" -> JsString(name), "isBot" -> JsBoolean(isBot)).compactPrint
    val req  = HttpRequest(
      HttpMethods.POST,
      uri = s"$baseUrl/api/auth/register",
      entity = HttpEntity(ContentTypes.`application/json`, body)
    )
    http.singleRequest(req).flatMap { resp =>
      if resp.status.isSuccess() then
        readBody(resp).map { raw =>
          try
            val obj = raw.parseJson.asJsObject.fields
            val id  = obj("id").asInstanceOf[JsString].value
            val tok = obj("token").asInstanceOf[JsString].value
            Right((id, tok))
          catch case NonFatal(ex) => Left(s"register parse failed: ${ex.getMessage}")
        }
      else readError(resp, "/api/auth/register")
    }.recover { case NonFatal(ex) => Left(s"register failed: ${ex.getMessage}") }

  // Idempotent: registering the same name twice returns the same id.
  // Honours the server's contract documented in BotRegistryService.scala.
  def registerBot(token: String, name: String): Future[Either[String, String]] =
    val body = JsObject("name" -> JsString(name)).compactPrint
    val req  = HttpRequest(
      HttpMethods.POST,
      uri = s"$baseUrl/api/bots",
      entity = HttpEntity(ContentTypes.`application/json`, body),
      headers = authed(token) :: Nil
    )
    http.singleRequest(req).flatMap { resp =>
      if resp.status.isSuccess() then
        readBody(resp).map { raw =>
          try Right(raw.parseJson.asJsObject.fields("id").asInstanceOf[JsString].value)
          catch case NonFatal(ex) => Left(s"registerBot parse failed: ${ex.getMessage}")
        }
      else readError(resp, "/api/bots")
    }.recover { case NonFatal(ex) => Left(s"registerBot failed: ${ex.getMessage}") }

  // Lists tournaments grouped by status (created/started/finished).
  // Returns the raw JsValue so callers can pick what they need without
  // forcing a heavy domain mapping for every screen.
  def listTournaments(): Future[Either[String, JsObject]] =
    val req = HttpRequest(HttpMethods.GET, uri = s"$baseUrl/api/tournament")
    http.singleRequest(req).flatMap { resp =>
      if resp.status.isSuccess() then
        readBody(resp).map(raw => Right(raw.parseJson.asJsObject))
      else readError(resp, "/api/tournament")
    }.recover { case NonFatal(ex) => Left(s"listTournaments failed: ${ex.getMessage}") }

  // Treats 409 as idempotent success — "already joined" or "started" both mean
  // we're in the tournament from our perspective. A director may pre-add us
  // via the create form's `bots:` parameter; in that case /join would otherwise
  // fail and our caller would have to special-case it. This way the same
  // bootstrap code works regardless of how the bot got into the tournament.
  def joinTournament(token: String, tournamentId: String): Future[Either[String, Done]] =
    val req = HttpRequest(
      HttpMethods.POST,
      uri = s"$baseUrl/api/tournament/$tournamentId/join",
      headers = authed(token) :: Nil
    )
    http.singleRequest(req).flatMap { resp =>
      if resp.status.isSuccess() || resp.status.intValue == 409 then
        resp.entity.discardBytes(); Future.successful(Right(Done))
      else readError(resp, s"/api/tournament/$tournamentId/join")
    }.recover { case NonFatal(ex) => Left(s"join failed: ${ex.getMessage}") }

  // GET the snapshot of a single game. Used at game-stream connect time
  // because the server does not always emit an initial gameState on the
  // stream — workaround documented in tournament/README.
  def getGameState(tournamentId: String, gameId: String): Future[Either[String, TournamentGameState]] =
    val req = HttpRequest(HttpMethods.GET, uri = s"$baseUrl/api/tournament/$tournamentId/game/$gameId")
    http.singleRequest(req).flatMap { resp =>
      if resp.status.isSuccess() then
        readBody(resp).map { raw =>
          try
            val o = raw.parseJson.asJsObject.fields
            Right(TournamentGameState(
              gameId  = o("id").asInstanceOf[JsString].value,
              fen     = o("fen").asInstanceOf[JsString].value,
              turn    = o("turn").asInstanceOf[JsString].value,
              status  = o("status").asInstanceOf[JsString].value,
              moves   = o.get("moves").collect { case JsString(v) => v }.getOrElse(""),
              winner  = o.get("winner").collect { case JsString(v) => v }
            ))
          catch case NonFatal(ex) => Left(s"getGameState parse failed: ${ex.getMessage}")
        }
      else readError(resp, s"/api/tournament/$tournamentId/game/$gameId")
    }.recover { case NonFatal(ex) => Left(s"getGameState failed: ${ex.getMessage}") }

  // Returns the round pairings. We use this right after a `gameStart`
  // event to figure out whether THIS bot is in the just-started game,
  // and on which colour. The server broadcasts all gameStart events to
  // every stream subscriber, so the bot must filter itself.
  def getRound(tournamentId: String, round: Int): Future[Either[String, List[TournamentPairing]]] =
    val req = HttpRequest(HttpMethods.GET, uri = s"$baseUrl/api/tournament/$tournamentId/round/$round")
    http.singleRequest(req).flatMap { resp =>
      if resp.status.isSuccess() then
        readBody(resp).map { raw =>
          try
            val pairings = raw.parseJson.asJsObject.fields("pairings").asInstanceOf[JsArray].elements.toList.map { p =>
              val o = p.asJsObject.fields
              val white = o("white").asJsObject.fields("id").asInstanceOf[JsString].value
              val black = o("black").asJsObject.fields("id").asInstanceOf[JsString].value
              val games = o.get("matches").collect { case JsArray(xs) =>
                xs.toList.map(_.asJsObject.fields("gameId").asInstanceOf[JsString].value)
              }.getOrElse(Nil)
              TournamentPairing(white, black, games)
            }
            Right(pairings)
          catch case NonFatal(ex) => Left(s"getRound parse failed: ${ex.getMessage}")
        }
      else readError(resp, s"/api/tournament/$tournamentId/round/$round")
    }.recover { case NonFatal(ex) => Left(s"getRound failed: ${ex.getMessage}") }

  // Director-side helpers, used by the UI's quickstart flow to spin up a
  // self-contained tournament. The create form is form-urlencoded (not JSON)
  // — verified against the live server.
  def createTournament(
      token: String,
      name: String,
      nbRounds: Int,
      clockLimitSeconds: Int,
      botRegistryIds: List[String]
  ): Future[Either[String, String]] =
    val form = akka.http.scaladsl.model.FormData(
      "name"            -> name,
      "nbRounds"        -> nbRounds.toString,
      "clockLimit"      -> clockLimitSeconds.toString,
      "clockIncrement"  -> "0",
      "rated"           -> "false",
      "format"          -> "swiss",
      "bots"            -> botRegistryIds.mkString(",")
    ).toEntity
    val req = HttpRequest(
      HttpMethods.POST,
      uri = s"$baseUrl/api/tournament",
      entity = form,
      headers = authed(token) :: Nil
    )
    http.singleRequest(req).flatMap { resp =>
      if resp.status.isSuccess() then
        readBody(resp).map { raw =>
          try Right(raw.parseJson.asJsObject.fields("id").asInstanceOf[JsString].value)
          catch case NonFatal(ex) => Left(s"createTournament parse: ${ex.getMessage}")
        }
      else readError(resp, "/api/tournament")
    }.recover { case NonFatal(ex) => Left(s"createTournament failed: ${ex.getMessage}") }

  def startTournament(token: String, tournamentId: String): Future[Either[String, Done]] =
    val req = HttpRequest(
      HttpMethods.POST,
      uri = s"$baseUrl/api/tournament/$tournamentId/start",
      headers = authed(token) :: Nil
    )
    http.singleRequest(req).flatMap { resp =>
      if resp.status.isSuccess() then
        resp.entity.discardBytes(); Future.successful(Right(Done))
      else readError(resp, s"/api/tournament/$tournamentId/start")
    }.recover { case NonFatal(ex) => Left(s"startTournament failed: ${ex.getMessage}") }

  def makeMove(token: String, tournamentId: String, gameId: String, uci: String): Future[Either[String, Done]] =
    val req = HttpRequest(
      HttpMethods.POST,
      uri = s"$baseUrl/api/tournament/$tournamentId/game/$gameId/move/$uci",
      headers = authed(token) :: Nil
    )
    http.singleRequest(req).flatMap { resp =>
      if resp.status.isSuccess() then
        resp.entity.discardBytes(); Future.successful(Right(Done))
      else readError(resp, s"/move/$uci")
    }.recover { case NonFatal(ex) => Left(s"move failed: ${ex.getMessage}") }

  // Open an NDJSON stream and dispatch each line to onLine. Same pattern
  // LichessClient.streamGame uses, so the reactive-stream behaviour
  // (backpressure, framing) stays consistent across the codebase.
  def stream(
      path: String,
      token: String
  )(onLine: String => Unit): Future[Either[String, Done]] =
    val req = HttpRequest(
      HttpMethods.GET,
      uri = s"$baseUrl$path",
      headers = authed(token) :: Nil
    )
    http.singleRequest(req).flatMap { resp =>
      if resp.status.isSuccess() then
        resp.entity.dataBytes
          .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 65536, allowTruncation = true))
          .map(_.utf8String.trim)
          .filter(_.nonEmpty)
          .runForeach(onLine)
          .map(Right(_))
          .recover { case NonFatal(ex) => Left(s"stream $path failed: ${ex.getMessage}") }
      else readError(resp, path)
    }.recover { case NonFatal(ex) => Left(s"stream $path failed: ${ex.getMessage}") }
