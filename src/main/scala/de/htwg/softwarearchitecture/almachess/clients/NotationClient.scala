package de.htwg.softwarearchitecture.almachess.clients

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.*
import akka.http.scaladsl.unmarshalling.Unmarshal
import de.htwg.softwarearchitecture.almachess.api.*
import de.htwg.softwarearchitecture.almachess.api.JsonFormats.given
import de.htwg.softwarearchitecture.almachess.parser.{FenParser, PgnParser}

import scala.concurrent.{ExecutionContext, Future}

trait NotationClient:
  def validateFen(fen: String): Future[Either[String, String]]
  def parsePgn(pgn: String): Future[Either[String, (Map[String, String], List[String])]]

object NotationClient:

  final class Local(ec: ExecutionContext) extends NotationClient:
    given ExecutionContext = ec
    def validateFen(fen: String): Future[Either[String, String]] =
      Future(FenParser.parse(fen).map(_.toFen))
    def parsePgn(pgn: String): Future[Either[String, (Map[String, String], List[String])]] =
      Future(PgnParser.parse(pgn))

  final class Http(baseUrl: String)(using system: ActorSystem[?]) extends NotationClient:
    given ExecutionContext = system.executionContext
    private val root = baseUrl.stripSuffix("/")

    def validateFen(fen: String): Future[Either[String, String]] =
      for
        entity <- Marshal(FenValidateRequest(fen)).to[RequestEntity]
        resp   <- akka.http.scaladsl.Http()(system).singleRequest(
                    HttpRequest(HttpMethods.POST, s"$root/notation/fen/validate", entity = entity))
        body   <- Unmarshal(resp.entity).to[FenValidateResponse]
      yield
        if body.valid then Right(body.fen.getOrElse(fen))
        else Left(body.error.getOrElse("invalid fen"))

    def parsePgn(pgn: String): Future[Either[String, (Map[String, String], List[String])]] =
      for
        entity <- Marshal(PgnParseRequest(pgn)).to[RequestEntity]
        resp   <- akka.http.scaladsl.Http()(system).singleRequest(
                    HttpRequest(HttpMethods.POST, s"$root/notation/pgn/parse", entity = entity))
        result <- {
          if resp.status.isFailure then {
            resp.discardEntityBytes()
            Future.successful(Left(s"notation service error: ${resp.status}"))
          } else {
            Unmarshal(resp.entity).to[PgnParseResponse].map(b => Right((b.tags, b.moves)))
          }
        }
      yield result
