package de.htwg.softwarearchitecture.almachess

import de.htwg.softwarearchitecture.almachess.control.Controller
import de.htwg.softwarearchitecture.almachess.view.Gui
import scala.swing.Swing

object Main:
  def main(args: Array[String]): Unit =
    Swing.onEDTWait {
      val controller = new Controller()
      val gui = new Gui(controller)
      gui.pack()
      gui.centerOnScreen()
      gui.visible = true
      println("GUI should be visible now")
    }