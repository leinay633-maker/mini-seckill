import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    order_pressure: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 1000),
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: Number(__ENV.VUS || 1000),
      maxVUs: Number(__ENV.MAX_VUS || 5000),
    },
  },
  thresholds: {
    system_error_rate: [`rate<${__ENV.SYSTEM_ERROR_THRESHOLD || 0.01}`],
  },
  discardResponseBodies: false,
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ACTIVITY_ID = Number(__ENV.ACTIVITY_ID || 1);
const SKU_ID = Number(__ENV.SKU_ID || 1001);
const USER_BASE = Number(__ENV.USER_BASE || 10000000);
const MODE = __ENV.MODE || 'unique';
const DUPLICATE_USERS = Number(__ENV.DUPLICATE_USERS || 1000);
const TIMEOUT = __ENV.HTTP_TIMEOUT || '5s';
const USE_HIDDEN_PATH = (__ENV.USE_HIDDEN_PATH || 'false').toLowerCase() === 'true';
const USER_JWT = __ENV.USER_JWT || '';

const tokenCreated = new Counter('token_created');
const tokenFailed = new Counter('token_failed');
const queued = new Counter('order_queued');
const soldOut = new Counter('order_sold_out');
const duplicate = new Counter('order_duplicate');
const rateLimited = new Counter('order_rate_limited');
const businessOther = new Counter('order_business_other');
const systemError = new Counter('order_system_error');
const systemErrorRate = new Rate('system_error_rate');
const queueResponseDuration = new Trend('queue_response_duration', true);

function userIdForIteration(iteration) {
  if (MODE === 'duplicate') {
    return USER_BASE + (iteration % DUPLICATE_USERS);
  }
  return USER_BASE + iteration;
}

function classifyResponse(res) {
  if (!res || res.status === 0 || res.status >= 500) {
    systemError.add(1);
    systemErrorRate.add(true);
    return;
  }

  let body;
  try {
    body = JSON.parse(res.body);
  } catch (e) {
    systemErrorRate.add(false);
    businessOther.add(1);
    return;
  }

  if (body.code === 0) {
    systemErrorRate.add(false);
    queued.add(1);
    queueResponseDuration.add(res.timings.duration);
  } else if (body.code === 1002) {
    systemErrorRate.add(false);
    soldOut.add(1);
  } else if (body.code === 409) {
    systemErrorRate.add(false);
    duplicate.add(1);
  } else if (body.code === 429) {
    systemErrorRate.add(false);
    rateLimited.add(1);
  } else if (body.code >= 500) {
    systemError.add(1);
    systemErrorRate.add(true);
  } else {
    systemErrorRate.add(false);
    businessOther.add(1);
  }
}

function classifyAdmissionFailure(res) {
  if (!res || res.status === 0 || res.status >= 500) {
    systemError.add(1);
    systemErrorRate.add(true);
    return;
  }

  let body;
  try {
    body = JSON.parse(res.body);
  } catch (e) {
    tokenFailed.add(1);
    businessOther.add(1);
    systemErrorRate.add(false);
    return;
  }

  if (body.code === 1002) {
    soldOut.add(1);
    systemErrorRate.add(false);
  } else if (body.code === 429) {
    rateLimited.add(1);
    systemErrorRate.add(false);
  } else if (body.code >= 500) {
    systemError.add(1);
    systemErrorRate.add(true);
  } else {
    businessOther.add(1);
    systemErrorRate.add(false);
  }
}

function authHeaders() {
  const headers = { 'Content-Type': 'application/json' };
  if (USER_JWT) {
    headers.Authorization = `Bearer ${USER_JWT}`;
  }
  return headers;
}

function createOrderToken(userId) {
  const res = http.get(`${BASE_URL}/api/seckill/token?activityId=${ACTIVITY_ID}&userId=${userId}&skuId=${SKU_ID}`, {
    headers: authHeaders(),
    timeout: TIMEOUT,
    tags: { endpoint: 'token', mode: MODE },
  });
  if (!res || res.status !== 200) {
    tokenFailed.add(1);
    classifyAdmissionFailure(res);
    return null;
  }

  let body;
  try {
    body = JSON.parse(res.body);
  } catch (e) {
    tokenFailed.add(1);
    businessOther.add(1);
    systemErrorRate.add(false);
    return null;
  }

  if (body.code !== 0 || !body.data || !body.data.token) {
    tokenFailed.add(1);
    classifyAdmissionFailure(res);
    return null;
  }

  tokenCreated.add(1);
  return body.data;
}

export default function () {
  const iteration = exec.scenario.iterationInTest;
  const userId = userIdForIteration(iteration);
  const tokenData = createOrderToken(userId);
  if (!tokenData) {
    return;
  }
  const payload = JSON.stringify({
    activityId: ACTIVITY_ID,
    userId,
    skuId: SKU_ID,
    token: tokenData.token,
  });
  const orderUrl = USE_HIDDEN_PATH && tokenData.orderPath
    ? `${BASE_URL}/api/seckill/order/${tokenData.orderPath}`
    : `${BASE_URL}/api/seckill/order`;
  const res = http.post(orderUrl, payload, {
    headers: authHeaders(),
    timeout: TIMEOUT,
    tags: { endpoint: 'order', mode: MODE },
  });
  classifyResponse(res);
}

export function handleSummary(data) {
  const jsonPath = __ENV.SUMMARY_JSON || `benchmark/results/k6-${MODE}-summary.json`;
  return {
    stdout: textSummary(data),
    [jsonPath]: JSON.stringify(data, null, 2),
  };
}

function metricLine(data, name, keys) {
  const metric = data.metrics[name];
  if (!metric) {
    return `${name}: n/a`;
  }
  const values = keys
    .filter((key) => metric.values[key] !== undefined)
    .map((key) => `${key}=${metric.values[key]}`)
    .join(', ');
  return `${name}: ${values}`;
}

function textSummary(data) {
  return [
    `mode=${MODE}`,
    `rate=${__ENV.RATE || 1000}/s duration=${__ENV.DURATION || '30s'}`,
    metricLine(data, 'iterations', ['count', 'rate']),
    metricLine(data, 'http_reqs', ['count', 'rate']),
    metricLine(data, 'http_req_duration', ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max']),
    metricLine(data, 'token_created', ['count', 'rate']),
    metricLine(data, 'token_failed', ['count', 'rate']),
    metricLine(data, 'order_queued', ['count', 'rate']),
    metricLine(data, 'order_sold_out', ['count', 'rate']),
    metricLine(data, 'order_duplicate', ['count', 'rate']),
    metricLine(data, 'order_rate_limited', ['count', 'rate']),
    metricLine(data, 'order_business_other', ['count', 'rate']),
    metricLine(data, 'order_system_error', ['count', 'rate']),
    metricLine(data, 'system_error_rate', ['rate']),
    metricLine(data, 'queue_response_duration', ['avg', 'med', 'p(95)', 'p(99)', 'max']),
    metricLine(data, 'dropped_iterations', ['count', 'rate']),
    '',
  ].join('\n');
}
