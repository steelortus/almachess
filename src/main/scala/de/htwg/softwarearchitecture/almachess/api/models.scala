package de.htwg.softwarearchitecture.almachess.api

import spray.json.*
import spray.json.DefaultJsonProtocol.*

// --- Request models ---
case class MoveRequest(from: String, to: String, promotion: Option[String] = None)
case class FenLoadRequest(fen: String)
case class PgnImportRequest(pgn: String)
case class AiMoveRequest(depth: Option[Int] = None)

// --- Response models ---
case class HealthResponse(status: String)
case class GameStateResponse(
    fen: String,
    status: String,
    turn: String,
    canUndo: Boolean,
    canRedo: Boolean,
    gameOver: Boolean
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
case class BestMoveRequest(fen: String, depth: Option[Int] = None)
case class BestMoveResponse(move: Option[String], error: Option[String] = None)

object JsonFormats extends DefaultJsonProtocol:
  given RootJsonFormat[MoveRequest]         = jsonFormat3(MoveRequest.apply)
  given RootJsonFormat[FenLoadRequest]      = jsonFormat1(FenLoadRequest.apply)
  given RootJsonFormat[PgnImportRequest]    = jsonFormat1(PgnImportRequest.apply)
  given RootJsonFormat[AiMoveRequest]       = jsonFormat1(AiMoveRequest.apply)

  given RootJsonFormat[HealthResponse]      = jsonFormat1(HealthResponse.apply)
  given RootJsonFormat[GameStateResponse]   = jsonFormat6(GameStateResponse.apply)
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
  given RootJsonFormat[BestMoveRequest]     = jsonFormat2(BestMoveRequest.apply)
  given RootJsonFormat[BestMoveResponse]    = jsonFormat2(BestMoveResponse.apply)
