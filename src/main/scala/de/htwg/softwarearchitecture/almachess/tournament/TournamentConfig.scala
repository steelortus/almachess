package de.htwg.softwarearchitecture.almachess.tournament

// Configuration for the central tournament server (Team Now-Chess).
// All values can be overridden via env vars so a single fat-jar can run
// both as the AlMaChess HTTP server (existing api.Server) and as a
// tournament participant (this package's TournamentMode).
//
// HTWG-internal staging: http://141.37.123.132:8086 (current build).
// Public staging is on an older build at the time of writing.
final case class TournamentConfig(
    baseUrl: String,
    botName: String,
    aiDepth: Int,
    movetimeMs: Option[Int],
    skill: Option[Int],          // Stockfish "Skill Level" (0-20); ignored by Local
    cachedToken: Option[String],
    tournamentId: Option[String],
    autoJoinFirstCreated: Boolean
)

object TournamentConfig:
  def fromEnv(): TournamentConfig =
    TournamentConfig(
      baseUrl              = sys.env.getOrElse("TOURNAMENT_BASE_URL", "http://141.37.123.132:8086").stripSuffix("/"),
      botName              = sys.env.getOrElse("TOURNAMENT_BOT_NAME", "AlMaChess"),
      aiDepth              = sys.env.get("TOURNAMENT_AI_DEPTH").flatMap(_.toIntOption).getOrElse(8),
      movetimeMs           = sys.env.get("TOURNAMENT_AI_MOVETIME_MS").flatMap(_.toIntOption),
      skill                = sys.env.get("TOURNAMENT_AI_SKILL").flatMap(_.toIntOption),
      cachedToken          = sys.env.get("TOURNAMENT_TOKEN").filter(_.nonEmpty),
      tournamentId         = sys.env.get("TOURNAMENT_ID").filter(_.nonEmpty),
      autoJoinFirstCreated = sys.env.get("TOURNAMENT_AUTO_JOIN").contains("true")
    )
