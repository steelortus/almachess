package de.htwg.softwarearchitecture.almachess.tournament

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import de.htwg.softwarearchitecture.almachess.clients.AiClient
import spray.json.*

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

// Standalone entry point so `sbt "runMain
// de.htwg.softwarearchitecture.almachess.tournament.TournamentMode"`
// joins the configured tournament without spinning up the rest of the
// AlMaChess HTTP server. The api.Server stays the default Main.
//
// Minimum env to play one tournament:
//   TOURNAMENT_BASE_URL = http://141.37.123.132:8086     (HTWG-internal)
//   TOURNAMENT_BOT_NAME = AlMaChess
//   TOURNAMENT_ID       = <tournament id we want to join>
//   ALMACHESS_AI_URL    = http://localhost:8082          (optional; falls
//                        back to the in-process negamax via AiClient.Local)
object TournamentMode:
  def main(args: Array[String]): Unit =
    val cfg = TournamentConfig.fromEnv()
    given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "almachess-tournament")
    given ec: ExecutionContext         = system.executionContext

    val client = new TournamentClient(cfg.baseUrl)
    val aiClient: AiClient = sys.env.get("ALMACHESS_AI_URL").filter(_.nonEmpty) match
      case Some(url) => println(s"[tournament] AI -> $url"); new AiClient.Http(url)
      case None      => println("[tournament] AI -> local negamax"); new AiClient.Local(ec)

    val bootstrap: Future[(String, String)] = cfg.cachedToken match
      case Some(t) =>
        // We cached a JWT in env, just re-derive the bot id via the
        // idempotent registry call. (Bot id == identity id == JWT sub.)
        client.registerBot(t, cfg.botName).map {
          case Right(id) => (id, t)
          case Left(err) => sys.error(s"cached token rejected: $err")
        }
      case None =>
        for
          regResult <- client.register(cfg.botName, isBot = true)
          (identityId, token) = regResult match
            case Right(v)  => v
            case Left(err) => sys.error(s"register failed: $err")
          botResult <- client.registerBot(token, cfg.botName)
        yield
          botResult match
            case Right(id) =>
              println(s"[tournament] identity=$identityId bot=$id token=${token.take(12)}...")
              (id, token)
            case Left(err) => sys.error(s"registerBot failed: $err")

    val joined: Future[(String, String, String)] = bootstrap.flatMap { case (botId, token) =>
      val tournamentIdFuture: Future[String] = cfg.tournamentId match
        case Some(id) => Future.successful(id)
        case None if cfg.autoJoinFirstCreated => firstCreatedTournament(client)
        case None     => Future.failed(new RuntimeException(
          "set TOURNAMENT_ID, or TOURNAMENT_AUTO_JOIN=true to grab the first created tournament"))
      tournamentIdFuture.flatMap { tid =>
        client.joinTournament(token, tid).map {
          case Right(_)  => println(s"[tournament] joined $tid"); (botId, token, tid)
          case Left(err) => sys.error(s"join $tid failed: $err")
        }
      }
    }

    val runFuture: Future[Unit] = joined.flatMap { case (botId, token, tid) =>
      val bot = new TournamentBot(client, aiClient, cfg, TournamentLog.Console, botId, token, tid)
      bot.runUntilFinished()
    }

    runFuture.onComplete {
      case Success(_)  => println("[tournament] run finished"); system.terminate()
      case Failure(ex) => System.err.println(s"[tournament] aborted: ${ex.getMessage}"); system.terminate()
    }

    // Block until tournament ends so sbt's run hook doesn't exit early.
    Await.ready(system.whenTerminated, Duration.Inf)

  private def firstCreatedTournament(client: TournamentClient)(using ec: ExecutionContext): Future[String] =
    client.listTournaments().map {
      case Right(obj) =>
        val created = obj.fields.get("created").collect { case JsArray(xs) => xs }.getOrElse(Vector.empty)
        created.headOption
          .flatMap(_.asJsObject.fields.get("id"))
          .collect { case JsString(v) => v }
          .getOrElse(sys.error("no created tournaments to join"))
      case Left(err) => sys.error(s"listTournaments failed: $err")
    }
