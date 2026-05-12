package de.htwg.softwarearchitecture.almachess.api

import spray.json.*
import spray.json.DefaultJsonProtocol.*

// --- Request models ---
case class MoveRequest(from: String, to: String, promotion: Option[String] = None)
case class FenLoadRequest(fen: String)
case class PgnImportRequest(pgn: String)
case class AiMoveRequest(
    depth: Option[Int] = None,
    movetime: Option[Int] = None,
    skill: Option[Int] = None
)

// --- Response models ---
case class HealthResponse(status: String)
case class GameStateResponse(
    fen: String,
    status: String,
    turn: String,
    canUndo: Boolean,
    canRedo: Boolean,
    gameOver: Boolean,
    lastMove: Option[String] = None
)
case class FenResponse(fen: String)
case class PgnResponse(pgn: String)
case class SuccessResponse(message: String, fen: Option[String] = None)
case class ErrorResponse(error: String)
case class HistoryResponse(moves: List[String])
case class LegalMovesResponse(from: Option[String], moves: List[String])
case class AiMoveResponse(move: String, state: GameStateResponse)

// --- Notation service DTOs ---
case class FenValidateRequest(fen: String)
case class FenValidateResponse(valid: Boolean, fen: Option[String], error: Option[String])
case class PgnParseRequest(pgn: String)
case class PgnParseResponse(tags: Map[String, String], moves: List[String])

// --- AI service DTOs ---
case class BestMoveRequest(
    fen: String,
    depth: Option[Int] = None,
    movetime: Option[Int] = None,
    skill: Option[Int] = None
)
case class BestMoveResponse(move: Option[String], error: Option[String] = None)
case class EvaluateRequest(fen: String, depth: Option[Int] = None)
case class EvaluateResponse(
    centipawns: Option[Int]    = None,
    mate:       Option[Int]    = None,
    bestMove:   Option[String] = None,
    depth:      Option[Int]    = None,
    error:      Option[String] = None
)
case class AiStatusResponse(
    enabled: Boolean,
    backend: String,
    engine: String,
    fallback: Boolean
)

// --- Persistence DTOs ---
case class PersistenceGameDto(
    gameId: String,
    currentFen: String,
    pgn: String,
    moves: List[String],
    status: String,
    savedAt: Long,
    initialFen: String
)
case class PersistenceListEntry(gameId: String, savedAt: Long)
case class PersistenceListResponse(games: List[PersistenceListEntry])
case class PersistenceStatusResponse(enabled: Boolean, backend: String)

// --- Live (Redis) DTOs ---
case class LiveGameDto(
    gameId: String,
    currentFen: String,
    pgn: String,
    moves: List[String],
    status: String,
    savedAt: Long,
    initialFen: String
)
case class LiveStatusResponse(enabled: Boolean, backend: String, ttlSeconds: Int)

// --- Lichess DTOs ---
case class LichessChallengeRequest(
    username: String,
    mode: String,
    color: String,
    timeControl: String,
    rated: Boolean
)
case class LichessMoveRequest(from: String, to: String, promotion: Option[String] = None)
case class LichessAutoMoveRequest(
    depth: Option[Int] = None,
    movetime: Option[Int] = None,
    skill: Option[Int] = None
)
case class LichessAutoMoveResponse(move: String, status: LichessStatusResponse)
case class LichessSessionDto(
    gameId: String,
    mode: String,             // "board" | "bot"
    yourColor: String,        // "white" | "black"
    fen: String,
    yourTurn: Boolean,
    gameOver: Boolean,
    status: String,
    turn: String,
    lastMove: Option[String] = None,
    opponent: Option[String] = None,
    moves: List[String] = Nil,
    streamStatus: String = "",
    // Live clock (None for correspondence games)
    whiteMs: Option[Long] = None,
    blackMs: Option[Long] = None,
    clockInitialMs: Option[Long] = None,
    clockIncrementMs: Option[Long] = None,
    // Side currently in check, or None
    inCheck: Option[String] = None,
    // Set when the game ends and Lichess names a winner
    winner: Option[String] = None
)
case class LichessStatusResponse(
    enabled: Boolean,
    baseUrl: String,
    boardToken: Boolean,      // Board API token (human plays)
    botToken: Boolean,        // Bot API token (engine plays)
    session: Option[LichessSessionDto] = None
)

