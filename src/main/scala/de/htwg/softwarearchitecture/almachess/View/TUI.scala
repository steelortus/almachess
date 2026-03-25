package de.htwg.softwarearchitecture.almachess.View

import de.htwg.softwarearchitecture.almachess.Model.{Board, Piece}

object Tui:
  def printBoard(board: Board): Unit =
    println("  | a b c d e f g h")
    println(board.toAscii)

  private def prompt(): String =
    print("> "); scala.io.StdIn.readLine().trim

  def run(): Unit =
    println("=== AlmaChess TUI ===")
    var board = Board.initial
    printBoard(board)

    var running = true
    while running do
      prompt() match
        case "quit" | "exit" => running = false; println("Quitting application...")
        case cmd if cmd.startsWith("move ") =>
          val pieces = cmd.stripPrefix("move ").split("\\s+")
          if pieces.length == 2 then
            board.move(pieces(0), pieces(1)) match
              case Right(next) =>
                board = next
                printBoard(board)
              case Left(err) =>
                println(s"[error] $err")
          else println("usage: move [target] [destination]")
        case other =>
          println(s"unknown command: '$other' (use `move e2 e4` or `quit`)")