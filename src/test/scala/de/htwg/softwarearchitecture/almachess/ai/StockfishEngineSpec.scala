package de.htwg.softwarearchitecture.almachess.ai

import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.mutable.ListBuffer

class StockfishEngineSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll:

  private val openEngines = ListBuffer.empty[StockfishEngine]
  private val initialFen  = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  override def afterAll(): Unit =
    openEngines.foreach { e => try e.quit() catch case _: Throwable => () }

  // sbt's test classloader does not expose itself via java.class.path
  // (the system property only contains sbt-launch.jar). Walk the classloader
  // chain to collect every URL that contributes to the test classpath.
  private lazy val discoveredClasspath: String =
    val urls = scala.collection.mutable.LinkedHashSet.empty[String]
    var cl: ClassLoader = Thread.currentThread.getContextClassLoader
    while cl != null do
      cl match
        case ucl: java.net.URLClassLoader =>
          ucl.getURLs.foreach { u =>
            try urls += new java.io.File(u.toURI).getAbsolutePath
            catch case _: Throwable => ()
          }
        case _ => ()
      cl = cl.getParent
    if urls.isEmpty then System.getProperty("java.class.path")
    else urls.mkString(java.io.File.pathSeparator)

  // Generates a launcher (.bat on Windows, .sh elsewhere) that invokes the
  // currently-running JVM with the test classpath to execute `FakeStockfish`
  // with the given mode.
  private def fakeLauncher(mode: String): String =
    val isWin    = System.getProperty("os.name").toLowerCase.contains("win")
    val javaHome = System.getProperty("java.home")
    val javaExe  = if isWin then s"$javaHome\\bin\\java.exe"
                   else        s"$javaHome/bin/java"
    val cp       = discoveredClasspath

    val ext      = if isWin then ".bat" else ".sh"
    val script   = Files.createTempFile("fake-stockfish-", ext)
    script.toFile.deleteOnExit()

    val content =
      if isWin then
        s"""@echo off\r\n"$javaExe" -cp "$cp" de.htwg.softwarearchitecture.almachess.ai.FakeStockfish $mode\r\n"""
      else
        s"""#!/bin/sh\nexec "$javaExe" -cp "$cp" de.htwg.softwarearchitecture.almachess.ai.FakeStockfish $mode\n"""

    Files.write(script, content.getBytes(StandardCharsets.UTF_8))
    if !isWin then script.toFile.setExecutable(true)
    script.toAbsolutePath.toString

  private def launchFake(mode: String): StockfishEngine =
    val script = fakeLauncher(mode)
    val r      = StockfishEngine.launch(script)
    r match
      case Left(err) =>
        fail(s"could not launch fake stockfish via $script — $err")
      case Right(engine) =>
        openEngines += engine
        engine

  "StockfishEngine.launch" should {
    "return Left for a non-existent binary path" in {
      val result = StockfishEngine.launch("/this/path/does/not/exist/stockfish")
      result shouldBe a[Left[_, _]]
      result.left.toOption.get should include("failed to start")
    }

    "return Right and capture engine name when fake responds with uciok/readyok" in {
      val engine = launchFake("happy")
      engine.engineName shouldBe "FakeStockfish 99"
      engine.isAlive    shouldBe true
    }
  }

  "StockfishEngine.bestMove" should {
    "parse 'bestmove e2e4' from the engine pipe" in {
      val engine = launchFake("bestmove-e2e4")
      val result = engine.bestMove(initialFen, depth = Some(2), movetimeMs = None, skill = None)
      result shouldBe Right("e2e4")
    }

    "return Left('no legal moves') when the engine emits 'bestmove (none)'" in {
      val engine = launchFake("bestmove-none")
      val result = engine.bestMove(initialFen, depth = Some(2), movetimeMs = None, skill = None)
      result shouldBe Left("no legal moves")
    }

    "honor a movetime override without erroring" in {
      val engine = launchFake("bestmove-e2e4")
      val result = engine.bestMove(initialFen, depth = None, movetimeMs = Some(50), skill = Some(5))
      result shouldBe Right("e2e4")
    }
  }

  "StockfishEngine.evaluate" should {
    "parse the deepest 'info score cp' line" in {
      val engine = launchFake("evaluate-cp")
      val result = engine.evaluate(initialFen, depth = Some(10))
      result shouldBe a[Right[_, _]]
      val ev = result.toOption.get
      ev.centipawns shouldBe Some(50) // last cp wins
      ev.mate       shouldBe None
      ev.bestMove   shouldBe Some("e2e4")
      ev.depth      shouldBe 10
    }

    "parse 'info score mate' lines" in {
      val engine = launchFake("evaluate-mate")
      val result = engine.evaluate(initialFen, depth = Some(8))
      result shouldBe a[Right[_, _]]
      val ev = result.toOption.get
      ev.mate       shouldBe Some(3)
      ev.centipawns shouldBe None
      ev.bestMove   shouldBe Some("h7h8q")
    }

    "flip score sign when black is to move" in {
      // Same fake (mate 3 from STM POV); use a black-to-move FEN — engine
      // should normalise to White-POV by negating.
      val engine = launchFake("evaluate-mate")
      val blackToMove = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1"
      val result = engine.evaluate(blackToMove, depth = Some(8))
      result.toOption.get.mate shouldBe Some(-3)
    }
  }

  "StockfishEngine.quit" should {
    "terminate the underlying process" in {
      val engine = launchFake("happy")
      engine.isAlive shouldBe true
      engine.quit()
      engine.isAlive shouldBe false
    }

    "be safe to call twice" in {
      val engine = launchFake("happy")
      engine.quit()
      noException should be thrownBy engine.quit()
    }
  }
