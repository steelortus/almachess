// k6 load test for the AlmaChess NotationService.
//
// Target endpoint: POST /notation/fen/validate — synchronous CPU-bound work
// (FenParser.parse). Mixes a few realistic FEN positions per VU iteration.
//
// Reproducible run (from repo root):
//   docker run --rm -i --network host \
//     -v "${PWD}/perf:/perf" -e BASE_URL=http://host.docker.internal:8084 \
//     grafana/k6 run --summary-export=/perf/results/k6-${TAG}.json \
//     /perf/k6/notation_validate.js
//
// Tunables (env):
//   BASE_URL           — default http://localhost:8084 (NotationService)
//   K6_VUS             — default 30
//   K6_DURATION        — default 30s

import http from 'k6/http';
import { check, fail } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8084';
const VUS = parseInt(__ENV.K6_VUS || '30', 10);
const DURATION = __ENV.K6_DURATION || '30s';

const validateLatency = new Trend('fen_validate_latency', true);
const validateFailureRate = new Rate('fen_validate_failures');

// Fixed seed of FENs so results are deterministic across runs.
const FENS = [
  'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
  'r1bq1rk1/pp2bppp/2n1pn2/2pp4/3P4/2PBPN2/PP3PPP/RNBQ1RK1 w - - 2 7',
  'r2qkb1r/pp2nppp/3p4/2pNN1B1/2BnP3/3P4/PPP2PPP/R2bK2R w KQkq - 1 0',
  '8/8/8/8/8/8/8/8 w - - 0 1',
  'rnbqkb1r/ppp1pppp/5n2/3p4/3P4/5N2/PPP1PPPP/RNBQKB1R w KQkq - 0 3'
];

export const options = {
  scenarios: {
    constant_load: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
      gracefulStop: '5s'
    }
  },
  // Reproducibility hard gates. CI / make-target should fail when these break.
  thresholds: {
    http_req_failed:                   ['rate<0.01'],   // < 1% errors
    'http_req_duration{endpoint:fen}': ['p(95)<200'],   // p95 latency < 200ms
    fen_validate_failures:             ['rate<0.01'],
    fen_validate_latency:              ['p(95)<200', 'p(99)<500']
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max']
};

export function setup() {
  // Fail fast if the service is not reachable — better than a failed run later.
  const r = http.get(`${BASE_URL}/health`);
  if (r.status !== 200) {
    fail(`NotationService not reachable at ${BASE_URL}/health (status ${r.status})`);
  }
  return { base: BASE_URL };
}

export default function (data) {
  const fen = FENS[(__ITER + __VU) % FENS.length];
  const payload = JSON.stringify({ fen });
  const params = {
    headers: { 'Content-Type': 'application/json' },
    tags:    { endpoint: 'fen' }
  };

  const res = http.post(`${data.base}/notation/fen/validate`, payload, params);
  validateLatency.add(res.timings.duration);

  const ok = check(res, {
    'status is 200': (r) => r.status === 200,
    'body is valid:true': (r) => r.json('valid') === true
  });
  validateFailureRate.add(!ok);
}
