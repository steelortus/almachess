package de.htwg.softwarearchitecture.almachess.analytics

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.OutputMode

/** Step 2 of the Spark task: the same analytics, but live from Kafka.
  *
  * Subscribes to `almachess.moves` via Structured Streaming and keeps three
  * continuously updating console tables:
  *
  *   1. running victory counter (same Aggregations.resultCounts as batch)
  *   2. most-played moves so far
  *   3. moves per minute (tumbling event-time window with watermark)
  *
  * Because batch and streaming share MoveSchema.parse / Aggregations, the
  * only difference to GameStatsBatch is the source: readStream+kafka
  * instead of read+text.
  *
  * Env: KAFKA_BOOTSTRAP (default localhost:9094 — the EXTERNAL listener of
  * the compose broker), KAFKA_MOVES_TOPIC (default almachess.moves).
  *
  * Run: `sbt "analytics/runMain de.htwg.softwarearchitecture.almachess.analytics.MoveStreamStats"`,
  * then play moves in the web UI and watch the tables update.
  */
object MoveStreamStats {

  def main(args: Array[String]): Unit = {
    val bootstrap = sys.env.getOrElse("KAFKA_BOOTSTRAP", "localhost:9094")
    val topic     = sys.env.getOrElse("KAFKA_MOVES_TOPIC", "almachess.moves")

    val spark = SparkSession.builder()
      .appName("AlmaChess MoveStreamStats")
      .master(sys.env.getOrElse("SPARK_MASTER", "local[*]"))
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "4")
      .getOrCreate()
    spark.sparkContext.setLogLevel("WARN")

    println(s"Streaming from $bootstrap topic $topic — Strg+C zum Beenden.")

    // earliest: replay everything already on the topic, then keep tailing.
    val raw = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", bootstrap)
      .option("subscribe", topic)
      .option("startingOffsets", "earliest")
      .load()
      .selectExpr("CAST(value AS STRING) AS value")

    val moves = MoveSchema.parse(raw)

    // 1. Running victory counter — identical aggregation to the batch job.
    Aggregations.resultCounts(moves).writeStream
      .queryName("victories")
      .format("console")
      .outputMode(OutputMode.Complete())
      .option("truncate", "false")
      .start()

    // 2. Most-played moves across all games so far.
    moves.groupBy("uci").count()
      .orderBy(col("count").desc)
      .limit(10)
      .writeStream
      .queryName("top-moves")
      .format("console")
      .outputMode(OutputMode.Complete())
      .option("truncate", "false")
      .start()

    // 3. Moves per minute: tumbling event-time window. The watermark lets
    // Spark drop state for windows older than 5 minutes.
    moves
      .withWatermark("time", "5 minutes")
      .groupBy(window(col("time"), "1 minute"))
      .count()
      .writeStream
      .queryName("moves-per-minute")
      .format("console")
      .outputMode(OutputMode.Update())
      .option("truncate", "false")
      .start()

    spark.streams.awaitAnyTermination()
  }
}
