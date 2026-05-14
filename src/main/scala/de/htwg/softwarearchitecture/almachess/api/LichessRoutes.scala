package de.htwg.softwarearchitecture.almachess.api

import akka.NotUsed
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.{ContentType, HttpEntity, MediaTypes, StatusCodes}
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.{BroadcastHub, Keep, Source, SourceQueueWithComplete}
import akka.util.ByteString
import de.htwg.softwarearchitecture.almachess.clients.{AiClient, LichessClientApi}
import de.htwg.softwarearchitecture.almachess.control.Controller
import de.htwg.softwarearchitecture.almachess.model.{Color, GameState, Pos}
import spray.json.*

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

import JsonFormats.given

final class LichessRoutes(client: Option[LichessClientApi], aiClient: AiClient)(using ec: ExecutionContext, mat: Materializer):

  private val lock = new Object
  private val startFen = GameState.initial.toFen

  private final case class BufferedSession(
      gameId: String,
      mode: String,
      requestedColor: String,
      yourColor: String,
      opponent: String,
      initialFen: String,
      moves: List[String],
      status: String,
      streamStatus: String,
      winner: Option[String] = None,
      // Clock state from the Lichess stream. For correspondence games these
      // stay None; the UI then keeps its clock rail hidden.
      whiteMs: Option[Long] = None,
      blackMs: Option[Long] = None,
      clockInitialMs: Option[Long] = None,
      clockIncrementMs: Option[Long] = None
  )

  private var session: Option[BufferedSession] = None

  // Reactive pub/sub for session updates. Producer is every mutation site
  // (route handlers + Lichess NDJSON callbacks). Consumers are SSE subscribers
  // on /api/lichess/session/stream — each materialises an independent
  // downstream of the BroadcastHub. dropHead on the upstream queue prevents
  // a slow subscriber from stalling the rest of the system.
  private val (sessionQueue, sessionHub): (SourceQueueWithComplete[ByteString], Source[ByteString, NotUsed]) =
    Source
      .queue[ByteString](64, OverflowStrategy.dropHead)
      .toMat(BroadcastHub.sink[ByteString](bufferSize = 8))(Keep.both)
      .run()

  // Keep the hub alive even with zero subscribers (otherwise the queue stops
  // accepting offers after the first downstream cancels).
  sessionHub.runWith(akka.stream.scaladsl.Sink.ignore)

  private def encodeSession(dto: LichessSessionDto): ByteString =
    ByteString(s"event: session\ndata: ${dto.toJson.compactPrint}\n\n")

  private def publishSession(): Unit =
    val snapshot = lock.synchronized(session.map(toDto))
    snapshot.foreach { dto =>
      val _ = sessionQueue.offer(encodeSession(dto))
    }

  private def statusPayload: LichessStatusResponse =
    val current = lock.synchronized(session.map(toDto))
    client match
      case None =>
        LichessStatusResponse(
          enabled = false,
          baseUrl = "https://lichess.org",
          boardToken = false,
          botToken = false,
          session = current
        )
      case Some(c) =>
        LichessStatusResponse(
          enabled = true,
          baseUrl = c.baseUrl,
          boardToken = c.hasBoardToken,
          botToken = c.hasBotToken,
          session = current
        )

  private def toDto(s: BufferedSession): LichessSessionDto =
    val controller = new Controller()
    val replay = controller.loadSnapshot(normalizeFen(s.initialFen), s.moves)
    val fen = replay match
      case Right(_) => controller.toFen
      case Left(_)  => normalizeFen(s.initialFen)
    val turn = replay match
      case Right(_) =>
        if controller.state.turn == Color.White then "white" else "black"
      case Left(_) =>
        "white"
    val terminalStatus = Set(
      "mate", "resign", "stalemate", "timeout", "draw", "outoftime",
      "abort", "cheat", "noStart", "variantEnd", "unknownFinish"
    )
    val statusTag = s.status.takeWhile(_ != ':').trim // "declined: reason..." → "declined"
    val terminalChallenge = Set("declined", "canceled", "expired")
    val gameOver = terminalStatus.contains(s.status)
      || terminalChallenge.contains(statusTag)
      || replay.exists(_ => controller.isGameOver)
    val status =
      if s.streamStatus.nonEmpty && s.streamStatus != "streaming" then s.streamStatus
      else if s.status.nonEmpty then s.status
      else "created"
    // Derive in-check info from the replayed controller. The Lichess stream
    // does not report check directly — we compute it locally so the UI can log
    // "Schach!" when appropriate.
    val inCheck: Option[String] = replay match
      case Right(_) =>
        val defender = controller.state.turn
        if controller.state.isCheck(defender) then
          Some(if defender == Color.White then "white" else "black")
        else None
      case Left(_) => None
    LichessSessionDto(
      gameId = s.gameId,
      mode = s.mode,
      yourColor = s.yourColor,
      fen = fen,
      yourTurn = !gameOver && turn == s.yourColor,
      gameOver = gameOver,
      status = status,
      turn = turn,
      lastMove = s.moves.lastOption,
      opponent = Some(s.opponent),
      moves = s.moves,
      streamStatus = s.streamStatus,
      whiteMs = s.whiteMs,
      blackMs = s.blackMs,
      clockInitialMs = s.clockInitialMs,
      clockIncrementMs = s.clockIncrementMs,
      inCheck = inCheck,
      winner = s.winner
    )

  private def controllerFor(s: BufferedSession): Either[String, Controller] =
    val controller = new Controller()
    controller.loadSnapshot(normalizeFen(s.initialFen), s.moves).map(_ => controller)

  private def normalizeFen(fen: String): String =
    if fen.trim == "startpos" || fen.trim.isEmpty then startFen else fen.trim

  private def splitMoves(value: String): List[String] =
    value.trim.split("\\s+").toList.filter(_.nonEmpty)

  // Returns (clockLimitSeconds, clockIncrementSeconds, daysOpt).
  // daysOpt = Some(n) selects correspondence mode (no live clock). When the
  // caller passes "Xd" / "corr-X", clock fields are zero and Lichess uses the
  // days field instead. Otherwise the regular "minutes+seconds" form applies.
  private def parseTimeControl(value: String): Either[String, (Int, Int, Option[Int])] =
    val v = value.trim
    v.toLowerCase match
      case s"corr-$d" =>
        d.toIntOption match
          case Some(days) if days >= 1 && days <= 15 => Right((0, 0, Some(days)))
          case _ => Left("correspondence days must be 1..15")
      case s"${d}d" =>
        d.toIntOption match
          case Some(days) if days >= 1 && days <= 15 => Right((0, 0, Some(days)))
          case _ => Left("correspondence days must be 1..15")
      case _ =>
        v match
          case s"$base+$inc" =>
            (base.toIntOption, inc.toIntOption) match
              case (Some(minutes), Some(seconds)) if minutes >= 0 && seconds >= 0 =>
                Right((minutes * 60, seconds, None))
              case _ =>
                Left("invalid Lichess time control")
          case _ =>
            Left("timeControl must look like '3+2' or '3d' (correspondence)")

  private def normalizeMode(value: String): Either[String, String] =
    value.trim.toLowerCase match
      case "board" => Right("board")
      case "bot"   => Right("bot")
      case other    => Left(s"unsupported Lichess mode: $other")

  private def normalizeColor(value: String): Either[String, String] =
    value.trim.toLowerCase match
      case color @ ("white" | "black" | "random") => Right(color)
      case other => Left(s"unsupported Lichess color: $other")

  private def configuredFor(c: LichessClientApi, mode: String): Boolean =
    mode match
      case "bot"   => c.hasBotToken
      case "board" => c.hasBoardToken
      case _       => false

  private def buildUci(req: LichessMoveRequest): Either[String, String] =
    val from = req.from.trim.toLowerCase
    val to = req.to.trim.toLowerCase
    val promo = req.promotion.map(_.trim.toLowerCase.take(1)).filter(_.nonEmpty).getOrElse("")
    val uci = from + to + promo
    if uci.matches("^[a-h][1-8][a-h][1-8][qrbn]?$") then Right(uci)
    else Left(s"invalid UCI move: $uci")

  private def fieldString(fields: Map[String, JsValue], name: String): Option[String] =
    fields.get(name).collect { case JsString(value) => value }

  private def fieldObject(fields: Map[String, JsValue], name: String): Option[Map[String, JsValue]] =
    fields.get(name).collect { case JsObject(value) => value }

  private def fieldLong(fields: Map[String, JsValue], name: String): Option[Long] =
    fields.get(name).collect { case JsNumber(value) => value.toLong }

  private def playerId(fields: Map[String, JsValue], color: String): Option[String] =
    fieldObject(fields, color).flatMap { player =>
      fieldString(player, "id").orElse(fieldString(player, "name"))
    }

  private def inferYourColor(fields: Map[String, JsValue], current: BufferedSession): String =
    val opponent = current.opponent.trim.toLowerCase
    val white = playerId(fields, "white").map(_.toLowerCase)
    val black = playerId(fields, "black").map(_.toLowerCase)
    if white.contains(opponent) then "black"
    else if black.contains(opponent) then "white"
    else current.requestedColor match
      case "white" | "black" => current.requestedColor
      case _                 => current.yourColor

  private def updateFromState(gameId: String, stateFields: Map[String, JsValue]): Unit =
    val moves = fieldString(stateFields, "moves").map(splitMoves)
    val status = fieldString(stateFields, "status")
    val winner = fieldString(stateFields, "winner")
    val wtime = fieldLong(stateFields, "wtime")
    val btime = fieldLong(stateFields, "btime")
    lock.synchronized {
      session = session.map { s =>
        if s.gameId != gameId then s
        else s.copy(
          moves = moves.getOrElse(s.moves),
          status = status.getOrElse(s.status),
          winner = winner.orElse(s.winner),
          whiteMs = wtime.orElse(s.whiteMs),
          blackMs = btime.orElse(s.blackMs),
          streamStatus = "streaming"
        )
      }
    }
    publishSession()

  private def handleStreamLine(gameId: String, line: String): Unit =
    try
      val fields = line.parseJson.asJsObject.fields
      fieldString(fields, "type") match
        case Some("gameFull") =>
          val stateFields = fieldObject(fields, "state").getOrElse(Map.empty)
          val clockFields = fieldObject(fields, "clock").getOrElse(Map.empty)
          lock.synchronized {
            session = session.map { s =>
              if s.gameId != gameId then s
              else
                val initial = fieldString(fields, "initialFen").map(normalizeFen).getOrElse(s.initialFen)
                s.copy(
                  initialFen = initial,
                  yourColor = inferYourColor(fields, s),
                  moves = fieldString(stateFields, "moves").map(splitMoves).getOrElse(s.moves),
                  status = fieldString(stateFields, "status").getOrElse("started"),
                  winner = fieldString(stateFields, "winner").orElse(s.winner),
                  whiteMs = fieldLong(stateFields, "wtime").orElse(s.whiteMs),
                  blackMs = fieldLong(stateFields, "btime").orElse(s.blackMs),
                  clockInitialMs = fieldLong(clockFields, "initial").orElse(s.clockInitialMs),
                  clockIncrementMs = fieldLong(clockFields, "increment").orElse(s.clockIncrementMs),
                  streamStatus = "streaming"
                )
            }
          }
        case Some("gameState") =>
          updateFromState(gameId, fields)
        case _ =>
          ()
      publishSession()
    catch case NonFatal(ex) =>
      lock.synchronized {
        session = session.map { s =>
          if s.gameId == gameId then s.copy(streamStatus = s"stream parse error: ${ex.getMessage}") else s
        }
      }
      publishSession()

  private def startStream(c: LichessClientApi, gameId: String, mode: String): Unit =
    c.streamGameWithRetry(gameId, mode)(
      line => handleStreamLine(gameId, line),
      result =>
        val terminalStatus = Set("mate", "resign", "stalemate", "timeout", "draw",
          "outoftime", "abort", "cheat", "noStart", "variantEnd", "unknownFinish")
        // Only reattach when the stream closed cleanly *and* the game is still
        // live. A Left here means streamGameWithRetry already exhausted its
        // internal retries (404, 429, ...) — looping at this layer would
        // hammer Lichess and earn us a rate-limit.
        val shouldReattach = lock.synchronized {
          session match
            case Some(s) if s.gameId == gameId =>
              val closed = result match
                case Right(_)  => "stream closed"
                case Left(err) => err
              session = Some(s.copy(streamStatus = closed))
              result.isRight && !terminalStatus.contains(s.status)
            case _ =>
              false
        }
        publishSession()
        if shouldReattach then
          startStream(c, gameId, mode)
        else
          // Stream failed terminally. Most common cause: the challenge was
          // never accepted (declined / canceled / expired). Look it up so the
          // UI can surface a clear reason instead of an opaque 404.
          result match
            case Left(err) if err.contains("404") => probeDeclined(c, gameId, mode)
            case _                                => ()
    )

  private def probeDeclined(c: LichessClientApi, gameId: String, mode: String): Unit =
    c.getChallengeState(gameId, mode).foreach {
      case Right((state, reasonOpt)) if Set("declined", "canceled", "expired").contains(state) =>
        val tag = reasonOpt.map(r => s"$state: $r").getOrElse(state)
        lock.synchronized {
          session = session.map { s =>
            if s.gameId == gameId then s.copy(status = tag, streamStatus = tag) else s
          }
        }
        publishSession()
      case _ =>
        ()
    }

  private def challengeRoute: Route =
    path("challenge") {
      post {
        entity(as[LichessChallengeRequest]) { req =>
          client match
            case None =>
              complete(StatusCodes.ServiceUnavailable -> ErrorResponse("Lichess integration is disabled"))
            case Some(c) =>
              val parsed =
                for
                  mode <- normalizeMode(req.mode)
                  color <- normalizeColor(req.color)
                  clock <- parseTimeControl(req.timeControl)
                  _ <- Either.cond(configuredFor(c, mode), (), s"Lichess $mode token is not configured")
                  username <- Either.cond(req.username.trim.nonEmpty, req.username.trim, "username is required")
                yield (mode, color, clock, username)

              parsed match
                case Left(err) =>
                  complete(StatusCodes.BadRequest -> ErrorResponse(err))
                case Right((mode, color, (limit, inc, daysOpt), username)) =>
                  onComplete(c.createChallenge(username, mode, color, limit, inc, daysOpt, req.rated)) {
                    case Success(Right(challenge)) =>
                      val visibleColor = color match
                        case "white" | "black" => color
                        case _                 => "white"
                      lock.synchronized {
                        session = Some(BufferedSession(
                          gameId = challenge.id,
                          mode = mode,
                          requestedColor = color,
                          yourColor = visibleColor,
                          opponent = username,
                          initialFen = startFen,
                          moves = Nil,
                          status = "created",
                          streamStatus = "waiting for accept"
                        ))
                      }
                      publishSession()
                      startStream(c, challenge.id, mode)
                      complete(StatusCodes.OK -> statusPayload)
                    case Success(Left(err)) =>
                      complete(StatusCodes.BadGateway -> ErrorResponse(err))
                    case Failure(ex) =>
                      complete(StatusCodes.BadGateway -> ErrorResponse(s"lichess challenge failed: ${ex.getMessage}"))
                  }
          }
        }
      }

  private def sessionRoute: Route =
    path("session") {
      get {
        complete(statusPayload)
      }
    }

  // Reactive-stream subscription: each GET materialises an independent
  // BroadcastHub downstream. We prepend the current session snapshot so
  // late subscribers don't have to wait for the next mutation to render.
  // Backpressure flows back through the hub buffer; if a single subscriber
  // is slow, the hub starts dropping for *that* subscriber only.
  private def sessionStreamRoute: Route =
    path("session" / "stream") {
      get {
        val initial: Source[ByteString, NotUsed] =
          lock.synchronized(session.map(toDto)) match
            case Some(dto) => Source.single(encodeSession(dto))
            case None      => Source.single(ByteString("event: session\ndata: null\n\n"))
        val stream = initial
          .concat(sessionHub)
          .keepAlive(15.seconds, () => ByteString(": keepalive\n\n"))
        complete(HttpEntity(ContentType(MediaTypes.`text/event-stream`), stream))
      }
    }

  private def moveRoute: Route =
    path("move") {
      post {
        entity(as[LichessMoveRequest]) { req =>
          val current = lock.synchronized(session)
          current match
            case None =>
              complete(StatusCodes.Conflict -> ErrorResponse("no active Lichess session"))
            case Some(s) if s.mode != "board" =>
              complete(StatusCodes.BadRequest -> ErrorResponse("manual moves are only enabled for Board mode in this slice"))
            case Some(s) =>
              buildUci(req) match
                case Left(err) =>
                  complete(StatusCodes.BadRequest -> ErrorResponse(err))
                case Right(uci) =>
                  client match
                    case None =>
                      complete(StatusCodes.ServiceUnavailable -> ErrorResponse("Lichess integration is disabled"))
                    case Some(c) =>
                      onComplete(c.makeMove(s.gameId, s.mode, uci)) {
                        case Success(Right(_)) =>
                          lock.synchronized {
                            session = session.map { cur =>
                              // Idempotent append: the NDJSON stream may have
                              // already delivered a gameState containing our
                              // move while makeMove was in flight.
                              if cur.gameId == s.gameId && !cur.moves.lastOption.contains(uci) then
                                cur.copy(moves = cur.moves :+ uci, status = "started")
                              else cur
                            }
                          }
                          publishSession()
                          complete(statusPayload)
                        case Success(Left(err)) =>
                          complete(StatusCodes.BadGateway -> ErrorResponse(err))
                        case Failure(ex) =>
                          complete(StatusCodes.BadGateway -> ErrorResponse(s"lichess move failed: ${ex.getMessage}"))
                      }
              }
          }
        }

  private def legalMovesRoute: Route =
    path("legal-moves") {
      get {
        parameter("from".optional) { fromOpt =>
          val current = lock.synchronized(session)
          current match
            case None =>
              complete(StatusCodes.Conflict -> ErrorResponse("no active Lichess session"))
            case Some(s) =>
              controllerFor(s) match
                case Left(err) =>
                  complete(StatusCodes.UnprocessableEntity -> ErrorResponse(err))
                case Right(controller) =>
                  fromOpt match
                    case None =>
                      val moves = controller.state.allLegalMoves().toList.map(AiClient.moveToUci)
                      complete(LegalMovesResponse(None, moves))
                    case Some(square) =>
                      Pos.fromAlgebraic(square) match
                        case None =>
                          complete(StatusCodes.BadRequest -> ErrorResponse(s"invalid square: $square"))
                        case Some(pos) =>
                          val moves = controller.state.legalMovesFrom(pos).toList.map(AiClient.moveToUci)
                          complete(LegalMovesResponse(Some(square), moves))
          }
        }
      }

  private def resignRoute: Route =
    path("resign") {
      post {
        val current = lock.synchronized(session)
        current match
          case None =>
            complete(StatusCodes.Conflict -> ErrorResponse("no active Lichess session"))
          case Some(s) =>
            client match
              case None =>
                complete(StatusCodes.ServiceUnavailable -> ErrorResponse("Lichess integration is disabled"))
              case Some(c) =>
                onComplete(c.resign(s.gameId, s.mode)) {
                  case Success(Right(_)) =>
                    lock.synchronized {
                      session = session.map { cur =>
                        if cur.gameId == s.gameId then cur.copy(status = "resign", streamStatus = "resigned")
                        else cur
                      }
                    }
                    publishSession()
                    complete(statusPayload)
                  case Success(Left(err)) =>
                    complete(StatusCodes.BadGateway -> ErrorResponse(err))
                  case Failure(ex) =>
                    complete(StatusCodes.BadGateway -> ErrorResponse(s"lichess resign failed: ${ex.getMessage}"))
                }
        }
      }

  private def disconnectRoute: Route =
    path("disconnect") {
      post {
        lock.synchronized { session = None }
        // Best-effort: notify SSE subscribers that the session has been cleared.
        val _ = sessionQueue.offer(ByteString("event: session\ndata: null\n\n"))
        complete(statusPayload)
      }
    }

  // Stockfish-vs-Lichess-Bot. Only allowed when session.mode == "bot".
  // Reads the current FEN from the buffered session, asks the AI for the best
  // move, then sends it to Lichess via the Bot API. Lichess Fair Play forbids
  // engine assistance in normal (board) games — this route enforces that.
  private def autoMoveRoute: Route =
    path("auto-move") {
      post {
        entity(as[LichessAutoMoveRequest]) { req =>
          val current = lock.synchronized(session)
          current match
            case None =>
              complete(StatusCodes.Conflict -> ErrorResponse("no active Lichess session"))
            case Some(s) if s.mode != "bot" =>
              complete(StatusCodes.BadRequest -> ErrorResponse("auto-move requires Bot mode (Fair Play)"))
            case Some(s) =>
              client match
                case None =>
                  complete(StatusCodes.ServiceUnavailable -> ErrorResponse("Lichess integration is disabled"))
                case Some(c) =>
                  controllerFor(s) match
                    case Left(err) =>
                      complete(StatusCodes.UnprocessableEntity -> ErrorResponse(err))
                    case Right(controller) =>
                      if controller.isGameOver then
                        complete(StatusCodes.Conflict -> ErrorResponse(s"game is over: ${controller.state.status}"))
                      else
                        val ourSide = if s.yourColor == "white" then Color.White else Color.Black
                        if controller.state.turn != ourSide then
                          complete(StatusCodes.Conflict -> ErrorResponse("not our turn"))
                        else
                          val fen = controller.toFen
                          val depth = req.depth.getOrElse(12).max(1).min(40)
                          onComplete(aiClient.bestMove(fen, depth, req.movetime, req.skill)) {
                            case Success(Right(uci)) =>
                              // Re-check turn under lock: stream may have updated state
                              // in flight (e.g. opponent already moved or game ended).
                              val gateOk = lock.synchronized {
                                session.exists { cur =>
                                  cur.gameId == s.gameId && cur.moves == s.moves
                                }
                              }
                              if !gateOk then
                                complete(StatusCodes.Conflict -> ErrorResponse("state changed during engine computation"))
                              else
                                onComplete(c.makeMove(s.gameId, "bot", uci)) {
                                  case Success(Right(_)) =>
                                    lock.synchronized {
                                      session = session.map { cur =>
                                        if cur.gameId == s.gameId && !cur.moves.lastOption.contains(uci) then
                                          cur.copy(moves = cur.moves :+ uci, status = "started")
                                        else cur
                                      }
                                    }
                                    publishSession()
                                    complete(LichessAutoMoveResponse(uci, statusPayload))
                                  case Success(Left(err)) =>
                                    complete(StatusCodes.BadGateway -> ErrorResponse(err))
                                  case Failure(ex) =>
                                    complete(StatusCodes.BadGateway -> ErrorResponse(s"lichess move failed: ${ex.getMessage}"))
                                }
                            case Success(Left(err)) =>
                              complete(StatusCodes.UnprocessableEntity -> ErrorResponse(err))
                            case Failure(ex) =>
                              complete(StatusCodes.ServiceUnavailable -> ErrorResponse(s"ai service unavailable: ${ex.getMessage}"))
                          }
          }
        }
      }

  val all: Route =
    pathPrefix("api" / "lichess") {
      concat(
        path("status") { get { complete(statusPayload) } },
        challengeRoute,
        sessionStreamRoute,
        sessionRoute,
        legalMovesRoute,
        moveRoute,
        autoMoveRoute,
        resignRoute,
        disconnectRoute
      )
    }
