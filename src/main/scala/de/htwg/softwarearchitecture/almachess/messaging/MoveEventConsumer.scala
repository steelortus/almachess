package de.htwg.softwarearchitecture.almachess.messaging

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.kafka.scaladsl.Consumer
import akka.stream.scaladsl.Source
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer

/** Kafka → Akka-Streams bridge for MoveEvents.
  *
  *   `source` returns an `auto.offset.reset=latest` reactive stream of
  *   decoded MoveEvents. Downstream consumers (e.g. a BroadcastHub feeding
  *   SSE subscribers in NotationService) get standard Akka-Streams
  *   backpressure all the way down to the Kafka poll loop. */
object MoveEventConsumer:

  def source(config: KafkaConfig)(using system: ActorSystem[?]): Source[MoveEvent, Consumer.Control] =
    val settings: ConsumerSettings[String, String] =
      ConsumerSettings(system.classicSystem, new StringDeserializer, new StringDeserializer)
        .withBootstrapServers(config.bootstrap)
        .withGroupId(config.consumerGroup)
        .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")

    Consumer
      .plainSource(settings, Subscriptions.topics(config.movesTopic))
      .map(record => MoveEvent.decode(record.value()))
      .collect { case Right(ev) => ev }
