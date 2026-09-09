import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { randomString } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// A realistic mix, not a uniform hammer. Retries are the normal case in payments:
// clients time out, mobile networks drop, and PSPs replay aggressively. If the
// idempotency path is only exercised at 1% of traffic, its cost never shows up in
// the numbers you quote.
const DUPLICATE_RATE = Number(__ENV.DUPLICATE_RATE || 0.15);   // same key, same body -> 200 replay
const CONFLICT_RATE = Number(__ENV.CONFLICT_RATE || 0.02);     // same key, different body -> 409
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const created = new Counter('authorizations_created');
const replayed = new Counter('authorizations_replayed');
const conflicted = new Counter('authorizations_conflicted');
const declined = new Counter('authorizations_declined');
const unexpected = new Counter('unexpected_responses');

const createLatency = new Trend('latency_create', true);
const replayLatency = new Trend('latency_replay', true);
const correctness = new Rate('correct_status');

export const options = {
  // p99 is the number that matters for a payment API and k6 does not report it by
  // default, so ask for it explicitly.
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    authorizations: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: __ENV.RAMP || '30s', target: Number(__ENV.VUS || 50) },
        { duration: __ENV.DURATION || '2m', target: Number(__ENV.VUS || 50) },
        { duration: '15s', target: 0 },
      ],
      gracefulRampDown: '15s',
    },
  },
  thresholds: {
    // A declined authorization is a correct response, so http_req_failed is not a
    // useful signal here. Correctness is asserted per response instead.
    correct_status: ['rate>0.99'],
    'latency_create': ['p(95)<250', 'p(99)<500'],
    'latency_replay': ['p(95)<100'],
    unexpected_responses: ['count<1'],
  },
};

// Keys that have already completed, so a later iteration can replay one. Bounded:
// an unbounded array in a long soak is a memory leak in the load generator, which
// is a fun way to blame the service for your own p99.
const completedKeys = [];
const MAX_REMEMBERED_KEYS = 5000;

function remember(key, body) {
  if (completedKeys.length >= MAX_REMEMBERED_KEYS) {
    completedKeys[Math.floor(Math.random() * completedKeys.length)] = { key, body };
  } else {
    completedKeys.push({ key, body });
  }
}

function newRequest() {
  return {
    // A bounded card pool on purpose: with a unique card per request the velocity
    // rules would never fire and the Redis path would be measured permanently cold.
    // Twenty thousand cards is wide enough that declines stay a minority and narrow
    // enough that the sorted sets actually get read.
    cardToken: `tok_${Math.floor(Math.random() * 20000)}`,
    amountMinor: Math.floor(Math.random() * 500000) + 100,
    currency: 'INR',
    merchantId: `mrc_${Math.floor(Math.random() * 50)}`,
    countryCode: 'IN',
  };
}

function post(key, body) {
  return http.post(`${BASE_URL}/v1/authorizations`, JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
    tags: { name: 'POST /v1/authorizations' },
  });
}

export default function () {
  const roll = Math.random();

  if (roll < DUPLICATE_RATE && completedKeys.length > 0) {
    // A client retrying something that already succeeded.
    const previous = completedKeys[Math.floor(Math.random() * completedKeys.length)];
    const response = post(previous.key, previous.body);
    replayLatency.add(response.timings.duration);

    const ok = response.status === 200 || response.status === 409;
    correctness.add(ok);
    if (response.status === 200) replayed.add(1);
    else if (response.status === 409) conflicted.add(1);
    else unexpected.add(1);

    check(response, { 'replay returns 200 or 409': () => ok });
    return;
  }

  if (roll < DUPLICATE_RATE + CONFLICT_RATE && completedKeys.length > 0) {
    // A client reusing a key for a different payment. Must always be refused.
    const previous = completedKeys[Math.floor(Math.random() * completedKeys.length)];
    const response = post(previous.key, { ...previous.body, amountMinor: previous.body.amountMinor + 1 });

    const ok = response.status === 409;
    correctness.add(ok);
    if (ok) conflicted.add(1);
    else unexpected.add(1);

    check(response, { 'key reuse with a different body is 409': () => ok });
    return;
  }

  const key = `k6-${__VU}-${__ITER}-${randomString(8)}`;
  const body = newRequest();
  const response = post(key, body);
  createLatency.add(response.timings.duration);

  const ok = response.status === 201;
  correctness.add(ok);
  if (ok) {
    created.add(1);
    remember(key, body);
    const decision = response.json('status');
    if (decision === 'DECLINED') declined.add(1);
  } else {
    unexpected.add(1);
  }

  check(response, { 'new authorization returns 201': () => ok });
}

export function handleSummary(data) {
  const metric = (name, stat) => {
    const m = data.metrics[name];
    return m && m.values[stat] !== undefined ? m.values[stat].toFixed(1) : 'n/a';
  };
  const count = (name) => {
    const m = data.metrics[name];
    return m && m.values.count !== undefined ? m.values.count : 0;
  };

  const table = [
    '',
    'POST /v1/authorizations',
    '',
    '| operation | p50 (ms) | p95 (ms) | p99 (ms) |',
    '| --- | --- | --- | --- |',
    `| create  | ${metric('latency_create', 'med')} | ${metric('latency_create', 'p(95)')} | ${metric('latency_create', 'p(99)')} |`,
    `| replay  | ${metric('latency_replay', 'med')} | ${metric('latency_replay', 'p(95)')} | ${metric('latency_replay', 'p(99)')} |`,
    '',
    `created:    ${count('authorizations_created')}`,
    `replayed:   ${count('authorizations_replayed')}`,
    `conflicted: ${count('authorizations_conflicted')}`,
    `declined:   ${count('authorizations_declined')}`,
    `unexpected: ${count('unexpected_responses')}`,
    '',
  ].join('\n');

  return {
    stdout: table,
    // Path inside the k6 container; the Makefile mounts load-test/ at /scripts.
    '/scripts/results/summary.json': JSON.stringify(data, null, 2),
  };
}
