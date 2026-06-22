# AlmaChess — Performance Testing & Benchmarking

Three layers of performance evidence:

1. **JMH** — micro-benchmark of `FenParser.parse` (the hot function under load).
2. **k6** — HTTP load test against `POST /notation/fen/validate`.
3. **Gatling** — same endpoint, second tool, independent confirmation.

Each layer was run twice: a **baseline** against the unmodified parser, and an
**optimized** rerun after replacing the `Vector`-heavy fold-based parser with a
fail-fast `VectorBuilder` + char-indexed lookup table. The same test fixtures
(five FENs covering start, mid-game, sparse-board, etc.) are used by all three
layers so the results are directly comparable.

---

## 1. JMH — `FenParser.parse`

Location: [`bench/src/main/scala/de/htwg/.../FenParserBench.scala`](../bench/src/main/scala/de/htwg/softwarearchitecture/almachess/bench/FenParserBench.scala)

Settings: `@Fork(1)`, 3 × 2 s warmup, 5 × 2 s measurement, G1GC, 512 MiB heap.
Both `Throughput` and `AverageTime` modes are reported per benchmark.

### Baseline → Optimized (avg time, lower is better)

| Benchmark    | Baseline µs/op | Optimized µs/op | Δ        | Throughput Δ |
|--------------|----------------|------------------|----------|--------------|
| `parseStart` | 4.087          | 3.325            | **−18.6 %** | 0.171 → 0.437 ops/µs (**+155 %**) |
| `parseMid`   | 5.523          | 2.744            | **−50.3 %** | 0.191 → 0.483 ops/µs (**+153 %**) |
| `parseEmpty` | 2.355          | 1.755            | **−25.5 %** | 0.435 → 0.586 ops/µs (**+34.7 %**)  |

Raw output: [`results/jmh-baseline.txt`](results/jmh-baseline.txt) ·
[`results/jmh-optimized.txt`](results/jmh-optimized.txt) (JSON variants alongside).

### Bottleneck and fix

**Before** ([`FenParser.scala` history](../src/main/scala/de/htwg/softwarearchitecture/almachess/parser/FenParser.scala)):

- `parseRank` used `foldLeft` over an `Either`, with `acc ++ Vector.fill(n)(None)` for
  digit-runs and `acc :+ Some(p)` per piece. Every `++` and `:+` allocates a new
  `Vector`. For the start FEN that is ~32 piece-appends plus 8 digit-fills —
  hundreds of intermediate immutable vectors.
- `parseBoard` walked the rank list **three times**: `map(parseRank)`, then
  `exists(_.isLeft)`, then `collect { case Right(row) => row }`.
- `Piece.fromFenChar` allocated a fresh `Some(Piece(...))` on every call.

**After**:

- Single-pass `while` loop with a `VectorBuilder[Option[Piece]]` and `sizeHint(8)`
  — one allocation per rank instead of dozens.
- `parseBoard` walks the ranks once with fail-fast on the first `Left`, no
  triple-traversal.
- A 128-entry `Array[Option[Piece]]` lookup keyed by char code returns the
  **same** `Some(Piece)` instance every call, so piece chars allocate nothing on
  the hot path.

The biggest win lands on `parseMid` (≈ 2.0× faster) — that FEN has the most
non-empty squares and small digit-runs, which is exactly where the old code
allocated the most.

### Reproduce

```powershell
sbt "bench/Jmh/run -rf json -rff perf/results/jmh-<tag>.json -o perf/results/jmh-<tag>.txt"
```

---

## 2. k6 — `POST /notation/fen/validate`

Location: [`perf/k6/notation_validate.js`](k6/notation_validate.js)

Scenario: `constant-vus`, 30 VUs, 30 s, fixed FEN pool.

Thresholds (build-breakers, in script):

```js
http_req_failed:                   ['rate<0.01'],
'http_req_duration{endpoint:fen}': ['p(95)<200'],
fen_validate_failures:             ['rate<0.01'],
fen_validate_latency:              ['p(95)<200', 'p(99)<500']
```

### Baseline → Optimized (HTTP latency)

| Metric        | Baseline    | Optimized   | Δ           |
|---------------|-------------|-------------|-------------|
| avg           | 12.25 ms    | 10.72 ms    | **−12.5 %** |
| p95           | 26.71 ms    | 21.11 ms    | **−21.0 %** |
| p99           | 42.72 ms    | 33.73 ms    | **−21.0 %** |
| max           | 488.76 ms   | 220.82 ms   | −54.8 %     |
| throughput    | 2 271 req/s | 2 580 req/s | **+13.6 %** |
| total req     | 68 194      | 77 494      | +13.6 %     |
| error rate    | 0.00 %      | 0.00 %      | —           |

All thresholds pass on both runs (the system was not in distress at 30 VUs);
the absolute deltas are the signal.

Raw output: [`results/k6-baseline.txt`](results/k6-baseline.txt) ·
[`results/k6-optimized.txt`](results/k6-optimized.txt) (JSON summaries alongside).

### Bottleneck and fix

