package de.htwg.softwarearchitecture.almachess.clients

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
import scala.concurrent.duration.*
import scala.util.control.NonFatal

// Client-side configuration for the Lichess integration.
// boardToken: lets a human play via /api/board (no engine assistance allowed).
// botToken:   lets the backend drive a Lichess BOT account via /api/bot
//             (engine assistance is permitted on the Bot API).
// Both tokens are optional. When neither is set the integration is disabled.
final case class LichessConfig(
    baseUrl: String,
    boardToken: Option[String],
    botToken: Option[String]
):
  def enabled: Boolean = boardToken.isDefined || botToken.isDefined

object LichessConfig:
  def fromEnv(): LichessConfig =
    val base  = sys.env.getOrElse("LICHESS_BASE_URL", "https://lichess.org").stripSuffix("/")
    val board = sys.env.get("LICHESS_BOARD_TOKEN").map(_.trim).filter(_.nonEmpty)
    val bot   = sys.env.get("LICHESS_BOT_TOKEN").map(_.trim).filter(_.nonEmpty)
    LichessConfig(base, board, bot)

final case class LichessChallengeCreated(id: String, url: String, raw: String)

// Test seam: LichessRoutes depends only on this trait, never on the http impl.
trait LichessClientApi:
  def baseUrl: String
  def hasBoardToken: Boolean
  def hasBotToken: Boolean
  def createChallenge(
      username: String,
      mode: String,
      color: String,
      clockLimitSeconds: Int,
      clockIncrementSeconds: Int,
      daysOpt: Option[Int],
      rated: Boolean
  ): Future[Either[String, LichessChallengeCreated]]
  def streamGameWithRetry(
      gameId: String,
      mode: String,
      attempts: Int = 30,
      delay: FiniteDuration = 1.second
  )(
      onLine: String => Unit,
      onClosed: Either[String, akka.Done] => Unit
  ): Unit
  def makeMove(gameId: String, mode: String, uci: String): Future[Either[String, String]]
  def resign(gameId: String, mode: String): Future[Either[String, String]]
  // Fetch challenge state (e.g. "created", "accepted", "declined", "canceled").
  // Used to detect declined challenges that would otherwise sit forever in
  // "waiting for accept" with a failing game stream.
  def getChallengeState(challengeId: String, mode: String): Future[Either[String, (String, Option[String])]]

