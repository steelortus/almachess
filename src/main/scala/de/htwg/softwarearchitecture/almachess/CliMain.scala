package de.htwg.softwarearchitecture.almachess

import de.htwg.softwarearchitecture.almachess.control.Controller
import de.htwg.softwarearchitecture.almachess.view.Tui
// $COVERAGE-OFF$

object CliMain:
  def main(args: Array[String]): Unit =
    val controller = new Controller()
    new Tui(controller).run()
    // $COVERAGE-ON$
