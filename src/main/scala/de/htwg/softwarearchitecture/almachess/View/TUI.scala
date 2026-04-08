package de.htwg.softwarearchitecture.almachess.view

import de.htwg.softwarearchitecture.almachess.control.Controller
import de.htwg.softwarearchitecture.almachess.parser.{Command, CommandParser}
import de.htwg.softwarearchitecture.almachess.model.Color
import scala.io.StdIn.readLine
import java.io.{File, PrintWriter}
import java.nio.file.Files

class Tui(controller: Controller):

  def run(): Unit =
    printHelp()

    var running = true
    while running do
      println()
      println(controller.ascii)
      readLine("> ") match
        case null                      => running = false
        case line if line.trim.isEmpty => ()
        case line =>
          CommandParser.parse(line) match
            case Left(err)  => println(s"[error] $err")
            case Right(cmd) => running = execute(cmd)

  /** Dispatches a parsed command. Returns false to stop the REPL. */
  private def execute(cmd: Command): Boolean =
    cmd match
      case Command.Exit => false

      case Command.Help =>
        printHelp(); true

      case Command.Show =>
        println(controller.ascii); true

      case Command.ShowFen =>
        println(controller.toFen); true

      case Command.ShowPgn =>
        println(controller.exportPgn()); true

      case Command.Undo =>
        controller.undo() match
          case Left(err) => println(s"[error] $err")
          case Right(_)  => println(s"[ok] ${controller.state.status}")
        true

      case Command.Redo =>
        controller.redo() match
          case Left(err) => println(s"[error] $err")
          case Right(_)  => println(s"[ok] ${controller.state.status}")
        true

      case Command.MoveCmd(from, to, promo) =>
        controller.move(from, to, promo) match
          case Left(err)  => println(s"[error] $err")
          case Right(msg) => println(msg)
        true

      case Command.LoadFen(fen) =>
        controller.loadFen(fen) match
          case Left(err)  => println(s"[error] $err")
          case Right(msg) => println(msg)
        true

      case Command.ExportPgn(path) =>
        writeFile(path, controller.exportPgn()) match
          case Left(err) => println(s"[error] $err")
          case Right(()) => println(s"[ok] PGN gespeichert: $path")
        true

      case Command.ImportPgn(path) =>
        readFile(path).flatMap(controller.importPgn) match
          case Left(err)  => println(s"[error] $err")
          case Right(msg) => println(s"[ok] $msg")
        true

      case Command.ExportJson(path) =>
        writeFile(path, controller.exportJson()) match
          case Left(err) => println(s"[error] $err")
          case Right(()) => println(s"[ok] JSON gespeichert: $path")
        true

      case Command.ImportJson(path) =>
        readFile(path).flatMap(controller.importJson) match
          case Left(err)  => println(s"[error] $err")
          case Right(msg) => println(s"[ok] $msg")
        true

      case Command.SetAiDepth(d) =>
        controller.setAiDepth(d)
        println(s"[ok] AI depth set to $d")
        true

      case Command.EnableAi(colorStr) =>
        val c = if colorStr == "white" then Color.White else Color.Black
        controller.enableAi(c, controller.currentAiDepth)
        println(s"[ok] AI enabled for $colorStr (depth ${controller.currentAiDepth})")
        true

      case Command.DisableAi =>
        controller.disableAi()
        println("[ok] AI disabled")
        true

  private def printHelp(): Unit =
    println("AlmaChess TUI")
    println("Befehle:")
    println("  move <from> <to> [<promo>]   e.g. move e2 e4, move e7 e8 q")
    println("  undo | redo")
    println("  show | fen | pgn")
    println("  load [fen] <FEN>")
    println("  export pgn <datei>  |  import pgn <datei>")
    println("  export json <datei> |  import json <datei>")
    println("  set ai depth <n>")
    println("  ai on white | ai on black | ai off")
    println("  help | exit")

  private def writeFile(path: String, content: String): Either[String, Unit] =
    try
      val pw = new PrintWriter(new File(path))
      try pw.write(content) finally pw.close()
      Right(())
    catch case e: Exception => Left(e.getMessage)

  private def readFile(path: String): Either[String, String] =
    try Right(new String(Files.readAllBytes(new File(path).toPath)))
    catch case e: Exception => Left(e.getMessage)