final class LichessClient(config: LichessConfig)(using system: ActorSystem[?]) extends LichessClientApi:
  given ExecutionContext = system.executionContext

  def baseUrl: String = config.baseUrl
  def hasBoardToken: Boolean = config.boardToken.isDefined
  def hasBotToken: Boolean = config.botToken.isDefined

  private def tokenFor(mode: String): Option[String] =
    mode.trim.toLowerCase match
      case "bot"   => config.botToken
      case "board" => config.boardToken
      case _       => None

  private def authHeader(mode: String): Either[String, Authorization] =
    tokenFor(mode)
      .toRight(s"no Lichess token configured for mode '$mode'")
      .map(token => Authorization(OAuth2BearerToken(token)))

  private def readError(resp: HttpResponse, endpoint: String): Future[Left[String, Nothing]] =
    Unmarshal(resp.entity).to[String].recover { case _ => "" }.map { body =>
      val detail = body.trim.take(240)
      val suffix = if detail.nonEmpty then s": $detail" else ""
      Left(s"lichess $endpoint -> ${resp.status.intValue}$suffix")
    }

  // Hits /api/account with the given token. Used to confirm a token is
  // actually accepted by Lichess at startup time (or on user request).
  def verifyToken(token: String): Future[Either[String, String]] =
    val req = HttpRequest(
      HttpMethods.GET,
      uri = s"$baseUrl/api/account",
      headers = Authorization(OAuth2BearerToken(token)) :: Nil
    )
    Http()(system).singleRequest(req).flatMap { resp =>
      if resp.status.isSuccess() then
        Unmarshal(resp.entity).to[String].map(Right(_))
      else
        resp.entity.discardBytes()
        Future.successful(Left(s"lichess /api/account -> ${resp.status.intValue}"))
    }

  def createChallenge(
      username: String,
      mode: String,
      color: String,
      clockLimitSeconds: Int,
      clockIncrementSeconds: Int,
      daysOpt: Option[Int],
      rated: Boolean
  ): Future[Either[String, LichessChallengeCreated]] =
    authHeader(mode) match
      case Left(err) => Future.successful(Left(err))
      case Right(auth) =>
        val safeUser = Uri.Path.Segment(username, Uri.Path.Empty).toString
        val endpoint = s"/api/challenge/$safeUser"
        // Lichess accepts EITHER a live clock (clock.limit + clock.increment)
        // OR a correspondence pace (days). Sending both yields a 400.
        val timeFields: Seq[(String, String)] = daysOpt match
          case Some(days) => Seq("days" -> days.toString)
          case None       => Seq(
            "clock.limit"     -> clockLimitSeconds.toString,
            "clock.increment" -> clockIncrementSeconds.toString
          )
        val form = FormData(
          (Seq(
            "rated"   -> rated.toString,
            "color"   -> color,
            "variant" -> "standard"
          ) ++ timeFields)*
        ).toEntity
        val req = HttpRequest(
          method = HttpMethods.POST,
          uri = s"$baseUrl$endpoint",
          headers = auth :: Nil,
          entity = form
        )
        Http()(system).singleRequest(req).flatMap { resp =>
          if resp.status.isSuccess() then
            Unmarshal(resp.entity).to[String].map { body =>
              parseChallenge(body).left.map(err => s"$err: ${body.take(240)}")
            }
          else readError(resp, endpoint)
        }.recover { case NonFatal(ex) => Left(s"lichess challenge failed: ${ex.getMessage}") }

  private def parseChallenge(body: String): Either[String, LichessChallengeCreated] =
    try
      val root = body.parseJson.asJsObject
      def stringField(obj: JsObject, name: String): Option[String] =
        obj.fields.get(name).collect { case JsString(v) => v }
      val nested = root.fields.get("challenge").collect { case o: JsObject => o }
      val id = stringField(root, "id").orElse(nested.flatMap(stringField(_, "id")))
      val url = stringField(root, "url").orElse(nested.flatMap(stringField(_, "url")))
      id match
        case Some(value) => Right(LichessChallengeCreated(value, url.getOrElse(s"$baseUrl/$value"), body))
        case None        => Left("lichess challenge response had no id")
    catch case NonFatal(ex) =>
      Left(s"could not parse lichess challenge response: ${ex.getMessage}")

  def streamGame(gameId: String, mode: String)(onLine: String => Unit): Future[Either[String, Done]] =
    authHeader(mode) match
      case Left(err) => Future.successful(Left(err))
      case Right(auth) =>
        val endpoint = mode.trim.toLowerCase match
          case "bot" => s"/api/bot/game/stream/$gameId"
          case _     => s"/api/board/game/stream/$gameId"
        val req = HttpRequest(
          method = HttpMethods.GET,
          uri = s"$baseUrl$endpoint",
          headers = auth :: Nil
        )
        Http()(system).singleRequest(req).flatMap { resp =>
          if resp.status.isSuccess() then
            resp.entity.dataBytes
              .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 65536, allowTruncation = true))
              .map(_.utf8String.trim)
              .filter(_.nonEmpty)
              .runForeach(onLine)
              .map(Right(_))
              .recover { case NonFatal(ex) => Left(s"lichess stream failed: ${ex.getMessage}") }
          else readError(resp, endpoint)
        }.recover { case NonFatal(ex) => Left(s"lichess stream failed: ${ex.getMessage}") }

  def streamGameWithRetry(
      gameId: String,
      mode: String,
      attempts: Int = 30,
      delay: FiniteDuration = 1.second
  )(onLine: String => Unit, onClosed: Either[String, Done] => Unit): Unit =
    def loop(remaining: Int): Unit =
      streamGame(gameId, mode)(onLine).foreach {
        case Left(err) if remaining > 1 && err.contains("404") =>
          system.scheduler.scheduleOnce(delay, () => loop(remaining - 1))
        case other =>
          onClosed(other)
      }
    loop(attempts.max(1))

  def makeMove(gameId: String, mode: String, uci: String): Future[Either[String, String]] =
    postOk(
      mode,
      mode.trim.toLowerCase match
        case "bot" => s"/api/bot/game/$gameId/move/$uci"
        case _     => s"/api/board/game/$gameId/move/$uci"
    )

  // Returns (status, declineReasonOpt). status is one of "created",
  // "accepted", "declined", "canceled", "expired".
  def getChallengeState(challengeId: String, mode: String): Future[Either[String, (String, Option[String])]] =
    authHeader(mode) match
      case Left(err) => Future.successful(Left(err))
      case Right(auth) =>
        val endpoint = s"/api/challenge/$challengeId/show"
        val req = HttpRequest(HttpMethods.GET, uri = s"$baseUrl$endpoint", headers = auth :: Nil)
        Http()(system).singleRequest(req).flatMap { resp =>
          if resp.status.isSuccess() then
            Unmarshal(resp.entity).to[String].map { body =>
              try
                val root = body.parseJson.asJsObject.fields
                val st = root.get("status").collect { case JsString(v) => v }.getOrElse("unknown")
                val reason = root.get("declineReason").collect { case JsString(v) => v }
                Right((st, reason))
              catch case NonFatal(ex) =>
                Left(s"could not parse challenge state: ${ex.getMessage}")
            }
          else readError(resp, endpoint)
        }.recover { case NonFatal(ex) => Left(s"lichess challenge state failed: ${ex.getMessage}") }

  def resign(gameId: String, mode: String): Future[Either[String, String]] =
    postOk(
      mode,
      mode.trim.toLowerCase match
        case "bot" => s"/api/bot/game/$gameId/resign"
        case _     => s"/api/board/game/$gameId/resign"
    )

  private def postOk(mode: String, endpoint: String): Future[Either[String, String]] =
    authHeader(mode) match
      case Left(err) => Future.successful(Left(err))
      case Right(auth) =>
        val req = HttpRequest(
          method = HttpMethods.POST,
          uri = s"$baseUrl$endpoint",
          headers = auth :: Nil
        )
        Http()(system).singleRequest(req).flatMap { resp =>
          if resp.status.isSuccess() then
            Unmarshal(resp.entity).to[String].map(Right(_))
          else readError(resp, endpoint)
        }.recover { case NonFatal(ex) => Left(s"lichess request failed: ${ex.getMessage}") }
