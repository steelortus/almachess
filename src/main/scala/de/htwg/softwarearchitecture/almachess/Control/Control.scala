package de.htwg.softwarearchitecture.almachess.Control

import de.htwg.softwarearchitecture.almachess.Model.Board
import de.htwg.softwarearchitecture.almachess.View.Tui
import de.htwg.softwarearchitecture.almachess.util.Observable

class Controller() extends Observable:
  def runTUI(): Unit =
    Tui.run()