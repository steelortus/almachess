package de.htwg.softwarearchitecture.almachess.ai

import java.io.{BufferedReader, InputStreamReader}

/** Tiny UCI-protocol echo server used by [[StockfishEngineSpec]] to stand in
 *  for a real Stockfish binary. The behaviour is selected by the first arg
 *  (mode), which determines how `go` and friends are answered.
 */
object FakeStockfish:

  def main(args: Array[String]): Unit =
    val mode = args.headOption.getOrElse("happy")
    val in   = new BufferedReader(new InputStreamReader(System.in))
    val out  = System.out
    var line: String = null
    while { line = in.readLine(); line != null } do
      handle(line.trim, mode, out)

  private def handle(cmd: String, mode: String, out: java.io.PrintStream): Unit =
    cmd match
      case "uci" =>
        out.println("id name FakeStockfish 99")
        out.println("id author AlmaChess Test")
        out.println("uciok")
        out.flush()
      case "isready" =>
        out.println("readyok")
        out.flush()
      case s if s.startsWith("setoption ") => ()
      case "ucinewgame"                    => ()
      case s if s.startsWith("position ")  => ()
      case s if s.startsWith("go ") =>
        mode match
          case "bestmove-e2e4" =>
            out.println("info depth 1 nodes 20 score cp 42")
            out.println("bestmove e2e4")
          case "bestmove-none" =>
            out.println("bestmove (none)")
          case "evaluate-cp" =>
            out.println("info depth 5 nodes 100 score cp 30")
            out.println("info depth 10 nodes 500 score cp 50")
            out.println("bestmove e2e4")
          case "evaluate-mate" =>
            out.println("info depth 8 nodes 100 score mate 3")
            out.println("bestmove h7h8q")
          case _ =>
            out.println("bestmove e2e4")
        out.flush()
      case "quit" =>
        out.flush()
        System.exit(0)
      case _ => ()
