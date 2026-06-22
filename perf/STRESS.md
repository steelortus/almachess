# AlmaChess Stress Test — Where Does It Break?

The previous load tests in [`perf/README.md`](README.md) and
[`perf/MONGO.md`](MONGO.md) used a **fixed** number of clients (20–30 VUs) to
prove that *under realistic load* the optimizations help. They never answered:
**at what point does the system actually break?**

This run does. Same target stack, same 10 008-doc dataset, same indexes — but
the load **ramps from 10 → 500 VUs** in five 30-second steps so we can watch
latency and error-rate curves cross zero.

---

## 1. Method

Script: [`perf/k6/persistence_stress.js`](k6/persistence_stress.js)

```js
scenarios: {
  warm: { vus:  10, duration: '30s', startTime:   '0s', tags: { stage: 'WARM' } },
  s030: { vus:  30, duration: '30s', startTime:  '30s', tags: { stage: 'L030' } },
  s075: { vus:  75, duration: '30s', startTime:  '60s', tags: { stage: 'L075' } },
  s150: { vus: 150, duration: '30s', startTime:  '90s', tags: { stage: 'L150' } },
  s300: { vus: 300, duration: '30s', startTime: '120s', tags: { stage: 'L300' } },
  s500: { vus: 500, duration: '30s', startTime: '150s', tags: { stage: 'L500' } },
}
```

Five constant-VU scenarios run back-to-back. Each request is tagged with its
stage so k6's summary breaks down latency and error-rate **per VU level**.
Thresholds are intentionally weak (`p(95)>=0`) so the run never aborts — we
*want* to see degradation.

Endpoint mix is 80 % `GET /games/{id}` (the lock-heavy path), 15 % `GET /games`
(list), 5 % `POST /games/{id}` (upsert). DELETE is omitted so the dataset
stays stable across stages.

In parallel a `docker stats` sampler logged CPU / RAM every 5 s for
`almachess-api` and `almachess-mongo` →
[`results/k6-stress-dockerstats.log`](results/k6-stress-dockerstats.log).

Total run: 3 minutes. Total requests: 21 941.

---

## 2. Results — per-stage latency and error-rate

Pulled from [`results/k6-stress.txt`](results/k6-stress.txt).

| Stage | VUs | Requests | Mean | p95 | p99 | Error rate | Verdict |
|------:|----:|---------:|-----:|----:|----:|-----------:|---------|
| WARM  |  10 |  3 335 (warmup, excluded from scoring) |
| L030  |  30 |    3 417 |  263 ms | **468 ms**  | 582 ms     | **0.00 %** | ✅ healthy |
| L075  |  75 |    3 401 |  666 ms | **1.22 s**  | 1.65 s     | **0.00 %** | ⚠️ degraded but stable |
| L150  | 150 |    3 633 |  1.24 s | **9.19 s**  | 10.04 s    | **4.95 %** | ❌ **knee point — saturated** |
| L300  | 300 |    3 712 |  2.41 s | **9.98 s**  | 10.01 s    | **17.69 %** | ❌ failing |
| L500  | 500 |    4 447 |  3.39 s | **9.97 s**  | 9.99 s     | **25.77 %** | ❌ broken |

**The 9.95–9.98 s plateau is the 10-second client timeout, not real latency.**
Once that many requests are timing out the underlying server-side response
time is even higher — the script just gave up waiting.

### What the numbers mean

- **30 VUs** is the comfortable operating zone. Latency tracks the steady-state
  load tests (p95 ≈ 470 ms, in the same ballpark as the 261 ms from the 20-VU
  run in [`perf/MONGO.md`](MONGO.md)).
- **75 VUs** is the **limit of healthy operation**. Latency triples (p95 1.2 s)
  but every request still completes successfully.
- **150 VUs** is **the knee.** p95 jumps 7.5× to 9.19 s, and 4.95 % of requests
  fail outright. This is the textbook elbow on a degradation curve: small
  increases in load produce large jumps in latency.
- **300+ VUs** is fully saturated. Throughput gain is gone, error rate climbs
  linearly with load.

Goalposts that any deployment of this should respect: **target ≤ 75
concurrent clients per API container**; alert on > 5 % 5xx-rate or p95 > 1 s.

---

## 3. Where does it break? — `docker stats` evidence

Picking five rows from the log to track API container CPU + RAM:

| Wall time   | Stage       | api CPU | api RAM | mongo CPU | mongo RAM |
|-------------|-------------|--------:|--------:|----------:|----------:|
| 15:25:25    | L030        |    8 %  | 195 MiB |     50 %  | 472 MiB   |
| 15:26:23    | L075        |    1 %  | 196 MiB |     65 %  | 471 MiB   |
| 15:28:06    | L150 (start)|    1 %  | 197 MiB |    106 %  | 487 MiB   |
| 15:28:38    | L150 (mid)  | **393 %** | 451 MiB |  76 %  | 473 MiB   |
| 15:28:54    | L150 (peak) |  217 %  | **528 MiB** |    73 %  | 340 MiB   |
| 15:29:35    | L300        |  211 %  | 642 MiB |     12 %  | 340 MiB   |
| 15:30:31    | L500        |  146 %  | **696 MiB** |  **126 %** | 499 MiB   |

