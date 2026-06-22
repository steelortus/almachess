# Mongo Persistence — Baseline → Indexed

Same playbook as `perf/README.md` (FenParser), applied to the persistence
hotspot: `MongoGameRepository`. Endpoints under test:

| Verb   | Path                                  | Repo call         |
|--------|---------------------------------------|-------------------|
| POST   | `/api/persistence/games/{gameId}`     | `save` (upsert)   |
| GET    | `/api/persistence/games/{gameId}`     | `load`            |
| GET    | `/api/persistence/games`              | `list`            |
| DELETE | `/api/persistence/games/{gameId}`     | `delete`          |

All four filter or sort on `gameId` / `savedAt`, but the only index that
existed at baseline was the auto-created `_id_`. Every query was a full
collection scan over 10 008 documents.

---

## 1. Dataset (deterministic, identical across runs)

[`perf/mongo/seed.js`](mongo/seed.js) inserts `seed-00000`..`seed-09999`:

```powershell
docker cp perf/mongo/seed.js almachess-mongo:/tmp/seed.js
docker exec almachess-mongo mongosh --quiet --eval "var SEED_COUNT=10000;" /tmp/seed.js
```

`savedAt` is a deterministic hash of the index, so rank orderings reproduce
exactly. Three pre-existing test docs are kept (`probe-1`, `crash`, etc.) —
total = 10 008. The transient delete-and-restore range (`seed-tx-*`) is
created and torn down inside k6/Gatling, so it never accumulates.

---

## 2. Mongo plan changes (`explain('executionStats')`)

Capture: [`perf/mongo/explain.js`](mongo/explain.js) →
[`results/mongo-explain-baseline.json`](results/mongo-explain-baseline.json) ·
[`results/mongo-explain-optimized.json`](results/mongo-explain-optimized.json)

| Query | Baseline plan | docs / keys examined | exec ms | Optimized plan | docs / keys examined | exec ms |
|---|---|---|---|---|---|---|
| `find({gameId:"seed-04242"})` | COLLSCAN | 10 008 / 0 | 8 | IXSCAN(`gameId_unique`) → FETCH | 1 / 1 | 4 |
| `find({}).sort({savedAt:-1}).limit(50)` (with `_id`) | COLLSCAN + in-memory SORT | 10 008 / 0 | 9 | IXSCAN(`savedAt_desc_…`) → FETCH → LIMIT | 50 / 50 | 3 |
| `find({}, {gameId:1, savedAt:1, _id:0}).sort({savedAt:-1}).limit(50)` | COLLSCAN + SORT | 10 008 / 0 | 9 | **IXSCAN → PROJECTION_COVERED → LIMIT** | **0 / 50** | **0** |

The third row is the punchline: dropping `_id` from the projection turns the
list query into a **fully covered index scan** — Mongo never touches a single
document, just walks the index keys.

---

## 3. Optimizations applied

### 3a. Indexes ([perf/mongo/indexes.js](mongo/indexes.js))

```js
db.games.createIndex({ gameId: 1 }, { unique: true, name: 'gameId_unique' });
db.games.createIndex({ savedAt: -1, gameId: 1 }, { name: 'savedAt_desc_gameId_asc' });
```

Idempotent — same statements are issued by the repo on startup (see below),
so a fresh deployment ends up with both indexes without manual intervention.

### 3b. Repo code ([MongoGameRepository.scala](../src/main/scala/de/htwg/softwarearchitecture/almachess/persistence/MongoGameRepository.scala))

Three changes:

1. **Index bootstrap on construction** — `createIndex` calls fire-and-forget at
   startup; idempotent so they're safe to repeat on every restart.
2. **`list()` projection drops `_id`** — required so the planner picks
   `PROJECTION_COVERED` over `FETCH`. Without this the index speeds up sort
   but Mongo still pages every document.
3. **`list()` adds `limit(listLimit = 100)`** — caps the response size. At
   10 k+ rows the unbounded list was sending hundreds of KB per request *and*
   forcing a sort across the entire collection. Hard cap turns a O(n) operation
   into O(min(n, 100)).

> **API contract change:** `GET /api/persistence/games` now returns at most
> 100 entries. There is no pagination cursor yet — the next step is
> `?after=<savedAt>&limit=...` keyset pagination, which can ride on the same
> compound index.

---

## 4. HTTP layer numbers — k6 (20 VUs, 45 s, mix 60/25/10/5)

Script: [`perf/k6/persistence_mix.js`](k6/persistence_mix.js). Both runs
preceded by a 30–45 s warmup on the *same* code path so the JVM is JIT-hot
before the measurement window.

