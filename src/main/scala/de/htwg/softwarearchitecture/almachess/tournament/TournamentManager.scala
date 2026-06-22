package de.htwg.softwarearchitecture.almachess.tournament

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{BroadcastHub, Keep, Source, SourceQueueWithComplete}
import akka.util.ByteString
import de.htwg.softwarearchitecture.almachess.clients.AiClient

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}

object TournamentManager:
  enum Phase:
    case Idle, Connecting, Joined, Playing, Finished, Error

  enum Engine:
    case Stockfish, Integrated  // Stockfish = HTTP AiService; Integrated = in-process Negamax

  final case class Status(
      phase: Phase,
      tournamentId: Option[String] = None,
      botId: Option[String] = None,
      botName: Option[String] = None,
      message: Option[String] = None,
      lastUpdated: Long = Instant.now().toEpochMilli
  )

  final case class StartRequest(
      tournamentBaseUrl: String,
      tournamentId: Option[String],   // None == auto-join first `created`
      botName: String,
      engine: Engine,
      depth: Int,
      movetimeMs: Option[Int],
      skill: Option[Int],
      aiServiceUrl: Option[String]    // required when engine == Stockfish
  )

  // Self-contained one-click test: registers a temp director, AlMaChess, and
  // (optionally) a built-in random-move sparring opponent; creates a tournament
  // with both pre-added; spawns the bots in-process; the director then starts
  // it after both are subscribed. The whole loop runs against any reachable
  // tournament server (HTWG-internal or wherever you deploy it).
  final case class QuickstartRequest(
      tournamentBaseUrl: String,
      tournamentName: String,
      nbRounds: Int,
      clockLimit: Int,
      botName: String,
      engine: Engine,
      depth: Int,
      movetimeMs: Option[Int],
      skill: Option[Int],
      aiServiceUrl: Option[String],
      withSparring: Boolean
  )

  final case class QuickstartResponse(tournamentId: String)