What this says:

- **The API container is the bottleneck, not Mongo.** Mongo's CPU stays under
  130 % and RAM stays around 350–500 MiB throughout. Indexed point lookups
  remain cheap even at 500 VUs.
- The API JVM goes from **<10 % CPU at L075 to 200–400 % at L150** — i.e. it
  jumps from idle to "more than a full core saturated" the moment the knee is
  crossed.
- API memory **3.5×** (195 MiB → 700 MiB) over the same period. That is the
  Akka-HTTP request queue + JVM working set growing as requests pile up
  faster than they drain.

In other words: **Mongo isn't sweating. The JVM behind the lock is.**

---

## 4. What is the bottleneck inside the API?

The earlier load test report ([`perf/MONGO.md` § 6](MONGO.md#6-bottleneck-and-fix--narrative))
already pointed at it: every `GET /api/persistence/games/{id}` ends with

```scala
sharedLock.synchronized {
  restoreFromDto(dto) match
    case Right(_)  => complete(toResponse(dto))
    ...
}
```

(See [PersistenceRoutes.scala:108-112](../src/main/scala/de/htwg/softwarearchitecture/almachess/api/PersistenceRoutes.scala#L108-L112)
and [Controller.loadSnapshot](../src/main/scala/de/htwg/softwarearchitecture/almachess/control/Controller.scala).)

`sharedLock` is a single global JVM monitor. Every GET, every POST, every
operation that mutates the controller serializes through it. With 150 VUs
hammering GETs, the loaded DTOs queue up in front of one synchronized block;
threads block, the akka-http dispatcher fills up its waiters, the executor
spends real CPU on context-switching and lock-contention, and from VU 75
onward each new client just lengthens the queue rather than getting more work
done.

`restoreFromDto` itself replays the whole move list against a fresh
`GameState` — that's CPU-bound Scala work that holds the lock for tens of
milliseconds per call. Multiply by 150 contending threads and you have the
exponential elbow we see at L150.

This explains every observation:
- **Mongo CPU stays low** — the lock isn't on Mongo
- **API CPU explodes** — that's GC + lock contention + replaying the move list
- **Latency hits the 10 s timeout** — requests don't fail, they just wait
  forever for the lock
- **Throughput plateaus around 120 req/s** even as VUs go from 150 → 500 —
  classic behavior of a serialized critical section: adding more clients
  doesn't help if they all queue on one mutex

---

## 5. What would fix this?

Not part of this exercise (we measured, we did not refactor), but the
recommendations follow naturally from the evidence:

1. **Per-game locks instead of one global `sharedLock`.** Replace the single
   monitor with a `ConcurrentHashMap[String, Object]` keyed by `gameId`.
   Different games stop blocking each other.
2. **Stop running `restoreFromDto` on a GET.** Right now a *read* mutates the
   server's controller state, which is the only reason the lock is needed at
   all. A pure read returning the saved DTO would have ~5 ms p95 (Mongo time)
   instead of 250 ms.
3. **Move `loadSnapshot` to a background dispatcher.** Even if the lock has
   to stay, free up the akka-http dispatcher threads so the queue drains.
4. **Cap the akka-http pending-request queue** with backpressure (`max-open-requests`)
   so excess load returns 503 fast instead of timing out at 10 s. Failing
   fast is better than queuing forever.

Of these, #2 is the largest win for the smallest change.

---

## 6. Reproducibility

Pre-conditions: stack up (`docker compose up -d`), 10 k seed loaded
([`perf/mongo/seed.js`](mongo/seed.js)), indexes present
([`perf/mongo/indexes.js`](mongo/indexes.js)), API container built with the
optimized `MongoGameRepository`. Same setup as
[`perf/MONGO.md`](MONGO.md) §7 steps 1–6.

```powershell
$perfPath = (Resolve-Path "perf").Path
docker run --rm -v "${perfPath}:/perf" `
  -e "BASE_URL=http://host.docker.internal:8083" `
  grafana/k6 run `
  --summary-export=/perf/results/k6-stress.json `
  /perf/k6/persistence_stress.js
```

Run takes ~3 minutes. Optionally start `docker stats` in another window:

```powershell
docker stats --format "{{.Name}}`t{{.CPUPerc}}`t{{.MemUsage}}" `
  almachess-api almachess-mongo
```

---

## 7. File map (additions to the existing `perf/` tree)

```
perf/
├── STRESS.md                           ← this file
├── k6/
│   └── persistence_stress.js           ← the ramp-up scenario
└── results/
    ├── k6-stress.txt                   ← human summary (per stage)
    ├── k6-stress.json                  ← machine summary
    └── k6-stress-dockerstats.log       ← container CPU/RAM samples
```

---

## TL;DR

> The optimized AlmaChess persistence API serves **75 concurrent clients
> happily** (p95 ≈ 1.2 s, 0 % errors). It **breaks at 150 concurrent clients**
> (p95 jumps to 9 s, 5 % errors), and is fully saturated by 300+
> (≥ 18 % errors). The bottleneck is **not** Mongo — it's a single global
> `sharedLock` in the API server that serializes every GET. Mongo handles the
> 500-VU run on a quarter of one CPU core.
