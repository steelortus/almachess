package de.htwg.softwarearchitecture.almachess.util

import de.htwg.softwarearchitecture.almachess.Model.Color

trait Observer {
    def update(e: GameEvent): Unit
}

trait Observable {
    var subscribers: Vector[Observer] = Vector()
    def add(s: Observer) = subscribers = subscribers :+ s
    def remove(s: Observer) = subscribers = subscribers.filterNot(o => o == s)
    def notifyObservers(e: GameEvent) = subscribers.foreach(o => o.update(e))
}

enum GameEvent:
    case kingCaptured