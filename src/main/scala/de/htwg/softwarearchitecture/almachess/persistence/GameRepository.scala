package de.htwg.softwarearchitecture.almachess.persistence

import akka.NotUsed
import akka.stream.scaladsl.Source

import scala.concurrent.{ExecutionContext, Future}

trait GameRepository:
  def name: String
  def save(game: GameSaveDto): Future[Unit]
  def load(gameId: String): Future[Option[GameSaveDto]]
  def delete(gameId: String): Future[Unit]
  def list(): Future[List[GameListEntry]]
  def close(): Unit = ()

  /** Reactive stream of UCI moves for a game, emitted in move-index order.
    * Backends that support cursor-based streaming (Slick `db.stream(...)`,
    * Mongo `Observable`) should override; the default falls back to a
    * one-shot `load` + per-element emission so the API surface is uniform. */
  def streamMoves(gameId: String)(using ec: ExecutionContext): Source[String, NotUsed] =
    Source
      .future(load(gameId))
      .mapConcat {
        case Some(dto) => dto.moves
        case None      => Nil
      }
