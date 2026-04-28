package de.htwg.softwarearchitecture.almachess.ai

import java.io.{BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter}
import scala.util.control.NonFatal

/** Thin UCI wrapper around an external Stockfish binary.
  *
  * One instance owns one subprocess. All public methods are synchronized so
  * concurrent HTTP requests cannot interleave UCI commands on the same pipe.
  */
final class StockfishEngine private (
    process: Process,
    in: BufferedReader,
    out: BufferedWriter,
    val engineName: String
):

  private val lock = new Object

  /** Best move in UCI notation, e.g. "e2e4" or "e7e8q". */
  def bestMove(
      fen: String,
      depth: Option[Int],
      movetimeMs: Option[Int],
      skill: Option[Int]
  ): Either[String, String] = lock.synchronized {
    try
      skill.foreach { s =>
        send(s"setoption name Skill Level value ${s.max(0).min(20)}")
      }
      send("ucinewgame")
      send("isready")
      readUntil(_ == "readyok")
      send(s"position fen $fen")
      val goCmd = movetimeMs match
        case Some(ms) => s"go movetime ${ms.max(1)}"
        case None     => s"go depth ${depth.getOrElse(12).max(1).min(40)}"
      send(goCmd)
      val bestLine = readUntil(_.startsWith("bestmove"))
      bestLine match
        case Some(line) =>
          val parts = line.split("\\s+")
          if parts.length >= 2 && parts(1) != "(none)" then Right(parts(1))
          else Left("no legal moves")
        case None => Left("engine pipe closed")
    catch case NonFatal(ex) => Left(s"stockfish error: ${ex.getMessage}")
  }

  def quit(): Unit = lock.synchronized {
    try
      send("quit")
      process.waitFor()
    catch case _: Throwable => ()
    finally
      try in.close() catch case _: Throwable => ()
      try out.close() catch case _: Throwable => ()
      if process.isAlive then process.destroyForcibly(): Unit
  }

  def isAlive: Boolean = process.isAlive

  private def send(cmd: String): Unit =
    out.write(cmd)
    out.newLine()
    out.flush()

  private def readUntil(pred: String => Boolean): Option[String] =
    var line: String = null
    while { line = in.readLine(); line != null } do
      if pred(line) then return Some(line)
    None

object StockfishEngine:

  /** Try to launch Stockfish. Performs UCI handshake; returns Right on success. */
  def launch(path: String): Either[String, StockfishEngine] =
    try
      val pb = new ProcessBuilder(path)
      pb.redirectErrorStream(true)
      val process = pb.start()
      val in  = new BufferedReader(new InputStreamReader(process.getInputStream))
      val out = new BufferedWriter(new OutputStreamWriter(process.getOutputStream))

      def send(cmd: String): Unit =
        out.write(cmd); out.newLine(); out.flush()

      send("uci")
      var name: String = "Stockfish"
      var line: String = null
      var sawUciOk = false
      while !sawUciOk && { line = in.readLine(); line != null } do
        if line.startsWith("id name ") then name = line.stripPrefix("id name ").trim
        else if line == "uciok" then sawUciOk = true

      if !sawUciOk then
        process.destroyForcibly()
        Left("no uciok received from stockfish")
      else
        send("isready")
        var sawReady = false
        while !sawReady && { line = in.readLine(); line != null } do
          if line == "readyok" then sawReady = true
        if !sawReady then
          process.destroyForcibly()
          Left("no readyok received from stockfish")
        else
          Right(new StockfishEngine(process, in, out, name))
    catch case NonFatal(ex) => Left(s"failed to start stockfish: ${ex.getMessage}")
