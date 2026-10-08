// k6 load test: concurrent token requests against several realms, each realm on its own database.
//
//   GRANT=password            resource-owner password grant (a full login: user lookup + password hash check)
//   GRANT=client_credentials  service-account token (no password hashing, DB + token signing only)
//
// One k6 scenario per realm runs in parallel with VUS_PER_REALM virtual users for DURATION.

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const KC_URL = __ENV.KC_URL || 'http://localhost:8080';
const REALMS = (__ENV.REALMS || 'alpha,beta,gamma').split(',').map((r) => r.trim()).filter(Boolean);
const VUS_PER_REALM = parseInt(__ENV.VUS_PER_REALM || '20', 10);
const DURATION = __ENV.DURATION || '60s';
const GRANT = __ENV.GRANT || 'password';
const USERS_PER_REALM = parseInt(__ENV.USERS_PER_REALM || '100', 10);

const scenarios = {};
for (const realm of REALMS) {
  scenarios[`login_${realm}`] = {
    executor: 'constant-vus',
    vus: VUS_PER_REALM,
    duration: DURATION,
    exec: 'login',
    env: { REALM: realm },
    gracefulStop: '15s',
  };
}

export const options = {
  scenarios,
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    checks: ['rate>0.99'],
  },
};

const okRate = {};
const latency = {};
const successes = {};
for (const realm of REALMS) {
  okRate[realm] = new Rate(`ok_${realm}`);
  latency[realm] = new Trend(`latency_${realm}`, true);
  successes[realm] = new Counter(`tokens_${realm}`);
}

export function login() {
  const realm = __ENV.REALM;
  const url = `${KC_URL}/realms/${realm}/protocol/openid-connect/token`;
  let body;
  if (GRANT === 'client_credentials') {
    body = { grant_type: 'client_credentials', client_id: 'loadtest-svc', client_secret: 'loadtest-secret' };
  } else {
    const n = 1 + Math.floor(Math.random() * USERS_PER_REALM);
    body = { grant_type: 'password', client_id: 'loadtest', username: `user-${n}`, password: 'password', scope: 'openid' };
  }
  const res = http.post(url, body, { tags: { realm } });
  const ok = check(res, {
    'status 200': (r) => r.status === 200,
    'has access_token': (r) => r.status === 200 && r.json('access_token') !== undefined,
  });
  okRate[realm].add(ok);
  latency[realm].add(res.timings.duration);
  if (ok) {
    successes[realm].add(1);
  }
}

export function handleSummary(data) {
  const seconds = data.state.testRunDurationMs / 1000;
  const rows = [];
  let total = 0;
  for (const realm of REALMS) {
    const count = (data.metrics[`tokens_${realm}`] || { values: { count: 0 } }).values.count;
    const lat = (data.metrics[`latency_${realm}`] || { values: {} }).values;
    const ok = (data.metrics[`ok_${realm}`] || { values: { rate: 0 } }).values.rate;
    total += count;
    rows.push({
      realm,
      tokens: count,
      perSecond: +(count / seconds).toFixed(1),
      successRate: +(ok * 100).toFixed(2),
      p50ms: round(lat['p(50)']),
      p95ms: round(lat['p(95)']),
      p99ms: round(lat['p(99)']),
      maxMs: round(lat.max),
    });
  }
  const http = data.metrics.http_req_duration ? data.metrics.http_req_duration.values : {};
  const summary = {
    grant: GRANT,
    realms: REALMS,
    vusPerRealm: VUS_PER_REALM,
    totalVus: VUS_PER_REALM * REALMS.length,
    durationSeconds: +seconds.toFixed(1),
    totalTokens: total,
    totalPerSecond: +(total / seconds).toFixed(1),
    overall: { p50ms: round(http['p(50)']), p95ms: round(http['p(95)']), p99ms: round(http['p(99)']) },
    perRealm: rows,
  };

  const lines = [];
  lines.push('');
  lines.push(`=== Keycloak realm-per-database load test (${GRANT}) ===`);
  lines.push(`realms: ${REALMS.join(', ')}  |  VUs: ${VUS_PER_REALM} per realm (${summary.totalVus} total)  |  duration: ${summary.durationSeconds}s`);
  lines.push('');
  lines.push(pad('realm', 10) + pad('tokens', 10) + pad('tokens/s', 10) + pad('ok %', 8) + pad('p50 ms', 9) + pad('p95 ms', 9) + pad('p99 ms', 9) + pad('max ms', 9));
  for (const r of rows) {
    lines.push(pad(r.realm, 10) + pad(r.tokens, 10) + pad(r.perSecond, 10) + pad(r.successRate, 8) + pad(r.p50ms, 9) + pad(r.p95ms, 9) + pad(r.p99ms, 9) + pad(r.maxMs, 9));
  }
  lines.push(pad('TOTAL', 10) + pad(total, 10) + pad(summary.totalPerSecond, 10) + pad('', 8) + pad(summary.overall.p50ms, 9) + pad(summary.overall.p95ms, 9) + pad(summary.overall.p99ms, 9));
  lines.push('');

  const file = `/results/summary-${GRANT}-${VUS_PER_REALM}x${REALMS.length}.json`;
  return {
    stdout: lines.join('\n'),
    [file]: JSON.stringify(summary, null, 2),
  };
}

function round(v) {
  return v === undefined || v === null ? null : +v.toFixed(1);
}

function pad(v, n) {
  const s = String(v === null || v === undefined ? '' : v);
  return s + ' '.repeat(Math.max(1, n - s.length));
}
