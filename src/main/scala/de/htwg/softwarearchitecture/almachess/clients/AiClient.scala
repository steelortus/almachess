package de.htwg.softwarearchitecture.almachess.clients

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.{sprayJsonMarshaller, sprayJsonUnmarshaller}
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.*
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.Source
import akka.util.ByteString
import de.htwg.softwarearchitecture.almachess.ai.ChessAI
import de.htwg.softwarearchitecture.almachess.api.*
import de.htwg.softwarearchitecture.almachess.api.JsonFormats.given
import de.htwg.softwarearchitecture.almachess.model.{GameState, Move, PieceType}
import de.htwg.softwarearchitecture.almachess.parser.FenParser

import scala.concurrent.{ExecutionContext, Future}

trait AiClient:
  /** Returns best move in UCI notation for the given FEN. */
  def bestMove(
      fen: String,
      depth: Int,
      movetime: Option[Int] = None,
      skill: Option[Int] = None
  ): Future[Either[String, String]]

  /** Reactive-stream variant: emits SSE-encoded bytes (one `event: info` block
    * per Stockfish search depth, then a final `event: bestmove` block). The
    * raw-bytes pass-through avoids re-marshalling at the gateway: the proxy
    * route streams the AI service's response straight to the browser with
    * end-to-end backpressure. Default impl emits a single terminal event so
    * non-streaming backends still satisfy the SSE contract. */
  def bestMoveStream(
      fen: String,
      depth: Int,
      movetime: Option[Int] = None,
      skill: Option[Int] = None
  )(using ec: ExecutionContext): Source[ByteString, NotUsed] =
    Source
      .future(bestMove(fen, depth, movetime, skill))
      .map {
        case Right(uci) => AiClient.sseBytes("bestmove", uci)
        case Left(err)  => AiClient.sseBytes("error", err)
      }

object AiClient:

  final class Local(ec: ExecutionContext) extends AiClient:
    given ExecutionContext = ec
    def bestMove(
        fen: String,
        depth: Int,
        movetime: Option[Int] = None,
        skill: Option[Int] = None
    ): Future[Either[String, String]] =
      Future {
        FenParser.parse(fen).flatMap { state =>
          ChessAI.bestMove(state, depth) match
            case None    => Left("no move found")
            case Some(m) => Right(moveToUci(m))
        }
      }

    // bestMoveStream: uses trait default — local negamax has no progressive
    // info, so a single terminal event is fine.

  final class Http(baseUrl: String)(using system: ActorSystem[?]) extends AiClient:
    given ExecutionContext = system.executionContext
    def bestMove(
        fen: String,
        depth: Int,
        movetime: Option[Int] = None,
        skill: Option[Int] = None
    ): Future[Either[String, String]] =
      val uri = s"${baseUrl.stripSuffix("/")}/ai/bestmove"
      for
        entity <- Marshal(BestMoveRequest(fen, Some(depth), movetime, skill)).to[RequestEntity]
        resp   <- akka.http.scaladsl.Http()(system).singleRequest(
                    HttpRequest(HttpMethods.POST, uri, entity = entity))
        body   <- Unmarshal(resp.entity).to[BestMoveResponse]
      yield body.move match
        case Some(m) => Right(m)
        case None    => Left(body.error.getOrElse("ai service returned no move"))

    // Inter-service reactive stream: POST request to /ai/bestmove/stream,
    // unmarshal the response body as a Source[ServerSentEvent]. The returned
    // Source is "deferred" — it only fires the request when materialized, and
    // any downstream backpressure flows back through the Akka HTTP entity bytes.
    override def bestMoveStream(
        fen: String,
        depth: Int,
        movetime: Option[Int] = None,
        skill: Option[Int] = None
    )(using ec: ExecutionContext): Source[ByteString, NotUsed] =
      val uri = s"${baseUrl.stripSuffix("/")}/ai/bestmove/stream"
      val futureSource: Future[Source[ByteString, Any]] =
        for
          entity <- Marshal(BestMoveRequest(fen, Some(depth), movetime, skill)).to[RequestEntity]
          resp   <- akka.http.scaladsl.Http()(system).singleRequest(
                      HttpRequest(HttpMethods.POST, uri, entity = entity))
        yield resp.entity.dataBytes
      Source.futureSource(futureSource).mapMaterializedValue(_ => NotUsed)

  // Encode a single Server-Sent-Event block as raw bytes (`event: <type>\ndata: <payload>\n\n`).
  private[clients] def sseBytes(event: String, data: String): ByteString =
    ByteString(s"event: $event\ndata: $data\n\n")

  def moveToUci(m: Move): String =
    val promo = m.promotion.map {
      case PieceType.Queen  => "q"
      case PieceType.Rook   => "r"
      case PieceType.Bishop => "b"
      case PieceType.Knight => "n"
      case _                => ""
    }.getOrElse("")
    m.from.toAlgebraic + m.to.toAlgebraic + promo