| Metric                       | Baseline    | Optimized   | Δ           |
|------------------------------|-------------|-------------|-------------|
| GET by id — p95              | 572.11 ms   | 261.32 ms   | **−54.3 %** |
| GET by id — p99              | 695.29 ms   | 340.50 ms   | **−51.0 %** |
| POST upsert — p95            | 536.66 ms   | 256.22 ms   | **−52.3 %** |
| POST upsert — p99            | 646.12 ms   | 361.35 ms   | **−44.1 %** |
| **LIST — p95**               | **1 200 ms**| **148.47 ms**| **−87.6 %** |
| LIST — p99                   | 1 570 ms    | 215.46 ms   | **−86.3 %** |
| DELETE — p95                 | 327.43 ms   | 151.13 ms   | **−53.8 %** |
| Throughput (HTTP)            | 42.9 req/s  | 157.5 req/s | **+267 %**  |
| Iterations completed         | 1 867       | 6 767       | +262 %      |
| Error rate                   | 0.00 %      | 0.00 %      | —           |

Raw output: [`results/k6-mongo-baseline.txt`](results/k6-mongo-baseline.txt) ·
[`results/k6-mongo-optimized.txt`](results/k6-mongo-optimized.txt) (JSON
summaries alongside).

### Threshold gates

The k6 script encodes *aspirational* targets so the baseline must fail and the
optimized run must pass — that's the contract:

```js
'mongo_get_by_id_latency':   ['p(95)<200', 'p(99)<500'],
'mongo_post_upsert_latency': ['p(95)<300', 'p(99)<800'],
'mongo_list_latency':        ['p(95)<800', 'p(99)<1500'],
'mongo_delete_latency':      ['p(95)<300']
```

- **Baseline** crosses 7/8 thresholds (everything except POST p99) → exit 99.
- **Optimized** passes 7/8. The lone remaining miss is GET p95 = 261 ms vs
  target 200 ms. The Mongo part of GET is ~5 ms (`IXSCAN → FETCH`); the
  remaining ~250 ms is in the API's `restoreFromDto` path inside the
  `sharedLock.synchronized` block (`controller.loadSnapshot` replays the move
  list against a fresh `GameState`). That is the next thing to attack — it is
  CPU-bound Scala, not Mongo.

---

## 5. HTTP layer numbers — Gatling (20 users, 45 s, same mix)

Sim: [`perf/gatling/.../PersistenceMixSimulation.scala`](gatling/src/test/scala/almachess/PersistenceMixSimulation.scala)

| Metric                    | Baseline   | Optimized  | Δ           |
|---------------------------|------------|------------|-------------|
| Global mean               | 376 ms     | 168 ms     | **−55.3 %** |
| Global p95                | 658 ms     | 312 ms     | **−52.6 %** |
| Global p99                | 925 ms     | 393 ms     | **−57.5 %** |
| Global max                | 1 685 ms   | 530 ms     | **−68.5 %** |
| GET /games/{id} p95       | 625 ms     | 331 ms     | −47.0 %     |
| POST /games/{id} p95      | 621 ms     | 351 ms     | −43.5 %     |
| GET /games (list) p95     | 779 ms     | 194 ms     | **−75.1 %** |
| Throughput                | 50.9 req/s | 115.4 req/s| **+126.7 %**|
| Failed                    | 0          | 0          | —           |
| Total requests            | 2 343      | 5 308      | +126.5 %    |

Raw output: [`results/gatling-mongo-baseline.txt`](results/gatling-mongo-baseline.txt) ·
[`results/gatling-mongo-optimized.txt`](results/gatling-mongo-optimized.txt).
HTML reports under
`perf/gatling/target/gatling/persistencemixsimulation-*/index.html`.

Both tools agree on direction and shape; absolute throughput differs
(k6 closed-loop 20 VUs ≈ 158 rps, Gatling open-loop ≈ 115 rps) for the same
HTTP-client-model reason as the FenParser tests.

---

## 6. Bottleneck and fix — narrative

**Where the time went, baseline:** every persistence endpoint walked all
10 008 documents. `find({gameId: …})` scanned the whole collection to return
one row; `find({}).sort({savedAt: -1})` did the same plus a memory sort.
Under 20 concurrent VUs that turns into ~200 k document scans/sec on a
single-core Mongo container — CPU and disk-cache pressure spike, all four
endpoints serialize behind the same hot collection.

**Fix, in order of impact (LIST → GET → POST/DELETE):**

1. `savedAt_desc_gameId_asc` + `_id:0` projection makes LIST a covered
   index scan. **Time inside Mongo drops from 9 ms (with sort over 10 008) to
   0 ms (covered, 50 keys read).** Combined with the `limit(100)` cap, the
   response payload also shrinks by ~100×, removing serialization cost.
