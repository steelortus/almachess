package de.htwg.softwarearchitecture.almachess.services

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import de.htwg.softwarearchitecture.almachess.ai.{ChessAI, StockfishEngine}
import de.htwg.softwarearchitecture.almachess.api.*
import de.htwg.softwarearchitecture.almachess.api.JsonFormats.given
import de.htwg.softwarearchitecture.almachess.clients.AiClient
import de.htwg.softwarearchitecture.almachess.parser.FenParser

import scala.util.{Failure, Success}

object AiService:

  // Engine selection is decided once at startup. AI_ENGINE values:
  //   "stockfish" → must launch stockfish, no fallback
  //   "chess-ai"  → always use the local negamax
  //   "auto"      → try stockfish, fall back to chess-ai (default)
  private val engineMode  = sys.env.getOrElse("AI_ENGINE", "auto").toLowerCase
  private val stockfishPath = sys.env.getOrElse("STOCKFISH_PATH", "stockfish")

  private val stockfish: Option[StockfishEngine] =
    if engineMode == "chess-ai" then None
    else StockfishEngine.launch(stockfishPath) match
      case Right(engine) =>
        println(s"AiService: stockfish active (${engine.engineName})")
        Runtime.getRuntime.addShutdownHook(new Thread(() => engine.quit()))
        Some(engine)
      case Left(err) =>
        if engineMode == "stockfish" then
          System.err.println(s"AiService: AI_ENGINE=stockfish but failed to launch: $err")
        else
          println(s"AiService: stockfish unavailable ($err), falling back to ChessAI")
        None

  private val usingFallback: Boolean = engineMode == "auto" && stockfish.isEmpty
  private val activeBackend: String  = if stockfish.isDefined then "stockfish" else "chess-ai"
  private val activeEngine: String   = stockfish.map(_.engineName).getOrElse("ChessAI (negamax)")

  val route: Route =
    concat(
      path("health") { get { complete(HealthResponse("ok")) } },
      path("ai" / "status") {
        get {
          complete(AiStatusResponse(
            enabled  = stockfish.exists(_.isAlive),
            backend  = activeBackend,
            engine   = activeEngine,
            fallback = usingFallback
          ))
        }
      },
      path("ai" / "bestmove") {
        post {
          entity(as[BestMoveRequest]) { req =>
            stockfish match
              case Some(engine) if engine.isAlive =>
                engine.bestMove(req.fen, req.depth, req.movetime, req.skill) match
                  case Right(uci) => complete(BestMoveResponse(Some(uci), None))
                  case Left(err)  => complete(StatusCodes.UnprocessableEntity -> BestMoveResponse(None, Some(err)))
              case _ =>
                FenParser.parse(req.fen) match
                  case Left(err)    => complete(StatusCodes.BadRequest -> BestMoveResponse(None, Some(err)))
                  case Right(state) =>
                    val depth = req.depth.getOrElse(3).max(1).min(6)
                    ChessAI.bestMove(state, depth) match
                      case None    => complete(BestMoveResponse(None, Some("no legal moves")))
                      case Some(m) => complete(BestMoveResponse(Some(AiClient.moveToUci(m)), None))
          }
        }
      },
      path("ai" / "evaluate") {
        post {
          entity(as[EvaluateRequest]) { req =>
            stockfish match
              case Some(engine) if engine.isAlive =>
                engine.evaluate(req.fen, req.depth) match
                  case Right(ev) => complete(EvaluateResponse(
                    centipawns = ev.centipawns,
                    mate       = ev.mate,
                    bestMove   = ev.bestMove,
                    depth      = Some(ev.depth)
                  ))
                  case Left(err) => complete(StatusCodes.UnprocessableEntity ->
                    EvaluateResponse(error = Some(err)))
              case _ =>
                complete(StatusCodes.NotImplemented ->
                  EvaluateResponse(error = Some("evaluate requires Stockfish")))
          }
        }
      }
    )

  def main(args: Array[String]): Unit =
    given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "ai-service")
    given ec: scala.concurrent.ExecutionContext = system.executionContext
    val host = sys.env.getOrElse("AI_HOST", "0.0.0.0")
    val port = sys.env.get("AI_PORT").flatMap(_.toIntOption)
      .orElse(args.headOption.flatMap(_.toIntOption))
      .getOrElse(8082)
    Http().newServerAt(host, port).bind(route).onComplete {
      case Success(b)  => println(s"AiService online at http://${b.localAddress.getHostString}:${b.localAddress.getPort}/ (backend=$activeBackend)")
      case Failure(ex) => System.err.println(s"AiService failed to bind: ${ex.getMessage}"); system.terminate()
    }
