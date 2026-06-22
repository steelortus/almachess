// Idempotent index creation for the AlmaChess persistence collection.
//
// Run: docker exec almachess-mongo mongosh --quiet --file /tmp/indexes.js

db = db.getSiblingDB('almachess');

// Point lookups (load / save upsert / delete) all filter by gameId.
db.games.createIndex(
  { gameId: 1 },
  { unique: true, name: 'gameId_unique' }
);

// Listing sorts by savedAt desc and returns gameId + savedAt — a compound
// index on the same fields lets the server skip the in-memory SORT and serve
// a covered scan when projection drops _id.
db.games.createIndex(
  { savedAt: -1, gameId: 1 },
  { name: 'savedAt_desc_gameId_asc' }
);

print('indexes:');
printjson(db.games.getIndexes());
