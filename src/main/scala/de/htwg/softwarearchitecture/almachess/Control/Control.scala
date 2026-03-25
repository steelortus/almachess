package de.htwg.softwarearchitecture.almachess.Control

import de.htwg.softwarearchitecture.almachess.Model.Board
import de.htwg.softwarearchitecture.almachess.View.Tui

class Controller():
  def runTUI(): Unit =
    Tui.run()