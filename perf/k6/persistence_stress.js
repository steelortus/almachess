// k6 stress test for the AlmaChess persistence API.
//
// Goal: find the **knee point** — at what VU count does latency explode and /
// or the error rate climb away from zero?
//
// Method: five constant-vus scenarios scheduled back-to-back, each 30 s long,
// each tagged with its own `stage` label so the summary breaks down per level.
// Loose thresholds so the run does NOT abort early — we want to see the system
// degrade.
//
// Hits the same mix of endpoints as persistence_mix.js but skewed to the GET
// path because that one carries the controller `sharedLock` cost on top of the
// Mongo lookup, so it saturates earliest.
//
// Reproducible run:
//   docker run --rm -v "${PWD}/perf:/perf" \
//     -e BASE_URL=http://host.docker.internal:8083 \
//     grafana/k6 run --summary-export=/perf/results/k6-stress.json \
//     /perf/k6/persistence_stress.js

import http from 'k6/http';
import { check, fail } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const BASE_URL   = __ENV.BASE_URL || 'http://localhost:8083';
const SEED_TOTAL = 10000;

// Per-stage trends so the summary report shows latency at each VU level.
const reqLatency = new Trend('stress_req_latency', true);
const errorRate  = new Rate('stress_errors');

function pad(n, w) { return String(n).padStart(w, '0'); }
function randomSeedId() { return 'seed-' + pad(Math.floor(Math.random() * SEED_TOTAL), 5); }

// Five steps. Each lasts 30 s. startTime stitches them in sequence so the run
// finishes in ~2:30, plus a leading 30 s warmup at low VU count.
export const options = {
  scenarios: {
    warm: { executor: 'constant-vus', startTime:   '0s', duration: '30s', vus:  10, tags: { stage: 'WARM' } },
    s030: { executor: 'constant-vus', startTime:  '30s', duration: '30s', vus:  30, tags: { stage: 'L030' } },
    s075: { executor: 'constant-vus', startTime:  '60s', duration: '30s', vus:  75, tags: { stage: 'L075' } },
    s150: { executor: 'constant-vus', startTime:  '90s', duration: '30s', vus: 150, tags: { stage: 'L150' } },
    s300: { executor: 'constant-vus', startTime: '120s', duration: '30s', vus: 300, tags: { stage: 'L300' } },
    s500: { executor: 'constant-vus', startTime: '150s', duration: '30s', vus: 500, tags: { stage: 'L500' } },
  },
  // Loose thresholds — this run is allowed to fail, we want to *see* the fail.
  // Each line acts as a per-stage breakdown in the summary.
  thresholds: {
    'http_req_failed':                                     ['rate<0.95'],
    'http_req_duration{stage:L030}':                       ['p(95)>=0'],
    'http_req_duration{stage:L075}':                       ['p(95)>=0'],
    'http_req_duration{stage:L150}':                       ['p(95)>=0'],
    'http_req_duration{stage:L300}':                       ['p(95)>=0'],
    'http_req_duration{stage:L500}':                       ['p(95)>=0'],
    'http_req_failed{stage:L030}':                         ['rate>=0'],
    'http_req_failed{stage:L075}':                         ['rate>=0'],
    'http_req_failed{stage:L150}':                         ['rate>=0'],
    'http_req_failed{stage:L300}':                         ['rate>=0'],
    'http_req_failed{stage:L500}':                         ['rate>=0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  // Don't let HTTP errors blow up the run; let k6 record them as failures
  // and keep going so we can chart degradation.
  noConnectionReuse: false,
  insecureSkipTLSVerify: true
};

export function setup() {
  const r = http.get(`${BASE_URL}/api/persistence/status`);
  if (r.status !== 200) fail(`persistence status not reachable (${r.status})`);
  return { base: BASE_URL };
}

export default function (data) {
  // 80 % GET-by-id (lock-heavy path), 15 % LIST, 5 % POST upsert.
  // Skipping DELETE here so the dataset stays stable across stages.
  const dice = Math.random();
  let res, label;

  if (dice < 0.80) {
    label = 'get';
    res = http.get(`${data.base}/api/persistence/games/${randomSeedId()}`,
      { tags: { endpoint: 'get_by_id' }, timeout: '10s' });
  } else if (dice < 0.95) {
    label = 'list';
    res = http.get(`${data.base}/api/persistence/games`,
      { tags: { endpoint: 'list' }, timeout: '10s' });
  } else {
    label = 'post';
    res = http.post(`${data.base}/api/persistence/games/${randomSeedId()}`, null,
      { tags: { endpoint: 'post_upsert' }, timeout: '10s' });
  }

  reqLatency.add(res.timings.duration, { kind: label });
  const ok = check(res, { 'status 2xx': (r) => r.status >= 200 && r.status < 300 });
  errorRate.add(!ok);
}
