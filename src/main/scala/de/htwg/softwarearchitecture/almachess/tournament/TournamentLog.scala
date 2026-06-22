package de.htwg.softwarearchitecture.almachess.tournament

// Lightweight log seam for the tournament module. The TournamentBot and
// TournamentSession used to call println directly; we route through this
// trait so the API-server impl can feed the same lines into an SSE stream
// that the browser UI subscribes to.
trait TournamentLog:
  def info(msg: String): Unit

object TournamentLog:
  // Plain stdout. Used by the standalone TournamentMode entry point.
  object Console extends TournamentLog:
    def info(msg: String): Unit = println(msg)

  // Forwards every line to `sink` (typically a thread-safe enqueue into
  // a BroadcastHub-backed SSE source) AND stdout. Console echo helps when
  // we tail server logs to debug a bot that's also being driven from the UI.
  final class Tee(sink: String => Unit) extends TournamentLog:
    def info(msg: String): Unit =
      sink(msg)
      println(msg)
