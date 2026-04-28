package de.htwg.softwarearchitecture.almachess.persistence

import org.bson.Document
import org.mongodb.scala.{MongoClient, MongoCollection, MongoDatabase, ObservableFuture, SingleObservableFuture}
import org.mongodb.scala.model.{Filters, ReplaceOptions}

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

final class MongoGameRepository(
    uri: String,
    databaseName: String = "almachess",
    collectionName: String = "games"
)(using ec: ExecutionContext) extends GameRepository:

  override val name: String = "mongo"

  private val client: MongoClient            = MongoClient(uri)
  private val db: MongoDatabase              = client.getDatabase(databaseName)
  private val games: MongoCollection[Document] = db.getCollection(collectionName)

  override def save(game: GameSaveDto): Future[Unit] =
    val ts = if game.savedAt > 0 then game.savedAt else System.currentTimeMillis()
    val doc = new Document()
      .append("gameId", game.gameId)
      .append("currentFen", game.currentFen)
      .append("pgn", game.pgn)
      .append("moves", game.moves.asJava)
      .append("status", game.status)
      .append("savedAt", java.lang.Long.valueOf(ts))

    games.replaceOne(
      Filters.eq("gameId", game.gameId),
      doc,
      ReplaceOptions().upsert(true)
    ).toFuture().map(_ => ())

  override def load(gameId: String): Future[Option[GameSaveDto]] =
    games.find(Filters.eq("gameId", gameId)).first().toFutureOption().map(_.map(toDto))

  override def delete(gameId: String): Future[Unit] =
    games.deleteOne(Filters.eq("gameId", gameId)).toFuture().map(_ => ())

  override def list(): Future[List[GameListEntry]] =
    games
      .find()
      .projection(new Document("gameId", 1).append("savedAt", 1))
      .sort(new Document("savedAt", -1))
      .toFuture()
      .map { docs =>
        docs.toList.map { d =>
          val ts = Option(d.get("savedAt")) match
            case Some(n: java.lang.Number) => n.longValue()
            case _                         => 0L
          GameListEntry(d.getString("gameId"), ts)
        }
      }

  override def close(): Unit = client.close()

  private def toDto(doc: Document): GameSaveDto =
    val rawMoves = Option(doc.getList("moves", classOf[String])).map(_.asScala.toList).getOrElse(Nil)
    val savedAt  = Option(doc.get("savedAt")) match
      case Some(n: java.lang.Number) => n.longValue()
      case _                         => 0L
    GameSaveDto(
      gameId     = doc.getString("gameId"),
      currentFen = Option(doc.getString("currentFen")).getOrElse(""),
      pgn        = Option(doc.getString("pgn")).getOrElse(""),
      moves      = rawMoves,
      status     = Option(doc.getString("status")).getOrElse(""),
      savedAt    = savedAt
    )
