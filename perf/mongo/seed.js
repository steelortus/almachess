// Deterministic Mongo seed for AlmaChess persistence load tests.
//
// Inserts SEED_COUNT documents into almachess.games with stable gameId values
// "seed-00000".."seed-NNNNN" so baseline and optimized runs hit the exact
// same dataset. savedAt is spread across a fixed window so the LIST sort has
// real work to do.
//
// Run:
//   docker exec -i almachess-mongo mongosh \
//     --quiet --eval "var SEED_COUNT=10000;" \
//     /perf/mongo/seed.js
//
// Or via volume mount:
//   docker exec -e SEED_COUNT=10000 almachess-mongo \
//     mongosh --quiet --file /perf/mongo/seed.js

const COUNT = (typeof SEED_COUNT === 'number' && SEED_COUNT > 0)
  ? SEED_COUNT
  : parseInt((typeof process !== 'undefined' && process.env && process.env.SEED_COUNT) || '10000', 10);

const BATCH = 1000;
const START_AT = 1700000000000; // fixed origin so savedAt is identical across runs

db = db.getSiblingDB('almachess');
db.games.deleteMany({ gameId: { $regex: '^seed-' } });

const startFen = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
const movesPool = ['e2e4', 'e7e5', 'g1f3', 'b8c6', 'f1c4', 'g8f6'];

function pad(n, w) { return String(n).padStart(w, '0'); }

let inserted = 0;
const t0 = Date.now();
while (inserted < COUNT) {
  const batch = [];
  const upper = Math.min(inserted + BATCH, COUNT);
  for (let i = inserted; i < upper; i++) {
    batch.push({
      gameId:     'seed-' + pad(i, 5),
      currentFen: startFen,
      initialFen: startFen,
      pgn:        '[Event "AlmaChess seed"]\n\n*',
      moves:      movesPool.slice(0, (i % movesPool.length) + 1),
      status:     'White to move',
      // pseudo-random but reproducible: deterministic function of i
      savedAt:    NumberLong(START_AT + ((i * 2654435761) % 2_592_000_000))
    });
  }
  db.games.insertMany(batch, { ordered: false });
  inserted = upper;
}

print('seeded ' + inserted + ' docs in ' + (Date.now() - t0) + ' ms');
print('total docs: ' + db.games.countDocuments({}));
print('indexes:');
printjson(db.games.getIndexes());
