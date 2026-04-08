package de.htwg.softwarearchitecture.almachess.api

import org.http4s.{HttpRoutes, QueryParamDecoder}
import org.http4s.dsl.io.*
import org.http4s.circe.CirceEntityCodec.*
import cats.effect.IO
import de.htwg.softwarearchitecture.almachess.control.Controller

object routes:
  def pgnRoutes(controller: Controller): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "api" / "pgn" / "export" =>
      val pgn = controller.exportPgn()
      Ok(PgnResponse(pgn))

    case req @ POST -> Root / "api" / "pgn" / "import" =>
      req.as[PgnImportRequest].attempt.flatMap {
        case Left(err) =>
          BadRequest(ErrorResponse(s"Invalid request body: ${err.getMessage}"))
        case Right(importReq) =>
          controller.importPgn(importReq.pgn) match
            case Left(err) => 
              BadRequest(ErrorResponse(err))
            case Right(msg) => 
              Ok(SuccessResponse(msg, Some(controller.toFen)))
      }
  }

  def allRoutes(controller: Controller): HttpRoutes[IO] =
    pgnRoutes(controller)