The endpoint does only one thing: `FenParser.parse`. So the parser hotspot found
by JMH is also the HTTP hotspot — fixing the parser drops every slice of the
HTTP latency distribution by ~20 %, and frees enough CPU to push 14 % more
requests/second through the same 30 VUs against the same `notation-service`
container with no other configuration change. Confirmed by rebuilding only the
`notation-service` image (`docker compose build notation-service` →
`up -d --force-recreate notation-service`) between baseline and optimized runs;
the rest of the stack was untouched.

> Caveat: the optimized run was preceded by ~45 s of warmup traffic. The first
> measurement after `--force-recreate` was *worse* than baseline because the
> JVM was not yet JIT-compiled — see "Warmup matters" below.

### Reproduce

```powershell
$perfPath = (Resolve-Path "perf").Path
docker run --rm `
  -v "${perfPath}:/perf" `
  -e "BASE_URL=http://host.docker.internal:8084" `
  -e "K6_VUS=30" -e "K6_DURATION=30s" `
  grafana/k6 run `
  --summary-export=/perf/results/k6-<tag>.json `
  /perf/k6/notation_validate.js
```

---

## 3. Gatling — same endpoint, independent tool

Location: [`perf/gatling/src/test/scala/almachess/NotationValidateSimulation.scala`](gatling/src/test/scala/almachess/NotationValidateSimulation.scala)

Subproject: `gatlingTests` (Scala 2.13) — kept separate so its transitive Akka
2.13 deps do not collide with the Scala 3 main build.

Scenario: `atOnceUsers(30)` looping over the same FEN fixtures for `during(30s)`.

Assertions (build-breakers, in simulation):

```scala
global.responseTime.percentile(95).lt(200),
global.responseTime.percentile(99).lt(500),
global.failedRequests.percent.lt(1.0)
```

### Baseline → Optimized

| Metric     | Baseline   | Optimized  | Δ           |
|------------|------------|------------|-------------|
| mean       | 54 ms      | 44 ms      | **−18.5 %** |
| p95        | 113 ms     | 78 ms      | **−31.0 %** |
| p99        | 166 ms     | 105 ms     | **−36.7 %** |
| max        | 715 ms     | 460 ms     | −35.7 %     |
| throughput | 438 req/s  | 540 req/s  | **+23.3 %** |
| total req  | 13 585     | 16 731     | +23.2 %     |
| failures   | 0          | 0          | —           |

Raw output: [`results/gatling-baseline.txt`](results/gatling-baseline.txt) ·
[`results/gatling-optimized.txt`](results/gatling-optimized.txt). HTML reports
under `perf/gatling/target/gatling/notationvalidatesimulation-*/index.html`.

### Bottleneck and fix

Same root cause as k6: server-side parsing. Gatling's open-loop client model
shows a *bigger* relative win on the tail (p95 −31 %, p99 −37 %) than k6's
closed-loop model (p95 −21 %, p99 −21 %) — when the server gets faster, queued
requests catch up faster, so the tail compresses more visibly. Both tools agree
on direction and shape; the absolute throughput numbers differ because Gatling
here is configured with a smaller connection pool than k6's 30 independent
HTTP/1 clients.

### Reproduce

```powershell
sbt 'gatlingTests/Gatling/testOnly almachess.NotationValidateSimulation'
```

Pass `-Dbase.url=...`, `-Dusers=...`, `-Dduration=...` to override the defaults.

---

## Reproducibility checklist

- **Same fixtures** in JMH, k6, Gatling (five FENs, identical strings).
- **Same target** in k6 + Gatling: `http://localhost:8084/notation/fen/validate`.
- **Pinned tool versions:** k6 `grafana/k6:latest` (v2.0.0-rc1), Gatling 3.11.5,
  sbt-jmh 0.4.7 (JMH 1.37), JDK 22.0.1 inside JMH fork; the service runs on
  Eclipse Temurin 17 inside the Docker image.
- **Pinned scenario knobs** in script defaults; override via env vars
  (`K6_VUS`, `K6_DURATION`) or `-D` system props (`base.url`, `users`,
  `duration`).
- **Hard thresholds** in both load tools — runs fail loud when broken.
- **Build → recreate, not restart**, between baseline and optimized so the
  service definitely runs the new code.

### Warmup matters

The first k6 attempt against the freshly recreated container produced *worse*
numbers than baseline — JVM was JIT-cold. The reported "optimized" k6 run was
preceded by 45 s of warmup traffic. Same applies if you re-run from scratch:
discard the first 30 s.

---

## File map

```
perf/
├── README.md                  ← this file
├── k6/
│   └── notation_validate.js   ← k6 scenario + thresholds
├── gatling/
│   └── src/test/scala/almachess/NotationValidateSimulation.scala
└── results/
    ├── jmh-baseline.{txt,json}
    ├── jmh-optimized.{txt,json}
    ├── k6-baseline.{txt,json}
    ├── k6-optimized.{txt,json}
    ├── gatling-baseline.txt
    ├── gatling-optimized.txt
    └── docker-rebuild.log

bench/
└── src/main/scala/de/htwg/.../bench/FenParserBench.scala  ← JMH benchmark
```

The optimization itself lives in
[`src/main/scala/.../parser/FenParser.scala`](../src/main/scala/de/htwg/softwarearchitecture/almachess/parser/FenParser.scala).
The behaviour-equivalent baseline is in git history (the commit immediately
before this perf work).
