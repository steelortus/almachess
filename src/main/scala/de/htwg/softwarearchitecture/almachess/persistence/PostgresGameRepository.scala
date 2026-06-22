package de.htwg.softwarearchitecture.almachess.persistence

import akka.NotUsed
import akka.stream.scaladsl.Source
import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.{ExecutionContext, Future}

final class PostgresGameRepository(
    url: String,
    user: String,
    password: String
)(using ec: ExecutionContext) extends GameRepository:

  override val name: String = "postgres"

  private val db: Database = Database.forURL(
    url      = url,
    user     = user,
    password = password,
    driver   = "org.postgresql.Driver"
  )

  private final class GamesTable(tag: Tag) extends Table[(String, String, String, String, Long)](tag, "games"):
    def id         = column[String]("id", O.PrimaryKey)
    def status     = column[String]("status")
    def currentFen = column[String]("current_fen")
    def initialFen = column[String]("initial_fen")
    def savedAt    = column[Long]("saved_at")
    def *          = (id, status, currentFen, initialFen, savedAt)

  private final class MovesTable(tag: Tag) extends Table[(Long, String, String, Int)](tag, "moves"):
    def id        = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def gameId    = column[String]("game_id")
    def uci       = column[String]("uci")
    def moveIndex = column[Int]("move_index")
    def *         = (id, gameId, uci, moveIndex)

  private final class FenTable(tag: Tag) extends Table[(Long, String, String, Int)](tag, "fen_notations"):
    def id        = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def gameId    = column[String]("game_id")
    def fen       = column[String]("fen")
    def moveIndex = column[Int]("move_index")
    def *         = (id, gameId, fen, moveIndex)

  private final class PgnTable(tag: Tag) extends Table[(Long, String, String)](tag, "pgn_documents"):
    def id     = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def gameId = column[String]("game_id")
    def pgn    = column[String]("pgn")
    def *      = (id, gameId, pgn)

  private val games = TableQuery[GamesTable]
  private val moves = TableQuery[MovesTable]
  private val fens  = TableQuery[FenTable]
  private val pgns  = TableQuery[PgnTable]

  private val initSchema: DBIO[Unit] = DBIO.seq(
    sqlu"""CREATE TABLE IF NOT EXISTS games (
             id          VARCHAR(128) PRIMARY KEY,
             status      TEXT NOT NULL,
             current_fen TEXT NOT NULL,
             initial_fen TEXT NOT NULL DEFAULT '',
             saved_at    BIGINT NOT NULL DEFAULT 0
           )""",
    sqlu"""ALTER TABLE games ADD COLUMN IF NOT EXISTS saved_at    BIGINT NOT NULL DEFAULT 0""",
    sqlu"""ALTER TABLE games ADD COLUMN IF NOT EXISTS initial_fen TEXT   NOT NULL DEFAULT ''""",
    sqlu"""CREATE TABLE IF NOT EXISTS moves (
             id         BIGSERIAL PRIMARY KEY,
             game_id    VARCHAR(128) NOT NULL REFERENCES games(id) ON DELETE CASCADE,
             uci        TEXT NOT NULL,
             move_index INTEGER NOT NULL
           )""",
    sqlu"""CREATE TABLE IF NOT EXISTS fen_notations (
             id         BIGSERIAL PRIMARY KEY,
             game_id    VARCHAR(128) NOT NULL REFERENCES games(id) ON DELETE CASCADE,
             fen        TEXT NOT NULL,
             move_index INTEGER NOT NULL
           )""",
    sqlu"""CREATE TABLE IF NOT EXISTS pgn_documents (
             id      BIGSERIAL PRIMARY KEY,
             game_id VARCHAR(128) NOT NULL REFERENCES games(id) ON DELETE CASCADE,
             pgn     TEXT NOT NULL
           )"""
  )

  scala.concurrent.Await.result(
    db.run(initSchema),
    scala.concurrent.duration.Duration(30, "seconds")
  )

  override def save(game: GameSaveDto): Future[Unit] =
    val ts       = if game.savedAt > 0 then game.savedAt else System.currentTimeMillis()
    val moveRows = game.moves.zipWithIndex.map { case (uci, idx) => (0L, game.gameId, uci, idx) }
    val fenRow   = (0L, game.gameId, game.currentFen, game.moves.size)

    val action = (for
      _ <- moves.filter(_.gameId === game.gameId).delete
      _ <- fens .filter(_.gameId === game.gameId).delete
      _ <- pgns .filter(_.gameId === game.gameId).delete
      _ <- games.filter(_.id     === game.gameId).delete
      _ <- games += ((game.gameId, game.status, game.currentFen, game.initialFen, ts))
      _ <- pgns  += ((0L, game.gameId, game.pgn))
      _ <- fens  += fenRow
      _ <- moves ++= moveRows
    yield ()).transactionally

    db.run(action)

  override def load(gameId: String): Future[Option[GameSaveDto]] =
    val gameQ  = games.filter(_.id === gameId).result.headOption
    val pgnQ   = pgns .filter(_.gameId === gameId).map(_.pgn).result.headOption
    val movesQ = moves.filter(_.gameId === gameId).sortBy(_.moveIndex).map(_.uci).result

    db.run(gameQ.zip(pgnQ).zip(movesQ)).map {
      case ((Some((id, status, currentFen, initialFen, savedAt)), pgnOpt), uciList) =>
        Some(GameSaveDto(
          gameId     = id,
          currentFen = currentFen,
          pgn        = pgnOpt.getOrElse(""),
          moves      = uciList.toList,
          status     = status,
          savedAt    = savedAt,
          initialFen = initialFen
        ))
      case _ => None
    }

  override def delete(gameId: String): Future[Unit] =
    db.run(games.filter(_.id === gameId).delete).map(_ => ())

  // Cursor-based reactive stream: Slick's db.stream returns a
  // DatabasePublisher[String] (Reactive-Streams Publisher). Source.fromPublisher
  // bridges it into Akka Streams, so downstream backpressure flows back to the
  // JDBC cursor — long move lists never have to be fully buffered in memory.
  override def streamMoves(gameId: String)(using ec: ExecutionContext): Source[String, NotUsed] =
    val action = moves
      .filter(_.gameId === gameId)
      .sortBy(_.moveIndex)
      .map(_.uci)
      .result
      .withStatementParameters(fetchSize = 64)
      .transactionally
    Source.fromPublisher(db.stream(action))

  override def list(): Future[List[GameListEntry]] =
    db.run(games.sortBy(_.savedAt.desc).map(g => (g.id, g.savedAt)).result)
      .map(_.toList.map { case (id, ts) => GameListEntry(id, ts) })

  override def close(): Unit = db.close()
