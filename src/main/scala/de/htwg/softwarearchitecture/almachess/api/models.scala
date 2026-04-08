package de.htwg.softwarearchitecture.almachess.api

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}

// Request/Response models for PGN
case class PgnExportRequest()
case class PgnImportRequest(pgn: String)
case class PgnResponse(pgn: String)

// Request/Response models for FEN
case class FenExportRequest()
case class FenLoadRequest(fen: String)
case class FenResponse(fen: String)

// Generic response models
case class ErrorResponse(error: String)
case class SuccessResponse(message: String, data: Option[String] = None)

// JSON codec derivations
implicit val pgnExportRequestEncoder: Encoder[PgnExportRequest] = deriveEncoder
implicit val pgnExportRequestDecoder: Decoder[PgnExportRequest] = deriveDecoder

implicit val pgnImportRequestEncoder: Encoder[PgnImportRequest] = deriveEncoder
implicit val pgnImportRequestDecoder: Decoder[PgnImportRequest] = deriveDecoder

implicit val pgnResponseEncoder: Encoder[PgnResponse] = deriveEncoder
implicit val pgnResponseDecoder: Decoder[PgnResponse] = deriveDecoder

implicit val fenExportRequestEncoder: Encoder[FenExportRequest] = deriveEncoder
implicit val fenExportRequestDecoder: Decoder[FenExportRequest] = deriveDecoder

implicit val fenLoadRequestEncoder: Encoder[FenLoadRequest] = deriveEncoder
implicit val fenLoadRequestDecoder: Decoder[FenLoadRequest] = deriveDecoder

implicit val fenResponseEncoder: Encoder[FenResponse] = deriveEncoder
implicit val fenResponseDecoder: Decoder[FenResponse] = deriveDecoder

implicit val errorResponseEncoder: Encoder[ErrorResponse] = deriveEncoder
implicit val errorResponseDecoder: Decoder[ErrorResponse] = deriveDecoder

implicit val successResponseEncoder: Encoder[SuccessResponse] = deriveEncoder
implicit val successResponseDecoder: Decoder[SuccessResponse] = deriveDecoder
