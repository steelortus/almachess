// Artillery helper: pickt einen zufälligen seed-ID und Endpoint pro Iteration.
//
// Gleiches Mix-Profil wie perf/k6/persistence_stress.js:
//   80 % GET /games/{id}, 15 % GET /games, 5 % POST /games/{id}

const SEED_TOTAL = 10000;

function pad(n, w) { return String(n).padStart(w, '0'); }

module.exports = {
  pickEndpoint(context, events, done) {
    const dice = Math.random();
    context.vars.route = dice < 0.80 ? 'get' : (dice < 0.95 ? 'list' : 'post');
    context.vars.seedId = 'seed-' + pad(Math.floor(Math.random() * SEED_TOTAL), 5);
    return done();
  }
};
