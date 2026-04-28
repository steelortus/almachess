package de.htwg.softwarearchitecture.almachess.persistence

import scala.concurrent.Future

trait LiveGameStore:
  def name: String
  def ttlSeconds: Int
  def save(sessionId: String, game: GameSaveDto): Future[Unit]
  def load(sessionId: String): Future[Option[GameSaveDto]]
  def delete(sessionId: String): Future[Unit]
  def close(): Unit = ()
