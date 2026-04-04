package de.htwg.softwarearchitecture.almachess.view

import de.htwg.softwarearchitecture.almachess.control.Controller
import scala.io.StdIn.readLine
import java.io.{File, PrintWriter}
import java.nio.file.Files

class Tui(controller: Controller):

  def run(): Unit =
    println("AlmaChess TUI")
    println("Befehle:")
    println("  move e2 e4")
    println("  undo | redo")
    println("  fen | show")
    println("  load <fen>")
    println("  pgn")
    println("  export pgn <datei>  |  import pgn <datei>")
    println("  export json <datei> |  import json <datei>")
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

        case line if line.trim == "pgn" =>
          println(controller.exportPgn())

        case line if line.trim == "undo" =>
          controller.undo() match
            case Left(err)  => println(s"[error] $err")
            case Right(_)   => println(s"[ok] ${controller.state.status}")

        case line if line.trim == "redo" =>
          controller.redo() match
            case Left(err)  => println(s"[error] $err")
            case Right(_)   => println(s"[ok] ${controller.state.status}")

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

        case line if line.trim.startsWith("export pgn ") =>
          val path = line.trim.stripPrefix("export pgn ").trim
          writeFile(path, controller.exportPgn()) match
            case Left(err)  => println(s"[error] $err")
            case Right(())  => println(s"[ok] PGN gespeichert: $path")

        case line if line.trim.startsWith("import pgn ") =>
          val path = line.trim.stripPrefix("import pgn ").trim
          readFile(path).flatMap(controller.importPgn) match
            case Left(err)  => println(s"[error] $err")
            case Right(msg) => println(s"[ok] $msg")

        case line if line.trim.startsWith("export json ") =>
          val path = line.trim.stripPrefix("export json ").trim
          writeFile(path, controller.exportJson()) match
            case Left(err)  => println(s"[error] $err")
            case Right(())  => println(s"[ok] JSON gespeichert: $path")

        case line if line.trim.startsWith("import json ") =>
          val path = line.trim.stripPrefix("import json ").trim
          readFile(path).flatMap(controller.importJson) match
            case Left(err)  => println(s"[error] $err")
            case Right(msg) => println(s"[ok] $msg")

        case other =>
          println(s"[error] unknown command: $other")

  private def writeFile(path: String, content: String): Either[String, Unit] =
    try
      val pw = new PrintWriter(new File(path))
      try pw.write(content) finally pw.close()
      Right(())
    catch case e: Exception => Left(e.getMessage)

  private def readFile(path: String): Either[String, String] =
    try Right(new String(Files.readAllBytes(new File(path).toPath)))
    catch case e: Exception => Left(e.getMessage)
