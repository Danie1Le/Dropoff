// order-history (M7-1, ticket #35): customers page through their order history, often deep into it.
//   full:  50 req/s for 5 min.
//   short: 10 req/s for 1 min (the default).
// Reads the customers ops/perf/seed-history.sql adds (ids from 1000001, 2,000 orders each, so 100 pages of 20), so run
// that first. Half the requests read page 0 and half read a page in the older half of the history. A deep page makes
// offset paging read and throw away every row before it, which is what M7-3/4 may fix with keyset paging.
import http from 'k6/http';
import { check, fail } from 'k6';
import { LOAD, ORDER_API, SUMMARY_TREND_STATS, byProfile, thresholds } from './lib/common.js';

// Must match ops/perf/seed-history.sql.
const FIRST_CUSTOMER_ID = 1000001;
const CUSTOMERS = 100;
const PAGE_SIZE = 20;

export const options = {
  scenarios: {
    order_history: {
      executor: 'constant-arrival-rate',
      rate: byProfile(10, 50),
      timeUnit: '1s',
      duration: byProfile('1m', '5m'),
      preAllocatedVUs: byProfile(5, 20),
      maxVUs: byProfile(30, 100),
    },
  },
  thresholds: thresholds(['GET /customers/{id}/orders first page', 'GET /customers/{id}/orders deep page']),
  summaryTrendStats: SUMMARY_TREND_STATS,
};

export function setup() {
  const res = http.get(`${ORDER_API}/customers/${FIRST_CUSTOMER_ID}/orders?size=1`);
  if (res.status !== 200) {
    fail(`Customer ${FIRST_CUSTOMER_ID} not found (${res.status}). Load ops/perf/seed-history.sql first.`);
  }
  // Every seeded customer has the same number of orders, so one customer's count gives the page count for all.
  const orders = res.json().page.totalElements;
  return { pages: Math.max(1, Math.ceil(orders / PAGE_SIZE)) };
}

export default function (data) {
  const customerId = FIRST_CUSTOMER_ID + Math.floor(Math.random() * CUSTOMERS);
  const deep = Math.random() < 0.5;
  // A deep page is one in the older half of the history: from the middle page to the last.
  const half = Math.floor(data.pages / 2);
  const page = deep ? half + Math.floor(Math.random() * (data.pages - half)) : 0;
  const name = deep ? 'GET /customers/{id}/orders deep page' : 'GET /customers/{id}/orders first page';
  const res = http.get(`${ORDER_API}/customers/${customerId}/orders?page=${page}&size=${PAGE_SIZE}`, {
    tags: { ...LOAD, name },
  });
  check(res, { 'history read (200)': (r) => r.status === 200 });
}
