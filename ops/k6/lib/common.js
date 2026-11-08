// Shared settings for the k6 scripts (M7-1, ticket #35). Every script reads the same env vars:
//   LOAD_PROFILE  short (default) or full. short is a ~1 min sanity run at low rates, sized for a laptop with little
//                 free memory; full is the shape ticket #35 fixed, for the M7-2 baseline and the M7-3/4 comparisons.
//   MERCHANT_URL  merchant-service base URL (default http://merchant:8081, the Compose service name).
//   ORDER_URL     order-service base URL (default http://order:8082).
import http from 'k6/http';
import { fail } from 'k6';

export const PROFILE = __ENV.LOAD_PROFILE || 'short';
if (PROFILE !== 'short' && PROFILE !== 'full') {
  throw new Error(`LOAD_PROFILE must be short or full, not ${PROFILE}`);
}

// Every endpoint sits under /api/v1 (ticket #8).
export const MERCHANT_API = `${__ENV.MERCHANT_URL || 'http://merchant:8081'}/api/v1`;
export const ORDER_API = `${__ENV.ORDER_URL || 'http://order:8082'}/api/v1`;

// Only the measured requests carry this tag. setup() requests don't, so the probing and seeding it does never counts
// against the thresholds.
export const LOAD = { kind: 'load' };

/** Picks the short or the full value for the current profile. */
export function byProfile(short, full) {
  return PROFILE === 'full' ? full : short;
}

/**
 * The ticket #35 thresholds (p95 under 300 ms, errors under 1%) on the measured requests, plus one p95 line per
 * endpoint name so the end-of-test summary shows each endpoint's latency on its own (M7-2 records them).
 */
export function thresholds(endpointNames) {
  const result = {
    'http_req_duration{kind:load}': ['p(95)<300'],
    'http_req_failed{kind:load}': ['rate<0.01'],
  };
  for (const name of endpointNames) {
    result[`http_req_duration{kind:load,name:${name}}`] = ['p(95)<300'];
  }
  return result;
}

// p50 is "med". M7-2 records p50, p95 and p99 per endpoint.
export const SUMMARY_TREND_STATS = ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'];

/** GET that must return 200; used by setup(), where a failure means the stack isn't ready, not a slow request. */
export function getJson(url) {
  const res = http.get(url);
  if (res.status !== 200) {
    fail(`GET ${url} returned ${res.status}: ${res.body}`);
  }
  return res.json();
}

/** The open merchants and their available menu items. Fails if there are none: seed the demo first. */
export function openMerchantsWithMenus() {
  const page = getJson(`${MERCHANT_API}/merchants?size=100`);
  const merchants = [];
  for (const m of page.content) {
    if (!m.open) {
      continue;
    }
    const items = getJson(`${MERCHANT_API}/merchants/${m.id}/menu`)
      .filter((i) => i.available)
      .map((i) => i.id);
    if (items.length > 0) {
      merchants.push({ id: m.id, items });
    }
  }
  if (merchants.length === 0) {
    fail('No open merchant with an available menu item. Start the demo profile so the simulator seeds first.');
  }
  return merchants;
}

export function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}
