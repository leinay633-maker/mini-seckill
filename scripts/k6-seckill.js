import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    seckill: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 200),
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: Number(__ENV.VUS || 200),
      maxVUs: Number(__ENV.MAX_VUS || 500),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.2'],
    http_req_duration: ['p(95)<500'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ACTIVITY_ID = Number(__ENV.ACTIVITY_ID || 1);
const SKU_ID = Number(__ENV.SKU_ID || 1001);
const USER_BASE = Number(__ENV.USER_BASE || 100000);

export function setup() {
  const stock = Number(__ENV.STOCK || 100);
  http.post(`${BASE_URL}/api/seckill/init?activityId=${ACTIVITY_ID}&skuId=${SKU_ID}&stock=${stock}`);
  http.post(`${BASE_URL}/api/seckill/warmup?activityId=${ACTIVITY_ID}&skuId=${SKU_ID}`);
}

export default function () {
  const userId = USER_BASE + (__VU * 100000) + __ITER;
  const tokenRes = http.get(`${BASE_URL}/api/seckill/token?activityId=${ACTIVITY_ID}&userId=${userId}&skuId=${SKU_ID}`);
  let token = '';
  try {
    token = JSON.parse(tokenRes.body).data.token;
  } catch (e) {
    token = '';
  }
  const payload = JSON.stringify({
    activityId: ACTIVITY_ID,
    userId,
    skuId: SKU_ID,
    token,
  });
  const res = http.post(`${BASE_URL}/api/seckill/order`, payload, {
    headers: { 'Content-Type': 'application/json' },
  });

  check(res, {
    'http status is not 5xx': (r) => r.status < 500,
    'business response parsed': (r) => {
      try {
        JSON.parse(r.body);
        return true;
      } catch (e) {
        return false;
      }
    },
  });
  sleep(0.01);
}
