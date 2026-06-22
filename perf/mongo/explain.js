// Capture executionStats for the two query shapes used by MongoGameRepository.
//
// Run: docker exec almachess-mongo mongosh --quiet --file /tmp/explain.js

db = db.getSiblingDB('almachess');

const out = {
  indexes: db.games.getIndexes(),
  totalDocs: db.games.countDocuments({})
};

// 1) Point lookup by gameId — used by load() / save() upsert / delete().
out.findByGameId = db.games
  .find({ gameId: 'seed-04242' })
  .explain('executionStats');

// 2) List with sort by savedAt desc — used by list().
//    Same shape as MongoGameRepository.list(): projection includes _id by default.
out.listSortedDesc = db.games
  .find({}, { gameId: 1, savedAt: 1 })
  .sort({ savedAt: -1 })
  .limit(50)
  .explain('executionStats');

// 3) Same list with _id:0 — proves whether dropping _id enables a covered index scan
//    once an index on (savedAt, gameId) exists.
out.listSortedDescNoId = db.games
  .find({}, { gameId: 1, savedAt: 1, _id: 0 })
  .sort({ savedAt: -1 })
  .limit(50)
  .explain('executionStats');

print(JSON.stringify(out, null, 2));
