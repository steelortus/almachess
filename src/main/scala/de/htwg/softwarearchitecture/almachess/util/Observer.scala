package de.htwg.softwarearchitecture.almachess.util

trait Observer:
  def update(e: GameEvent): Unit
