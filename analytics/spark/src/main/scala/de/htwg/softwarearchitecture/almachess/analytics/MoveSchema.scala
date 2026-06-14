package de.htwg.softwarearchitecture.almachess.analytics

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._

/** Spark-side mirror of `messaging.MoveEvent` (the JSON published on the
  * Kafka topic `almachess.moves` — and, line by line, the format of the
  * `moves.jsonl` file dump). Keep in sync with the Scala 3 case class.
  */
object MoveSchema {

  val schema: StructType = StructType(Seq(
    StructField("source", StringType),
    StructField("uci",    StringType),
    StructField("fen",    StringType),
    StructField("gameId", StringType),
    StructField("status", StringType),
    StructField("ts",     LongType)
  ))

  /** Parses a DataFrame with a single string column `value` (one raw
    * MoveEvent JSON document per row) into typed move columns. Works the
    * same for a batch read of moves.jsonl and a Kafka stream — that is the
    * point: batch and streaming share everything from here on.
    */
  def parse(raw: DataFrame): DataFrame =
    raw
      .select(from_json(col("value"), schema).as("ev"))
      .select("ev.*")
      .filter(col("uci").isNotNull) // drop undecodable lines
      .withColumn("time", (col("ts") / 1000).cast(TimestampType))
}
