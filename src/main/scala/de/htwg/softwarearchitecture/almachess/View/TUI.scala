package de.htwg.softwarearchitecture.almachess.view

import de.htwg.softwarearchitecture.almachess.control.Controller
import scala.io.StdIn.readLine

class Tui(controller: Controller):

  def run(): Unit =
    println("AlmaChess TUI")
    println("Befehle:")
    println("  move e2 e4")
    println("  fen")
    println("  load <fen>")
    println("  show")
    println("  exit")

    var running = true
    while running do
      println()
      println(controller.ascii)
      readLine("> ") match
        case null =>
          running = false

        case line if line.trim == "exit" =>
          running = false

        case line if line.trim == "show" =>
          println(controller.ascii)

        case line if line.trim == "fen" =>
          println(controller.toFen)

        case line if line.trim.startsWith("load ") =>
          val fen = line.trim.stripPrefix("load ").trim
          controller.loadFen(fen) match
            case Left(err)  => println(s"[error] $err")
            case Right(msg) => println(msg)

        case line if line.trim.startsWith("move ") =>
          val parts = line.trim.split("\\s+").toList
          parts match
            case "move" :: from :: to :: Nil =>
              controller.move(from, to) match
                case Left(err)  => println(s"[error] $err")
                case Right(msg) => println(msg)
            case "move" :: from :: to :: promo :: Nil =>
              controller.move(from, to, Some(promo)) match
                case Left(err)  => println(s"[error] $err")
                case Right(msg) => println(msg)
            case _ =>
              println("[error] usage: move <from> <to> [promotion]")

        case other =>
          println(s"[error] unknown command: $other")

          // start tui sbt "runMain de.htwg.softwarearchitecture.almachess.CliMain"