2. `gameId_unique` makes `find({gameId})` an `IXSCAN(1) → FETCH(1)` instead
   of a full scan. Cuts GET/DELETE time in half end-to-end and unblocks the
   write path: Mongo no longer scans 10 008 docs to locate the row that
   `replaceOne(filter, doc, upsert=true)` will replace.
3. The unique constraint also catches data-quality bugs — duplicate
   `gameId`s now error at insert time instead of silently coexisting.

**What did NOT improve:** the `controller.restoreFromDto` step inside the
GET handler still costs ~250 ms p95. That work happens inside
`sharedLock.synchronized`, replays the move list, and is unrelated to Mongo.
That is the next bottleneck and explains why GET p95 sits at 261 ms even
though Mongo's part is now 4 ms — load tests don't lie about the layer below
either.

---

## 7. Reproducibility checklist

```powershell
# 1. Bring stack up, ALMACHESS_DB=mongo (default in docker-compose.yml)
docker compose up -d

# 2. Seed deterministic dataset (idempotent — re-deletes seed-* first)
docker cp perf/mongo/seed.js almachess-mongo:/tmp/seed.js
docker exec almachess-mongo mongosh --quiet --eval "var SEED_COUNT=10000;" /tmp/seed.js

# 3. Baseline explain — ONLY meaningful before indexes are created
docker cp perf/mongo/explain.js almachess-mongo:/tmp/explain.js
docker exec almachess-mongo mongosh --quiet --file /tmp/explain.js > perf/results/mongo-explain-baseline.json

# 4. Baseline k6 + Gatling
$perfPath = (Resolve-Path "perf").Path
docker run --rm -v "${perfPath}:/perf" -e BASE_URL=http://host.docker.internal:8083 `
  -e K6_VUS=20 -e K6_DURATION=45s grafana/k6 run `
  --summary-export=/perf/results/k6-mongo-baseline.json /perf/k6/persistence_mix.js
sbt 'gatlingTests/Gatling/testOnly almachess.PersistenceMixSimulation'

# 5. Apply indexes (idempotent) and rebuild API with the projection / limit fix
docker cp perf/mongo/indexes.js almachess-mongo:/tmp/indexes.js
docker exec almachess-mongo mongosh --quiet --file /tmp/indexes.js
docker compose build almachess-api
docker compose up -d --force-recreate almachess-api

# 6. Optimized explain
docker exec almachess-mongo mongosh --quiet --file /tmp/explain.js > perf/results/mongo-explain-optimized.json

# 7. Warmup + measurement (JIT matters — see perf/README.md)
docker run --rm -v "${perfPath}:/perf" -e BASE_URL=http://host.docker.internal:8083 `
  -e K6_VUS=20 -e K6_DURATION=45s grafana/k6 run --quiet /perf/k6/persistence_mix.js
docker run --rm -v "${perfPath}:/perf" -e BASE_URL=http://host.docker.internal:8083 `
  -e K6_VUS=20 -e K6_DURATION=45s grafana/k6 run `
  --summary-export=/perf/results/k6-mongo-optimized.json /perf/k6/persistence_mix.js
sbt 'gatlingTests/Gatling/testOnly almachess.PersistenceMixSimulation'
```

### Optional: Mongo profiler for ad-hoc investigation

```js
db.setProfilingLevel(1, { slowms: 20 });   // log every op > 20 ms
// run a load test, then:
db.system.profile.find().sort({ ts: -1 }).limit(20);
db.setProfilingLevel(0);                   // and turn it back off
```

Useful when k6 numbers move and you don't yet know whether Mongo or the JVM
moved them — it'll tell you per-op what Mongo saw.

---

## 8. File map

```
perf/
├── MONGO.md                          ← this report
├── mongo/
│   ├── seed.js                       ← deterministic 10 k seed
│   ├── indexes.js                    ← idempotent index creation
│   └── explain.js                    ← capture executionStats
├── k6/
│   └── persistence_mix.js            ← 4-endpoint mix + thresholds
├── gatling/src/test/scala/almachess/
│   └── PersistenceMixSimulation.scala
└── results/
    ├── mongo-explain-baseline.json
    ├── mongo-explain-optimized.json
    ├── k6-mongo-baseline.{txt,json}
    ├── k6-mongo-optimized.{txt,json}
    ├── gatling-mongo-baseline.txt
    └── gatling-mongo-optimized.txt
```

The repo-level changes are in
[`MongoGameRepository.scala`](../src/main/scala/de/htwg/softwarearchitecture/almachess/persistence/MongoGameRepository.scala);
the previous behavior is in git history immediately before this commit.
