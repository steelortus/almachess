package de.htwg.softwarearchitecture.almachess.services

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.{ContentType, HttpEntity, MediaTypes, StatusCodes}
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import akka.kafka.scaladsl.Consumer
import akka.stream.{KillSwitches, Materializer, OverflowStrategy}
import akka.stream.scaladsl.{BroadcastHub, Keep, Sink, Source}
import akka.util.ByteString
import de.htwg.softwarearchitecture.almachess.api.*
import de.htwg.softwarearchitecture.almachess.api.JsonFormats.given
import de.htwg.softwarearchitecture.almachess.messaging.{KafkaConfig, MoveEvent, MoveEventConsumer}
import de.htwg.softwarearchitecture.almachess.parser.{FenParser, PgnParser}
import spray.json.*

import scala.concurrent.duration.*
import scala.util.{Failure, Success}

object NotationService:

  // Lazily-materialised reactive pipeline: Kafka → BroadcastHub.
  // SSE subscribers attach to the hub on demand; the underlying Kafka
  // consumer keeps running with one consumer-group offset shared across
  // all NotationService instances.
  final case class EventStream(hub: Source[ByteString, NotUsed])

  private def startEventStream(
      config: KafkaConfig
  )(using system: ActorSystem[?]): EventStream =
    val (_, hub): (Consumer.Control, Source[ByteString, NotUsed]) =
      MoveEventConsumer
        .source(config)
        .map(toSseBytes)
        .toMat(BroadcastHub.sink[ByteString](bufferSize = 16))(Keep.both)
        .run()
    // Keep the hub upstream alive even with zero subscribers; otherwise the
    // Kafka consumer would stop the first time the last SSE client cancels.
    hub.runWith(Sink.ignore)
    EventStream(hub)

  private def toSseBytes(ev: MoveEvent): ByteString =
    ByteString(s"event: move\ndata: ${ev.toJson.compactPrint}\n\n")

  def route(eventStream: Option[EventStream])(using mat: Materializer): Route =
    concat(
      path("health") { get { complete(HealthResponse("ok")) } },
      pathPrefix("notation") {
        concat(
          path("fen" / "validate") {
            post {
              entity(as[FenValidateRequest]) { req =>
                FenParser.parse(req.fen) match
                  case Right(state) => complete(FenValidateResponse(true, Some(state.toFen), None))
                  case Left(err)    => complete(FenValidateResponse(false, None, Some(err)))
              }
            }
          },
          path("pgn" / "parse") {
            post {
              entity(as[PgnParseRequest]) { req =>
                PgnParser.parse(req.pgn) match
                  case Right((tags, moves)) => complete(PgnParseResponse(tags, moves))
                  case Left(err)            => complete(StatusCodes.UnprocessableEntity -> ErrorResponse(err))
              }
            }
          },
          // SSE bridge: fan-out of the Kafka MoveEvent stream. Each browser
          // gets an independent downstream of the BroadcastHub with normal
          // Akka-Streams backpressure.
          path("events" / "stream") {
            get {
              eventStream match
                case None =>
                  complete(StatusCodes.ServiceUnavailable ->
                    ErrorResponse("kafka disabled (KAFKA_BOOTSTRAP not set)"))
                case Some(es) =>
                  val sse = es.hub.keepAlive(15.seconds, () => ByteString(": keepalive\n\n"))
                  complete(HttpEntity(ContentType(MediaTypes.`text/event-stream`), sse))
            }
          }
        )
      }
    )

  def main(args: Array[String]): Unit =
    given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "notation-service")
    given ec: scala.concurrent.ExecutionContext = system.executionContext
    val host = sys.env.getOrElse("NOTATION_HOST", "0.0.0.0")
    val port = sys.env.get("NOTATION_PORT").flatMap(_.toIntOption)
      .orElse(args.headOption.flatMap(_.toIntOption))
      .getOrElse(8081)

    val kafkaConfig = KafkaConfig.fromEnv(defaultGroup = "notation-service")
    val eventStream: Option[EventStream] =
      if kafkaConfig.enabled then
        println(s"Kafka consumer → ${kafkaConfig.bootstrap} (topic=${kafkaConfig.movesTopic}, group=${kafkaConfig.consumerGroup})")
        Some(startEventStream(kafkaConfig))
      else
        println("Kafka disabled (KAFKA_BOOTSTRAP not set)")
        None

    Http().newServerAt(host, port).bind(route(eventStream)).onComplete {
      case Success(b)  => println(s"NotationService online at http://${b.localAddress.getHostString}:${b.localAddress.getPort}/")
      case Failure(ex) => System.err.println(s"NotationService failed to bind: ${ex.getMessage}"); system.terminate()
    }
