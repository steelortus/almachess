// k6 mix scenario for the AlmaChess persistence API (Mongo backend).
//
// Endpoints tested:
//   GET    /api/persistence/games/{gameId}   ← point lookup by gameId
//   POST   /api/persistence/games/{gameId}   ← upsert (replaceOne)
//   GET    /api/persistence/games             ← full list, sorted by savedAt desc
//   DELETE /api/persistence/games/{gameId}   ← delete + re-POST to keep dataset stable
//
// Targets a Mongo collection seeded with seed-00000..seed-09999 (perf/mongo/seed.js).
//
// Reproducible run:
//   docker run --rm -v "${PWD}/perf:/perf" \
//     -e BASE_URL=http://host.docker.internal:8083 \
//     -e K6_VUS=20 -e K6_DURATION=45s \
//     grafana/k6 run --summary-export=/perf/results/k6-mongo-${TAG}.json \
//     /perf/k6/persistence_mix.js

import http from 'k6/http';
import { check, fail, group } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8083';
const VUS      = parseInt(__ENV.K6_VUS || '20', 10);
const DURATION = __ENV.K6_DURATION || '45s';

const SEED_TOTAL = 10000;

const getLatency    = new Trend('mongo_get_by_id_latency', true);
const postLatency   = new Trend('mongo_post_upsert_latency', true);
const listLatency   = new Trend('mongo_list_latency', true);
const deleteLatency = new Trend('mongo_delete_latency', true);
const errorRate     = new Rate('mongo_request_failures');

export const options = {
  scenarios: {
    mixed: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
      gracefulStop: '5s'
    }
  },
  // Hard gates. Tightened versus the baseline-only run so the optimized run
  // must demonstrate real improvement on the slow paths (LIST especially).
  thresholds: {
    'http_req_failed':                     ['rate<0.01'],
    'mongo_request_failures':              ['rate<0.01'],
    'mongo_get_by_id_latency':             ['p(95)<200', 'p(99)<500'],
    'mongo_post_upsert_latency':           ['p(95)<300', 'p(99)<800'],
    'mongo_list_latency':                  ['p(95)<800', 'p(99)<1500'],
    'mongo_delete_latency':                ['p(95)<300']
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max']
};

function pad(n, w) { return String(n).padStart(w, '0'); }

function randomSeedId() {
  return 'seed-' + pad(Math.floor(Math.random() * SEED_TOTAL), 5);
}

export function setup() {
  const r = http.get(`${BASE_URL}/api/persistence/status`);
  if (r.status !== 200) fail(`persistence status not reachable (${r.status})`);
  const status = r.json();
  if (!status.enabled || status.backend !== 'mongo') {
    fail(`expected enabled mongo backend, got ${JSON.stringify(status)}`);
  }
  return { base: BASE_URL };
}

export default function (data) {
  // Endpoint mix: 60% GET-by-id, 25% LIST, 10% POST upsert, 5% DELETE+restore.
  const dice = Math.random();

  if (dice < 0.60) {
    group('GET /games/{id}', () => {
      const id = randomSeedId();
      const res = http.get(`${data.base}/api/persistence/games/${id}`,
        { tags: { endpoint: 'get_by_id' } });
      getLatency.add(res.timings.duration);
      const ok = check(res, { 'GET 200': (r) => r.status === 200 });
      errorRate.add(!ok);
    });
  } else if (dice < 0.85) {
    group('GET /games (list)', () => {
      const res = http.get(`${data.base}/api/persistence/games`,
        { tags: { endpoint: 'list' } });
      listLatency.add(res.timings.duration);
      const ok = check(res, {
        'LIST 200':       (r) => r.status === 200,
        'LIST has games': (r) => Array.isArray(r.json('games')) && r.json('games').length > 0
      });
      errorRate.add(!ok);
    });
  } else if (dice < 0.95) {
    group('POST /games/{id}', () => {
      const id = randomSeedId();
      const res = http.post(`${data.base}/api/persistence/games/${id}`, null,
        { tags: { endpoint: 'post_upsert' } });
      postLatency.add(res.timings.duration);
      const ok = check(res, { 'POST 200': (r) => r.status === 200 });
      errorRate.add(!ok);
    });
  } else {
    group('DELETE /games/{id} + restore', () => {
      // Use a dedicated transient range so deletes don't shrink the seeded set.
      const txId = 'seed-tx-' + pad(__VU * 1000 + (__ITER % 1000), 5);
      // Ensure the doc exists, time the delete, then restore for next iteration.
      http.post(`${data.base}/api/persistence/games/${txId}`);
      const del = http.del(`${data.base}/api/persistence/games/${txId}`,
        null, { tags: { endpoint: 'delete' } });
      deleteLatency.add(del.timings.duration);
      const ok = check(del, { 'DELETE 200': (r) => r.status === 200 });
      errorRate.add(!ok);
    });
  }
}
