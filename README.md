## AlMaChess

Testing out AI code generation for class "Software Architekturen".

### Start (lokal, ohne Docker)

Alle drei Services einzeln (siehe [`TEST-SPICKZETTEL.txt`](TEST-SPICKZETTEL.txt) für PowerShell-Details).
Beim lokalen Start (ohne Docker) gelten die Code-Defaults:
NotationService 8081, AiService 8082, Main-API 8080.

```bash
sbt "runMain de.htwg.softwarearchitecture.almachess.services.NotationService"
sbt "runMain de.htwg.softwarearchitecture.almachess.services.AiService"
ALMACHESS_AI_URL=http://localhost:8082 \
ALMACHESS_NOTATION_URL=http://localhost:8081 \
sbt "runMain de.htwg.softwarearchitecture.almachess.api.Server"
```

Monolith-Modus: einfach `sbt run` und im Menü Option 3 wählen.

### Start per Docker Compose

Alles zusammen — Main-API, NotationService, AiService, Mongo/Postgres/Redis
und ein nginx-Web-Frontend:

```bash
docker compose up --build
```

- Web-Frontend:    http://localhost:8079
- Main-API:        http://localhost:8083
- NotationService: http://localhost:8084
- AiService:       http://localhost:8082

Das Frontend läuft hinter nginx und proxied `/api`, `/notation`, `/ai`, `/health`
auf die jeweiligen Container, sodass keine CORS-Konfiguration nötig ist.

Die API wartet dank `depends_on: condition: service_healthy` auf die beiden
Microservices und die Datenbanken (Mongo, Postgres, Redis), bevor sie startet.

Stoppen: `docker compose down`. Neuer Build nach Code-Änderung:
`docker compose up --build`.

Live-Edit am Frontend ohne Image-Rebuild:
`cp docker-compose.override.yml.example docker-compose.override.yml`
(mountet `web/html` und `web/nginx.conf` in den laufenden nginx-Container).

Ggf. baut schlägt dieser fehl, dann muss man das ganze ohne cached items neu builden:
```bash
docker compose build --no-cache
```

### Smoke-Tests (nach dem Start)

Über das Web-Frontend (nginx proxied alle Pfade auf die richtigen Services):

```bash
curl -fsS http://localhost:8079/health           # API /health
curl -fsS http://localhost:8079/notation/health  # NotationService /health
curl -fsS http://localhost:8079/ai/health        # AiService /health
curl -fsS http://localhost:8079/api/game
```

Oder direkt gegen die einzelnen Services:

```bash
curl -fsS http://localhost:8083/health       # {"status":"ok"}  Main-API
curl -fsS http://localhost:8084/health       # NotationService
curl -fsS http://localhost:8082/health       # AiService

curl -fsS http://localhost:8083/api/game

curl -fsS -X POST http://localhost:8083/api/game/reset
curl -fsS -X POST http://localhost:8083/api/game/move \
  -H 'Content-Type: application/json' -d '{"from":"e2","to":"e4"}'
curl -fsS -X POST http://localhost:8083/api/game/ai-move \
  -H 'Content-Type: application/json' -d '{"depth":2}'
```

Die vollständige Endpunkt-Übersicht steht in [`TEST-SPICKZETTEL.txt`](TEST-SPICKZETTEL.txt).

### Spark-Analytics (`analytics/spark`)

Eigenständiges Scala-2.13-Subprojekt (Spark gibt es nicht für Scala 3 — gleiches
Muster wie das Gatling-Subprojekt). Es ist nur über das Event-Format auf dem
Kafka-Topic `almachess.moves` mit dem Rest gekoppelt; jedes `MoveEvent` trägt
dafür `gameId` und `status` ("checkmate - White wins", "stalemate", …).

Spark braucht Java 17. Auf neueren JDKs vor dem Start einmal setzen:

```powershell
$env:ANALYTICS_JAVA_HOME = "C:\Pfad\zu\jdk-17"
```

Für den **Streaming**-Job auf Windows zusätzlich winutils (Hadoop-Shim für
Checkpoint-Dateien; der Batch-Job läuft auch ohne): `winutils.exe` +
`hadoop.dll` aus https://github.com/cdarlint/winutils (hadoop-3.3.5/bin)
nach `%USERPROFILE%\.hadoop\bin` legen und
`$env:HADOOP_HOME = "$env:USERPROFILE\.hadoop"` setzen.

**Schritt 1 — Batch aus Datei.** Liest `analytics/data/moves.jsonl`
(eine MoveEvent-JSON-Zeile pro Zug, exakt das Topic-Format) und rechnet:
Siege Weiß/Schwarz/Patt, Siege Mensch vs. KI, schnellste Matts (Highscore),
beliebteste Eröffnungen, Ø-Partielänge.

```bash
sbt "analytics/runMain de.htwg.softwarearchitecture.almachess.analytics.GameStatsBatch"
# Beispieldaten neu erzeugen (spielt Miniaturpartien durch den echten Controller):
sbt "runMain de.htwg.softwarearchitecture.almachess.tools.AnalyticsSampleData"
```

**Schritt 2 — Streaming aus Kafka.** Dieselben Aggregationen (geteilter Code in
`Aggregations.scala`), aber live per Structured Streaming vom Topic, plus
Züge/Minute im Tumbling Window. Der Compose-Broker hat dafür einen
EXTERNAL-Listener auf `localhost:9094`.

```bash
docker compose up -d            # Broker + Services starten
sbt "analytics/runMain de.htwg.softwarearchitecture.almachess.analytics.MoveStreamStats"
# dann im Web-Frontend (localhost:8079) Züge spielen und die Konsole beobachten
```

todo
1. ai spiel funktion einbauen
2. ai logik verbessern damit sie schneller ist und schlauer
3. blitz modus mit timer