// Singleton lifecycle owner for the in-process tournament participation.
// Drives one TournamentBot at a time, captures every log line, and exposes
// them both as a rolling buffer (for late HTTP polling clients) and as an
// SSE source (for browser tail-follow). Pattern mirrors LichessRoutes's
// sessionHub so the rest of the stack only has to know one reactive-stream
// shape.
final class TournamentManager(using system: ActorSystem[?]):
  import TournamentManager.*
  given ExecutionContext = system.executionContext

  @volatile private var current: Status = Status(Phase.Idle)
  def status: Status = current
  private def updateStatus(s: Status): Unit =
    current = s.copy(lastUpdated = Instant.now().toEpochMilli)
    val _ = sseQueue.offer(ByteString(s"event: status\ndata: ${statusJson(current)}\n\n"))

  private def statusJson(s: Status): String =
    def opt(k: String, v: Option[String]): String = v.map(x => s""""$k":"${escape(x)}"""").getOrElse("")
    val parts = List(
      Some(s""""phase":"${s.phase}""""),
      s.tournamentId.map(v => s""""tournamentId":"$v""""),
      s.botId.map(v => s""""botId":"$v""""),
      s.botName.map(v => s""""botName":"${escape(v)}""""),
      s.message.map(v => s""""message":"${escape(v)}""""),
      Some(s""""lastUpdated":${s.lastUpdated}""")
    ).flatten
    parts.mkString("{", ",", "}")
  private def escape(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

  // Bounded ring buffer (newest at tail). Keeps the last 500 lines so a
  // late HTTP poller can snapshot recent activity even without SSE.
  private val logRing = new ConcurrentLinkedDeque[String]()
  private val maxBuffered = 500
  def recentLog: List[String] = logRing.iterator().asScala.toList

  private val (sseQueue, sseHub): (SourceQueueWithComplete[ByteString], Source[ByteString, NotUsed]) =
    Source
      .queue[ByteString](256, OverflowStrategy.dropHead)
      .toMat(BroadcastHub.sink[ByteString](bufferSize = 16))(Keep.both)
      .run()
  // Keep the hub alive even with zero subscribers — same trick as LichessRoutes.
  sseHub.runWith(akka.stream.scaladsl.Sink.ignore)

  def logSource: Source[ByteString, NotUsed] = sseHub

  // TournamentLog impl that pipes to ring buffer + SSE hub.
  private val managedLog: TournamentLog = new TournamentLog:
    def info(msg: String): Unit =
      val stamped = s"[${Instant.now()}] $msg"
      logRing.addLast(stamped)
      while logRing.size() > maxBuffered do logRing.pollFirst()
      val _ = sseQueue.offer(ByteString(s"event: log\ndata: ${escape(stamped)}\n\n"))
      println(stamped)

  @volatile private var stopRequested = false

  // Releases the start() guard so a fresh run() can begin. The previous
  // bot's Future is allowed to drain in the background — it'll finish when
  // its NDJSON stream closes or the tournament terminates. Not graceful,
  // but acceptable for a single-user dev UI.
  def stop(): Unit =
    stopRequested = true
    if current.phase != Phase.Idle && current.phase != Phase.Finished then
      managedLog.info("[manager] stop requested — previous bot will drain in background")
      updateStatus(Status(Phase.Idle, message = Some("stopped by user")))

  def start(req: StartRequest): Either[String, String] = synchronized {
    current.phase match
      case Phase.Connecting | Phase.Joined | Phase.Playing =>
        Left("tournament already in progress; stop first")
      case _ =>
        if req.engine == Engine.Stockfish && req.aiServiceUrl.forall(_.isEmpty) then
          Left("Stockfish engine selected but aiServiceUrl is empty")
        else
          stopRequested = false
          updateStatus(Status(Phase.Connecting, botName = Some(req.botName), message = Some("registering")))
          managedLog.info(s"[manager] starting bot=${req.botName} engine=${req.engine} depth=${req.depth}")
          run(req)
          Right("started")
  }

  private def run(req: StartRequest): Unit =
    val client = new TournamentClient(req.tournamentBaseUrl)
    val ai: AiClient = req.engine match
      case Engine.Stockfish  => new AiClient.Http(req.aiServiceUrl.get)
      case Engine.Integrated => new AiClient.Local(summon[ExecutionContext])
    val cfg = TournamentConfig(
      baseUrl = req.tournamentBaseUrl, botName = req.botName,
      aiDepth = req.depth, movetimeMs = req.movetimeMs, skill = req.skill,
      cachedToken = None, tournamentId = req.tournamentId, autoJoinFirstCreated = req.tournamentId.isEmpty
    )

    // The bootstrap mirrors TournamentMode but reports state changes
    // through updateStatus so the UI shows what's happening.
    val pipeline: Future[Unit] =
      for
        regResult <- client.register(req.botName, isBot = true)
        (identityId, token) = regResult match
          case Right(v)  => v
          case Left(err) => throw new RuntimeException(s"register failed: $err")
        botResult <- client.registerBot(token, req.botName)
        botId     = botResult match
          case Right(id) => id
          case Left(err) => throw new RuntimeException(s"registerBot failed: $err")
        _ = updateStatus(Status(Phase.Connecting, botId = Some(botId), botName = Some(req.botName), message = Some("resolving tournament")))
        tid <- req.tournamentId match
          case Some(id) => Future.successful(id)
          case None     => firstCreated(client)
        _ = managedLog.info(s"[manager] joining tournament $tid (${if req.tournamentId.isDefined then "from UI input" else "auto-picked first created"})")
        // Surface the actual tournament state so the user sees whether it's
        // still in `created` (waiting for a director to start it) or already
        // `started`. A stale `created` lobby with no director is the classic
        // "why is nothing happening" trap.
        _ <- client.listTournaments().map {
          case Right(obj) =>
            import spray.json.{JsArray, JsObject, JsString, JsNumber}
            def lookup(group: String): Option[JsObject] =
              obj.fields.get(group).collect { case JsArray(xs) => xs }.getOrElse(Vector.empty)
                .map(_.asJsObject).find(_.fields.get("id").contains(JsString(tid)))
            val (group, info) = (List("created","started","finished").map(g => g -> lookup(g)).collectFirst {
              case (g, Some(o)) => (g, o)
            }).getOrElse(("?", null))
            val n = if info != null then info.fields.get("nbPlayers").collect { case JsNumber(n) => n.toInt }.getOrElse(0) else 0
            managedLog.info(s"[manager] tournament $tid is in '$group' with $n players")
            if group == "created" then
              managedLog.info(s"[manager] WARN: this tournament has not been started by its director — we will sit idle until they do")
          case _ => ()
        }
        _ <- client.joinTournament(token, tid).map {
          case Right(_)  => ()
          case Left(err) => throw new RuntimeException(s"join failed: $err")
        }
        _ = updateStatus(Status(Phase.Joined, Some(tid), Some(botId), Some(req.botName), Some("subscribed to event stream")))
        bot = new TournamentBot(client, ai, cfg, managedLog, botId, token, tid)
        _ = updateStatus(Status(Phase.Playing, Some(tid), Some(botId), Some(req.botName)))
        _ <- bot.runUntilFinished()
      yield ()

    pipeline.onComplete {
      case Success(_) =>
        managedLog.info("[manager] tournament loop completed")
        updateStatus(current.copy(phase = Phase.Finished, message = Some("tournament finished")))
      case Failure(ex) =>
        managedLog.info(s"[manager] aborted: ${ex.getMessage}")
        updateStatus(current.copy(phase = Phase.Error, message = Some(ex.getMessage)))
    }

  // Quickstart: returns the new TID immediately (after registration + create);
  // the actual play happens asynchronously in the background.
  def quickstart(req: QuickstartRequest): Future[Either[String, String]] = synchronized {
    current.phase match
      case Phase.Connecting | Phase.Joined | Phase.Playing =>
        Future.successful(Left("tournament already in progress; stop first"))
      case _ =>
        stopRequested = false
        if req.engine == Engine.Stockfish && req.aiServiceUrl.forall(_.isEmpty) then
          Future.successful(Left("Stockfish engine selected but aiServiceUrl is empty"))
        else
          updateStatus(Status(Phase.Connecting, botName = Some(req.botName),
            message = Some(s"registering identities${if req.withSparring then " + sparring" else ""}")))
          managedLog.info(s"[manager] quickstart: name='${req.tournamentName}' engine=${req.engine} sparring=${req.withSparring}")
          quickstartImpl(req)
  }

  private def quickstartImpl(req: QuickstartRequest): Future[Either[String, String]] =
    val client = new TournamentClient(req.tournamentBaseUrl)
    val ts    = System.currentTimeMillis()
    val dirName  = s"alma-qs-dir-$ts"
    val sparName = s"alma-qs-spar-$ts"

    def unwrap[A](f: Future[Either[String, A]], label: String): Future[A] =
      f.map { case Right(v) => v; case Left(err) => throw new RuntimeException(s"$label: $err") }

    val bootstrap: Future[(String, String, String, Option[(String, String)])] =
      for
        dir <- unwrap(client.register(dirName, isBot = false), "director register")
        (_, dirTok) = dir
        alma <- unwrap(client.register(req.botName, isBot = true), "alma register")
        (_, almaTok) = alma
        almaId <- unwrap(client.registerBot(almaTok, req.botName), "alma registerBot")
        sparring <-
          if !req.withSparring then Future.successful(None)
          else
            for
              sp <- unwrap(client.register(sparName, isBot = true), "sparring register")
              (_, spTok) = sp
              spId <- unwrap(client.registerBot(spTok, sparName), "sparring registerBot")
            yield Some((spId, spTok))
      yield (dirTok, almaId, almaTok, sparring)

    val createAndStart = bootstrap.flatMap { case (dirTok, almaId, almaTok, sparring) =>
      // Create the tournament EMPTY — no `bots:` form param. Both bots then
      // self-join. This path works on every server build we've seen (the
      // older one had a bug where bots added via `bots:` couldn't submit
      // moves because the move-auth check compared JWT sub to the
      // registry id stored in the pairing).
      for
        tid <- unwrap(
          client.createTournament(dirTok, req.tournamentName, req.nbRounds, req.clockLimit, Nil),
          "createTournament")
        _ = managedLog.info(s"[manager] quickstart created tournament $tid (empty, bots self-join)")
        _ = updateStatus(Status(Phase.Joined, Some(tid), Some(almaId), Some(req.botName),
              Some(if req.withSparring then "starting AlMaChess + sparring" else "starting AlMaChess")))
      yield (dirTok, almaId, almaTok, sparring, tid)
    }

    // Return TID as soon as the tournament is created. The actual play
    // orchestration (both /joins, director /start, bot loop) runs in the
    // background so the HTTP /quickstart response is fast (<3s) regardless
    // of how long the play takes. Without this split, a slow tournament
    // server makes Akka's request-timeout kick in and the UI sees a plain
    // "The server was not able to produce a timely response" HTML response
    // instead of JSON.
    createAndStart.map { case (dirTok, almaId, almaTok, sparring, tid) =>
      runQuickstartPlay(client, req, dirTok, almaId, almaTok, sparring, tid)
      Right(tid): Either[String, String]
    }.recover { case ex =>
      managedLog.info(s"[manager] quickstart failed: ${ex.getMessage}")
      updateStatus(current.copy(phase = Phase.Error, message = Some(ex.getMessage)))
      Left(ex.getMessage)
    }

  private def runQuickstartPlay(
      client: TournamentClient,
      req: QuickstartRequest,
      dirTok: String,
      almaId: String,
      almaTok: String,
      sparring: Option[(String, String)],
      tid: String
  ): Unit =
    val ai: AiClient = req.engine match
      case Engine.Stockfish  => new AiClient.Http(req.aiServiceUrl.get)
      case Engine.Integrated => new AiClient.Local(summon[ExecutionContext])
    val cfg = TournamentConfig(
      baseUrl = req.tournamentBaseUrl, botName = req.botName,
      aiDepth = req.depth, movetimeMs = req.movetimeMs, skill = req.skill,
      cachedToken = Some(almaTok), tournamentId = Some(tid), autoJoinFirstCreated = false)
    val almaJoin = client.joinTournament(almaTok, tid)
    val sparringJoin = sparring match
      case Some((_, spTok)) => client.joinTournament(spTok, tid)
      case None             => Future.successful(Right(akka.Done))
    val ready = for { _ <- almaJoin; _ <- sparringJoin } yield ()
    ready.flatMap { _ =>
      val almaBot = new TournamentBot(client, ai, cfg, managedLog, almaId, almaTok, tid)
      updateStatus(Status(Phase.Playing, Some(tid), Some(almaId), Some(req.botName)))
      almaBot.runUntilFinished().onComplete {
        case Success(_) =>
          managedLog.info("[manager] quickstart bot loop completed")
          updateStatus(current.copy(phase = Phase.Finished, message = Some("tournament finished")))
        case Failure(ex) =>
          managedLog.info(s"[manager] quickstart aborted: ${ex.getMessage}")
          updateStatus(current.copy(phase = Phase.Error, message = Some(ex.getMessage)))
      }
      sparring.foreach { case (spId, spTok) =>
        new TournamentSparring(client, managedLog, spId, spTok, tid).runUntilFinished()
      }
      // Brief pause so both bot streams beat the first roundStarted/gameStart.
      akka.pattern.after(1500.millis, system.classicSystem.scheduler)(Future.successful(()))
        .flatMap(_ => client.startTournament(dirTok, tid))
    }.onComplete {
      case Success(Right(_)) =>
        managedLog.info(s"[manager] quickstart director started tournament $tid")
      case Success(Left(err)) =>
        managedLog.info(s"[manager] startTournament failed: $err")
        updateStatus(current.copy(phase = Phase.Error, message = Some(err)))
      case Failure(ex) =>
        managedLog.info(s"[manager] quickstart play failed: ${ex.getMessage}")
        updateStatus(current.copy(phase = Phase.Error, message = Some(ex.getMessage)))
    }

  private def firstCreated(client: TournamentClient): Future[String] =
    client.listTournaments().map {
      case Right(obj) =>
        import spray.json.{JsArray, JsString}
        val created = obj.fields.get("created").collect { case JsArray(xs) => xs }.getOrElse(Vector.empty)
        created.headOption
          .flatMap(_.asJsObject.fields.get("id"))
          .collect { case JsString(v) => v }
          .getOrElse(throw new RuntimeException("no created tournaments to join"))
      case Left(err) => throw new RuntimeException(s"listTournaments failed: $err")
    }
