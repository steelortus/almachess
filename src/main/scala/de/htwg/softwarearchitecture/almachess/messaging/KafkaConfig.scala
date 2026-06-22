package de.htwg.softwarearchitecture.almachess.messaging

/** Kafka wiring configuration. All values are ENV-driven so the integration
  * is opt-in: if `KAFKA_BOOTSTRAP` is unset the producer/consumer never
  * spin up and the service falls back to its non-Kafka code path. This
  * mirrors how persistence (ALMACHESS_DB) and Redis (REDIS_ENABLED) gate
  * their respective backends. */
final case class KafkaConfig(
    bootstrap:     String,
    movesTopic:    String,
    consumerGroup: String
):
  def enabled: Boolean = bootstrap.trim.nonEmpty

object KafkaConfig:
  def fromEnv(defaultGroup: String): KafkaConfig =
    val bootstrap = sys.env.getOrElse("KAFKA_BOOTSTRAP", "").trim
    val topic     = sys.env.getOrElse("KAFKA_MOVES_TOPIC", "almachess.moves").trim
    val group     = sys.env.getOrElse("KAFKA_CONSUMER_GROUP", defaultGroup).trim
    KafkaConfig(bootstrap, topic, group)
