package de.htwg.softwarearchitecture.almachess.persistence

import scala.concurrent.Future

trait GameRepository:
  def name: String
  def save(game: GameSaveDto): Future[Unit]
  def load(gameId: String): Future[Option[GameSaveDto]]
  def delete(gameId: String): Future[Unit]
  def list(): Future[List[GameListEntry]]
  def close(): Unit = ()
