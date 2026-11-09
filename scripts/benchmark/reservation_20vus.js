// Isolated-server only k6 scenario. It refuses the known shared development ports.
// Required: ISOLATED_RUN=1, BASE_URL, ACCESS_TOKENS_JSON (20 customer JWTs), SLOT_START_AT.
import http from 'k6/http';
import { check, fail } from 'k6';

const base = __ENV.BASE_URL || '';
const tokens = JSON.parse(__ENV.ACCESS_TOKENS_JSON || '[]');
if (__ENV.ISOLATED_RUN !== '1') fail('Set ISOLATED_RUN=1 after starting a separate server.');
if (!base || /:(18081|9001|5175)(\/|$)/.test(base)) fail('Shared development ports are prohibited.');
if (tokens.length !== 20) fail('ACCESS_TOKENS_JSON must contain exactly 20 isolated-fixture customer JWTs.');
if (!__ENV.SLOT_START_AT) fail('SLOT_START_AT is required.');

export const options = { vus: 20, iterations: 20, discardResponseBodies: false, thresholds: { http_req_failed: ['rate<1'] } };

export default function () {
  const n = __VU - 1;
  const response = http.post(`${base}/api/v1/bff/visit-reservations`, JSON.stringify({
    requestId: `k6-isolated-${n}`, caseId: 7000 + n, branchId: 1, startAt: __ENV.SLOT_START_AT,
  }), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${tokens[n]}` }, tags: { expected: 'capacity-conflict-or-success' } });
  // Domain rejections (409) are expected capacity contention, unlike 5xx server errors.
  check(response, { 'success or business rejection': r => r.status === 200 || r.status === 201 || r.status === 409, 'no server error': r => r.status < 500 });
}
