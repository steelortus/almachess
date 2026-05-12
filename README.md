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

todo
1. ai spiel funktion einbauen
2. ai logik verbessern damit sie schneller ist und schlauer
3. blitz modus mit timer
