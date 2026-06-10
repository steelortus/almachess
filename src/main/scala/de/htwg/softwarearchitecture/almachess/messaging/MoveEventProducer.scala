package de.htwg.softwarearchitecture.almachess.messaging

import akka.Done
import akka.actor.typed.ActorSystem
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.Producer
import akka.stream.{Materializer, OverflowStrategy, QueueOfferResult}
import akka.stream.scaladsl.{Sink, Source, SourceQueueWithComplete}
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer

import scala.concurrent.Future

/** Akka-Streams → Kafka bridge for MoveEvents.
  *
  *   Producer side: callers do `producer.publish(event)` from any thread.
  *   Internally this offers to a `Source.queue` whose downstream is an
  *   Alpakka `Producer.plainSink`. The queue gives us asynchronous,
  *   backpressured publishing without blocking the route handler. */
trait MoveEventProducer:
  def publish(event: MoveEvent): Future[QueueOfferResult]
  def shutdown(): Future[Done]

object MoveEventProducer:

  /** No-op when KAFKA_BOOTSTRAP is unset. Lets the rest of the wiring stay
    * uniform (always call producer.publish) without scattering Option checks. */
  object Disabled extends MoveEventProducer:
    def publish(event: MoveEvent): Future[QueueOfferResult] = Future.successful(QueueOfferResult.Enqueued)
    def shutdown(): Future[Done] = Future.successful(Done)

  def apply(config: KafkaConfig)(using system: ActorSystem[?]): MoveEventProducer =
    if !config.enabled then Disabled
    else new Live(config)

  private final class Live(config: KafkaConfig)(using system: ActorSystem[?]) extends MoveEventProducer:
    private val settings: ProducerSettings[String, String] =
      ProducerSettings(system.classicSystem, new StringSerializer, new StringSerializer)
        .withBootstrapServers(config.bootstrap)

    // Source.queue → ProducerRecord → Alpakka Producer.plainSink.
    // dropHead means: if the broker is slow / down, we drop *old* events
    // instead of blocking the API request thread.
    private given Materializer = Materializer(system.classicSystem)
    private val queue: SourceQueueWithComplete[ProducerRecord[String, String]] =
      Source
        .queue[ProducerRecord[String, String]](1024, OverflowStrategy.dropHead)
        .to(Producer.plainSink(settings))
        .run()

    def publish(event: MoveEvent): Future[QueueOfferResult] =
      val record = new ProducerRecord[String, String](config.movesTopic, event.uci, MoveEvent.encode(event))
      queue.offer(record)

    def shutdown(): Future[Done] =
      queue.complete()
      queue.watchCompletion()
