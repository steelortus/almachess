package de.htwg.softwarearchitecture.almachess.services

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling.*
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.Source
import de.htwg.softwarearchitecture.almachess.ai.{ChessAI, StockfishEngine}
import de.htwg.softwarearchitecture.almachess.api.*
import de.htwg.softwarearchitecture.almachess.api.JsonFormats.given
import de.htwg.softwarearchitecture.almachess.clients.AiClient
import de.htwg.softwarearchitecture.almachess.parser.FenParser

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
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

  // Background EC for blocking UCI I/O so the akka default dispatcher stays
  // free. The streaming engine call parks on `in.readLine()`.
  private val blockingEc: ExecutionContext = ExecutionContext.global

  def route(using mat: Materializer): Route =
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
      // Reactive-stream variant of /ai/bestmove. Returns a Server-Sent-Events
      // response: one `info` event per UCI info line emitted by Stockfish
      // during search, followed by one `bestmove` event with the final UCI
      // move. Backpressure is enforced by the SourceQueue (dropHead under
      // load — clients won't slow down the engine, they just miss intermediate
      // depth reports).
      path("ai" / "bestmove" / "stream") {
        post {
          entity(as[BestMoveRequest]) { req =>
            stockfish match
              case Some(engine) if engine.isAlive =>
                val (queue, source) = Source
                  .queue[ServerSentEvent](128, OverflowStrategy.dropHead)
                  .preMaterialize()
                Future {
                  val result = engine.bestMoveStreaming(req.fen, req.depth, req.movetime, req.skill) { line =>
                    val ev =
                      if line.startsWith("info ") then
                        ServerSentEvent(line.stripPrefix("info ").trim, "info")
                      else if line.startsWith("bestmove") then
                        val mv = line.split("\\s+").lift(1).getOrElse("")
                        ServerSentEvent(mv, "bestmove")
                      else ServerSentEvent(line, "raw")
                    val _ = queue.offer(ev)
                  }
                  result match
                    case Left(err) =>
                      val _ = queue.offer(ServerSentEvent(err, "error"))
                    case Right(_) => ()
                  queue.complete()
                }(blockingEc)
                complete(source.keepAlive(15.seconds, () => ServerSentEvent.heartbeat))
              case _ =>
                // ChessAI fallback: emit a single `bestmove` event (no
                // progressive info — negamax doesn't expose iterative deepening
                // lines). Keeps the SSE contract stable so the client doesn't
                // need a separate code path.
                FenParser.parse(req.fen) match
                  case Left(err) =>
                    complete(StatusCodes.BadRequest -> ErrorResponse(err))
                  case Right(state) =>
                    val depth = req.depth.getOrElse(3).max(1).min(6)
                    val event = ChessAI.bestMove(state, depth) match
                      case Some(m) => ServerSentEvent(AiClient.moveToUci(m), "bestmove")
                      case None    => ServerSentEvent("no legal moves", "error")
                    complete(Source.single(event))
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
