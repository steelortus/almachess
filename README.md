## AlMaChess

Testing out AI code generation for class "Software Architekturen".

### Start (lokal, ohne Docker)

Alle drei Services einzeln (siehe [`TEST-SPICKZETTEL.txt`](TEST-SPICKZETTEL.txt) für PowerShell-Details):

```bash
sbt "runMain de.htwg.softwarearchitecture.almachess.services.NotationService"
sbt "runMain de.htwg.softwarearchitecture.almachess.services.AiService"
ALMACHESS_AI_URL=http://localhost:8082 \
ALMACHESS_NOTATION_URL=http://localhost:8081 \
sbt "runMain de.htwg.softwarearchitecture.almachess.api.Server"
```

Monolith-Modus: einfach `sbt run` und im Menü Option 3 wählen.

### Start per Docker Compose

Alles zusammen — Main-API, NotationService, AiService und ein simples Web-Frontend:

```bash
docker compose up --build
```

- Main-API:        http://localhost:8080
- NotationService: http://localhost:8081
- AiService:       http://localhost:8082
- Web-Frontend:    http://localhost:8079

Das Frontend läuft hinter nginx und proxied `/api`, `/notation`, `/ai`, `/health`
auf die jeweiligen Container, sodass keine CORS-Konfiguration nötig ist.

Die API wartet dank `depends_on: condition: service_healthy` auf die beiden
Microservices, bevor sie startet.

Stoppen: `docker compose down`. Neuer Build nach Code-Änderung:
`docker compose up --build`.

### Smoke-Tests (nach dem Start)

```bash
curl -fsS http://localhost:8080/health       # {"status":"ok"}
curl -fsS http://localhost:8081/health
curl -fsS http://localhost:8082/health

curl -fsS http://localhost:8080/api/game

curl -fsS -X POST http://localhost:8080/api/game/reset
curl -fsS -X POST http://localhost:8080/api/game/move \
  -H 'Content-Type: application/json' -d '{"from":"e2","to":"e4"}'
curl -fsS -X POST http://localhost:8080/api/game/ai-move \
  -H 'Content-Type: application/json' -d '{"depth":2}'
```

Die vollständige Endpunkt-Übersicht steht in [`TEST-SPICKZETTEL.txt`](TEST-SPICKZETTEL.txt).
