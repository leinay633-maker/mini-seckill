import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

const bases = (__ENV.BASE_URLS || 'http://127.0.0.1:18081,http://127.0.0.1:18082,http://127.0.0.1:18083').split(',');
const fixed = Number(__ENV.ITERATIONS || 0);
export const options = {
  scenarios: { admissions: fixed ? {
    executor: 'shared-iterations', vus: 32, iterations: fixed, maxDuration: '90s'
  } : {
    executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 200), timeUnit: '1s',
    duration: __ENV.DURATION || '75s', preAllocatedVUs: 64, maxVUs: 256
  } },
  thresholds: { coordination_system_errors: ['rate==0'] },
  summaryTrendStats: ['min', 'avg', 'med', 'p(95)', 'p(99)', 'max'],
};
const queued = new Counter('coordination_queued');
const soldOut = new Counter('coordination_sold_out');
const errors = new Rate('coordination_system_errors');
const dispatched = new Counter('coordination_requests');
const requestStarted = new Trend('coordination_request_started_ms');
export default function () {
  const n = exec.scenario.iterationInTest;
  const node = n % bases.length;
  const user = Number(__ENV.USER_BASE || 10000000) + n;
  // Admission security is disabled in this isolated correctness fixture. This is
  // not comparable with historical token+order capacity tests.
  requestStarted.add(Date.now());
  const res = http.post(`${bases[node]}/api/seckill/order`, JSON.stringify({ activityId: 1, skuId: 1001, userId: user }), {
    headers: { 'Content-Type': 'application/json' }, timeout: '5s', tags: { node: String(node) }
  });
  dispatched.add(1, { node: String(node) }); queued.add(0); soldOut.add(0);
  let body = null;
  try { body = res.json(); } catch (_) { }
  const accepted = res.status === 200 && body && body.code === 0;
  const exhausted = res.status === 200 && body && body.code === 1002;
  queued.add(accepted ? 1 : 0); soldOut.add(exhausted ? 1 : 0);
  errors.add(!accepted && !exhausted);
}
