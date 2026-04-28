package de.htwg.softwarearchitecture.almachess.persistence

import io.lettuce.core.{RedisClient, RedisURI, SetArgs}
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands

import java.util.concurrent.CompletionStage
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters.*

final class RedisLiveGameStore(
    host: String,
    port: Int,
    override val ttlSeconds: Int
)(using ec: ExecutionContext) extends LiveGameStore:

  override val name: String = "redis"

  private val uri: RedisURI                              = RedisURI.create(host, port)
  private val client: RedisClient                        = RedisClient.create(uri)
  private val connection: StatefulRedisConnection[String, String] = client.connect()
  private val cmd: RedisAsyncCommands[String, String]    = connection.async()

  private def key(sessionId: String): String = s"live-game:$sessionId"

  override def save(sessionId: String, game: GameSaveDto): Future[Unit] =
    val json = LiveGameJson.encode(game)
    val args = SetArgs.Builder.ex(ttlSeconds.toLong)
    asScalaFuture(cmd.set(key(sessionId), json, args)).map(_ => ())

  override def load(sessionId: String): Future[Option[GameSaveDto]] =
    asScalaFuture(cmd.get(key(sessionId))).map { raw =>
      Option(raw).map(LiveGameJson.decode)
    }

  override def delete(sessionId: String): Future[Unit] =
    asScalaFuture(cmd.del(key(sessionId))).map(_ => ())

  override def close(): Unit =
    connection.close()
    client.shutdown()

  private def asScalaFuture[T](cs: CompletionStage[T]): Future[T] =
    cs.asScala
