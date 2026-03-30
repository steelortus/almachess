package de.htwg.softwarearchitecture.almachess.util

trait Observable:
  private var subscribers: Vector[Observer] = Vector.empty

  def add(s: Observer): Unit =
    subscribers = subscribers :+ s

  def remove(s: Observer): Unit =
    subscribers = subscribers.filterNot(_ == s)

  def notifyObservers(e: GameEvent): Unit =
    subscribers.foreach(_.update(e))
