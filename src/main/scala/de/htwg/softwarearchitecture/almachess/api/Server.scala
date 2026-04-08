package de.htwg.softwarearchitecture.almachess.api

import cats.effect.*
import cats.effect.unsafe.implicits.global
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.implicits.*
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.loggerFactoryforSync
import de.htwg.softwarearchitecture.almachess.control.Controller

object Server:
  def run(controller: Controller, port: Int = 8080): IO[Unit] =
    BlazeServerBuilder[IO]
      .bindHttp(port, "localhost")
      .withHttpApp(routes.allRoutes(controller).orNotFound)
      .serve
      .compile
      .drain
      .void

  def main(args: Array[String]): Unit =
    val controller = new Controller()
    val port = args.headOption.flatMap(_.toIntOption).getOrElse(8080)
    
    println(s"Starting Chess API server on http://localhost:$port")
    run(controller, port).unsafeRunSync()
