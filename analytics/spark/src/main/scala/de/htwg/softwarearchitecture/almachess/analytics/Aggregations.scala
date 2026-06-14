package de.htwg.softwarearchitecture.almachess.analytics

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions._

/** Pure DataFrame → DataFrame aggregations over parsed MoveEvents
  * (see [[MoveSchema.parse]]).
  *
  * Status values come from GameState.finishMove():
  * "checkmate - White wins" | "checkmate - Black wins" | "stalemate" |
  * "<Color> is in check" | "<Color> to move".
  */
object Aggregations {

  /** One row per finished game: the move that ended it.
    * Columns: gameId, source, uci, status, winner ("White"|"Black"|"" for
    * stalemate), ts, time.
    */
  def gameEndings(moves: DataFrame): DataFrame =
    moves
      .filter(col("status").startsWith("checkmate") || col("status") === "stalemate")
      .withColumn("winner", regexp_extract(col("status"), "checkmate - (White|Black) wins", 1))

  /** Victories: how many games did White win, Black win, or end in stalemate. */
  def resultCounts(moves: DataFrame): DataFrame =
    gameEndings(moves)
      .withColumn("result", when(col("winner") === "", lit("stalemate"))
        .otherwise(concat(col("winner"), lit(" wins"))))
      .groupBy("result")
      .count()

  /** Human vs AI: who delivered the mating move. `source` is "api/move"
    * for human moves and "api/ai-move" for Stockfish moves.
    */
  def winsBySource(moves: DataFrame): DataFrame =
    gameEndings(moves)
      .filter(col("winner") =!= "")
      .withColumn("player", when(col("source") === "api/ai-move", lit("AI (Stockfish)"))
        .otherwise(lit("Human")))
      .groupBy("player")
      .count()
      .withColumnRenamed("count", "victories")

  /** Highscore: fastest checkmates, measured in plies (half-moves).
    * Joins per-game ply counts with the games that ended in mate.
    */
  def fastestMates(moves: DataFrame, limit: Int = 5): DataFrame = {
    val plies = moves.groupBy("gameId").agg(count("*").as("plies"))
    gameEndings(moves)
      .filter(col("winner") =!= "")
      .select("gameId", "winner")
      .join(plies, "gameId")
      .orderBy(col("plies").asc)
      .limit(limit)
  }

  /** Most popular opening moves: the first move of each game, ranked. */
  def topOpenings(moves: DataFrame, limit: Int = 5): DataFrame =
    moves
      .groupBy("gameId")
      .agg(min_by(col("uci"), col("ts")).as("opening"))
      .groupBy("opening")
      .count()
      .orderBy(col("count").desc)
      .limit(limit)

  /** Average game length in plies, over finished games only. */
  def avgGameLength(moves: DataFrame): DataFrame = {
    val plies = moves.groupBy("gameId").agg(count("*").as("plies"))
    gameEndings(moves)
      .select("gameId")
      .join(plies, "gameId")
      .agg(round(avg("plies"), 1).as("avg_plies"), count("*").as("finished_games"))
  }
}
