// browse (M7-1, ticket #35): customers read the merchant list and menus at a constant rate.
//   full:  100 req/s for 5 min.
//   short: 20 req/s for 1 min (the default).
// Each iteration is one request: half list a page of merchants, half read one merchant's menu.
import http from 'k6/http';
import { check, fail } from 'k6';
import { LOAD, MERCHANT_API, SUMMARY_TREND_STATS, byProfile, getJson, pick, thresholds } from './lib/common.js';

const PAGE_SIZE = 20;

export const options = {
  scenarios: {
    browse: {
      executor: 'constant-arrival-rate',
      rate: byProfile(20, 100),
      timeUnit: '1s',
      duration: byProfile('1m', '5m'),
      preAllocatedVUs: byProfile(5, 20),
      maxVUs: byProfile(30, 100),
    },
  },
  thresholds: thresholds(['GET /merchants', 'GET /merchants/{id}/menu']),
  summaryTrendStats: SUMMARY_TREND_STATS,
};

export function setup() {
  const first = getJson(`${MERCHANT_API}/merchants?size=100`);
  if (first.content.length === 0) {
    fail('No merchants. Start the demo profile so the simulator seeds first.');
  }
  const pages = Math.max(1, Math.ceil(first.page.totalElements / PAGE_SIZE));
  return { merchantIds: first.content.map((m) => m.id), pages };
}

export default function (data) {
  if (Math.random() < 0.5) {
    const page = Math.floor(Math.random() * data.pages);
    const res = http.get(`${MERCHANT_API}/merchants?page=${page}&size=${PAGE_SIZE}`, {
      tags: { ...LOAD, name: 'GET /merchants' },
    });
    check(res, { 'merchants listed (200)': (r) => r.status === 200 });
  } else {
    const res = http.get(`${MERCHANT_API}/merchants/${pick(data.merchantIds)}/menu`, {
      tags: { ...LOAD, name: 'GET /merchants/{id}/menu' },
    });
    check(res, { 'menu read (200)': (r) => r.status === 200 });
  }
}