object JsonFormats extends DefaultJsonProtocol:
  given RootJsonFormat[MoveRequest]         = jsonFormat3(MoveRequest.apply)
  given RootJsonFormat[FenLoadRequest]      = jsonFormat1(FenLoadRequest.apply)
  given RootJsonFormat[PgnImportRequest]    = jsonFormat1(PgnImportRequest.apply)
  given RootJsonFormat[AiMoveRequest]       = jsonFormat3(AiMoveRequest.apply)

  given RootJsonFormat[HealthResponse]      = jsonFormat1(HealthResponse.apply)
  given RootJsonFormat[GameStateResponse]   = jsonFormat7(GameStateResponse.apply)
  given RootJsonFormat[FenResponse]         = jsonFormat1(FenResponse.apply)
  given RootJsonFormat[PgnResponse]         = jsonFormat1(PgnResponse.apply)
  given RootJsonFormat[SuccessResponse]     = jsonFormat2(SuccessResponse.apply)
  given RootJsonFormat[ErrorResponse]       = jsonFormat1(ErrorResponse.apply)
  given RootJsonFormat[HistoryResponse]     = jsonFormat1(HistoryResponse.apply)
  given RootJsonFormat[LegalMovesResponse]  = jsonFormat2(LegalMovesResponse.apply)
  given RootJsonFormat[AiMoveResponse]      = jsonFormat2(AiMoveResponse.apply)

  given RootJsonFormat[FenValidateRequest]  = jsonFormat1(FenValidateRequest.apply)
  given RootJsonFormat[FenValidateResponse] = jsonFormat3(FenValidateResponse.apply)
  given RootJsonFormat[PgnParseRequest]     = jsonFormat1(PgnParseRequest.apply)
  given RootJsonFormat[PgnParseResponse]    = jsonFormat2(PgnParseResponse.apply)
  given RootJsonFormat[BestMoveRequest]     = jsonFormat4(BestMoveRequest.apply)
  given RootJsonFormat[BestMoveResponse]    = jsonFormat2(BestMoveResponse.apply)
  given RootJsonFormat[EvaluateRequest]     = jsonFormat2(EvaluateRequest.apply)
  given RootJsonFormat[EvaluateResponse]    = jsonFormat5(EvaluateResponse.apply)
  given RootJsonFormat[AiStatusResponse]    = jsonFormat4(AiStatusResponse.apply)

  given RootJsonFormat[PersistenceGameDto]        = jsonFormat7(PersistenceGameDto.apply)
  given RootJsonFormat[PersistenceListEntry]      = jsonFormat2(PersistenceListEntry.apply)
  given RootJsonFormat[PersistenceListResponse]   = jsonFormat1(PersistenceListResponse.apply)
  given RootJsonFormat[PersistenceStatusResponse] = jsonFormat2(PersistenceStatusResponse.apply)

  given RootJsonFormat[LiveGameDto]        = jsonFormat7(LiveGameDto.apply)
  given RootJsonFormat[LiveStatusResponse] = jsonFormat3(LiveStatusResponse.apply)

  given RootJsonFormat[LichessChallengeRequest] = jsonFormat5(LichessChallengeRequest.apply)
  given RootJsonFormat[LichessMoveRequest]      = jsonFormat3(LichessMoveRequest.apply)
  given RootJsonFormat[LichessAutoMoveRequest]  = jsonFormat3(LichessAutoMoveRequest.apply)
  given RootJsonFormat[LichessSessionDto]       = jsonFormat18(LichessSessionDto.apply)
  given RootJsonFormat[LichessStatusResponse]   = jsonFormat5(LichessStatusResponse.apply)
  given RootJsonFormat[LichessAutoMoveResponse] = jsonFormat2(LichessAutoMoveResponse.apply)
