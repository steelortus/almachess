package de.htwg.softwarearchitecture.almachess.api

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}

// Request/Response models for PGN
case class PgnExportRequest()
case class PgnImportRequest(pgn: String)
case class PgnResponse(pgn: String)

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

implicit val errorResponseEncoder: Encoder[ErrorResponse] = deriveEncoder
implicit val errorResponseDecoder: Decoder[ErrorResponse] = deriveDecoder

implicit val successResponseEncoder: Encoder[SuccessResponse] = deriveEncoder
implicit val successResponseDecoder: Decoder[SuccessResponse] = deriveDecoder
