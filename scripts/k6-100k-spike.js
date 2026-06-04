import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    spike_users: {
      executor: 'ramping-vus',
      stages: [
        { duration: __ENV.RAMP_UP || '2m', target: Number(__ENV.TARGET_VUS || 100000) },
        { duration: __ENV.HOLD || '1m', target: Number(__ENV.TARGET_VUS || 100000) },
        { duration: __ENV.RAMP_DOWN || '30s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.5'],
    http_req_duration: ['p(95)<1000'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:80';
const ACTIVITY_ID = Number(__ENV.ACTIVITY_ID || 1);
const SKU_ID = Number(__ENV.SKU_ID || 1001);
const USER_BASE = Number(__ENV.USER_BASE || 90000000);

export function setup() {
  const stock = Number(__ENV.STOCK || 1000);
  http.post(`${BASE_URL}/api/seckill/init?activityId=${ACTIVITY_ID}&skuId=${SKU_ID}&stock=${stock}`);
  http.post(`${BASE_URL}/api/seckill/warmup?activityId=${ACTIVITY_ID}&skuId=${SKU_ID}`);
}

export default function () {
  const userId = USER_BASE + (__VU * 1000000) + __ITER;
  const tokenRes = http.get(`${BASE_URL}/api/seckill/token?activityId=${ACTIVITY_ID}&userId=${userId}&skuId=${SKU_ID}`);

  let token = '';
  try {
    const body = JSON.parse(tokenRes.body);
    token = body.data && body.data.token ? body.data.token : '';
  } catch (e) {
    token = '';
  }

  if (token) {
    const payload = JSON.stringify({ activityId: ACTIVITY_ID, userId, skuId: SKU_ID, token });
    const orderRes = http.post(`${BASE_URL}/api/seckill/order`, payload, {
      headers: { 'Content-Type': 'application/json' },
    });
    check(orderRes, {
      'order response parsed': (r) => {
        try {
          JSON.parse(r.body);
          return true;
        } catch (e) {
          return false;
        }
      },
    });
  } else {
    check(tokenRes, {
      'token rejected or parsed': (r) => r.status === 200,
    });
  }

  sleep(Number(__ENV.SLEEP || 1));
}
