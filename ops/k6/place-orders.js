// place-orders (M7-1, ticket #35): customers place orders at a rising arrival rate.
//   full:  5 to 50 orders/s over 10 min.
//   short: 2 to 10 orders/s over 1 min (the default).
// An arrival-rate executor starts iterations on schedule even when responses slow down, so a slow service shows up as
// higher latency and dropped iterations, not as a quietly lower request rate.
//
// Orders go to the open merchants the demo simulator seeded, so its merchant and courier bots accept, prepare and
// deliver them, and dispatch runs under the same load (Grafana shows dispatch p95). setup() creates the customers.
import http from 'k6/http';
import { check, fail } from 'k6';
import {
  LOAD,
  ORDER_API,
  SUMMARY_TREND_STATS,
  byProfile,
  openMerchantsWithMenus,
  pick,
  thresholds,
} from './lib/common.js';

const CUSTOMERS = 20;
// Downtown Austin, the simulator's demo city (ticket #33). Customers land within about 3 km of it.
const AUSTIN = { lat: 30.2672, lng: -97.7431 };

export const options = {
  scenarios: {
    place_orders: {
      executor: 'ramping-arrival-rate',
      startRate: byProfile(2, 5),
      timeUnit: '1s',
      preAllocatedVUs: byProfile(5, 20),
      maxVUs: byProfile(30, 100),
      stages: [{ target: byProfile(10, 50), duration: byProfile('1m', '10m') }],
    },
  },
  thresholds: thresholds(['POST /orders']),
  summaryTrendStats: SUMMARY_TREND_STATS,
};

export function setup() {
  const merchants = openMerchantsWithMenus();
  const customers = [];
  for (let i = 1; i <= CUSTOMERS; i++) {
    const res = http.post(
      `${ORDER_API}/customers`,
      JSON.stringify({
        name: `Load customer ${i}`,
        address: `${100 + i} Congress Ave, Austin, TX`,
        lat: AUSTIN.lat + (Math.random() - 0.5) * 0.05,
        lng: AUSTIN.lng + (Math.random() - 0.5) * 0.05,
      }),
      { headers: { 'Content-Type': 'application/json' } },
    );
    if (res.status !== 201) {
      fail(`POST /customers returned ${res.status}: ${res.body}`);
    }
    customers.push(res.json().id);
  }
  return { merchants, customers, run: Date.now() };
}

export default function (data) {
  const merchant = pick(data.merchants);
  // One to three distinct items, one to two of each.
  const count = 1 + Math.floor(Math.random() * Math.min(3, merchant.items.length));
  const items = [...merchant.items].sort(() => Math.random() - 0.5).slice(0, count);
  const body = {
    customerId: pick(data.customers),
    merchantId: merchant.id,
    lines: items.map((menuItemId) => ({ menuItemId, quantity: 1 + Math.floor(Math.random() * 2) })),
  };
  const res = http.post(`${ORDER_API}/orders`, JSON.stringify(body), {
    headers: {
      'Content-Type': 'application/json',
      // Unique per run, VU and iteration, so every request places a new order instead of replaying one.
      'Idempotency-Key': `k6-${data.run}-${__VU}-${__ITER}`,
    },
    tags: { ...LOAD, name: 'POST /orders' },
  });
  check(res, { 'order placed (201)': (r) => r.status === 201 });
}
