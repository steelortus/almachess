package de.htwg.softwarearchitecture.almachess.analytics

import org.apache.spark.sql.SparkSession

/** Step 1 of the Spark task: batch analytics from a simple file.
  *
  * Reads `analytics/data/moves.jsonl` — one MoveEvent JSON document per
  * line, i.e. exactly what the API publishes on the Kafka topic
  * `almachess.moves` (the file is just a dump of the topic; regenerate the
  * sample with `sbt "runMain de.htwg.softwarearchitecture.almachess.tools.AnalyticsSampleData"`).
  *
  * Run: `sbt analytics/run` and pick GameStatsBatch, or
  *      `sbt "analytics/runMain de.htwg.softwarearchitecture.almachess.analytics.GameStatsBatch [file]"`
  */
object GameStatsBatch {

  def main(args: Array[String]): Unit = {
    val path = args.headOption.getOrElse("analytics/data/moves.jsonl")

    val spark = SparkSession.builder()
      .appName("AlmaChess GameStatsBatch")
      .master(sys.env.getOrElse("SPARK_MASTER", "local[*]"))
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "4")
      .getOrCreate()
    spark.sparkContext.setLogLevel("WARN")

    // spark.read.text yields one row per line in a column named `value` —
    // the same shape a Kafka source produces, so MoveSchema.parse is shared.
    val moves = MoveSchema.parse(spark.read.text(path))

    val totalMoves = moves.count()
    val totalGames = moves.select("gameId").distinct().count()
    println(s"\n=== AlmaChess Statistik aus $path ($totalMoves Züge, $totalGames Partien) ===")

    println("\n--- Siege (Weiß / Schwarz / Patt) ---")
    Aggregations.resultCounts(moves).show(truncate = false)

    println("--- Siege Mensch vs. KI (wer hat den Mattzug gesetzt) ---")
    Aggregations.winsBySource(moves).show(truncate = false)

    println("--- Highscore: schnellste Matts (in Halbzügen) ---")
    Aggregations.fastestMates(moves).show(truncate = false)

    println("--- Beliebteste Eröffnungszüge ---")
    Aggregations.topOpenings(moves).show(truncate = false)

    println("--- Durchschnittliche Partielänge (beendete Partien) ---")
    Aggregations.avgGameLength(moves).show(truncate = false)

    spark.stop()
  }
}
