-- Historical orders for the M7 load tests (M7-1, ticket #35): 100 customers with 2,000 past orders each, 200,000
-- orders in all, with one to three lines per order. Without them order_db holds a few hundred rows, every query is a
-- fast scan, and neither an index nor deep paging can matter.
--
-- Run it once into order_db, after the demo profile has seeded (it needs the menu item copies) and before the k6 runs:
--   docker compose exec -T postgres psql -U order_user -d order_db -v ON_ERROR_STOP=1 -f - < ops/perf/seed-history.sql
-- It runs in one transaction and refuses to run twice. `docker compose down -v` removes the data.
--
-- Every value comes from the row number, not random(), so each reseed builds the same data and runs compare fairly.
-- The customers take fixed ids from 1000001 (OVERRIDING SYSTEM VALUE), far above the ids the identity hands out, so
-- ops/k6/order-history.js knows them without a lookup.
BEGIN;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM customers WHERE id = 1000001) THEN
        RAISE EXCEPTION 'History already seeded. Reset with docker compose down -v to seed again.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM menu_item_copies) THEN
        RAISE EXCEPTION 'No menu item copies yet. Start the demo profile and let the simulator seed first.';
    END IF;
END
$$;

-- Customers around downtown Austin, the demo city (ticket #33).
INSERT INTO customers (id, name, address, lat, lng) OVERRIDING SYSTEM VALUE
SELECT 1000000 + c,
       'History customer ' || c,
       c || ' Guadalupe St, Austin, TX',
       30.2672 + ((c % 41) - 20) * 0.001,
       -97.7431 + ((c % 37) - 18) * 0.001
FROM generate_series(1, 100) AS c;

-- The merchants that have menu items, numbered 1..n so an order can pick one by row number.
CREATE TEMP TABLE perf_merchants ON COMMIT DROP AS
SELECT row_number() OVER (ORDER BY merchant_id) AS n, merchant_id
FROM (SELECT DISTINCT merchant_id FROM menu_item_copies) AS m;

-- Each merchant's menu items, numbered 1..cnt per merchant.
CREATE TEMP TABLE perf_items ON COMMIT DROP AS
SELECT merchant_id,
       menu_item_id,
       price_cents,
       row_number() OVER (PARTITION BY merchant_id ORDER BY menu_item_id) AS n,
       count(*) OVER (PARTITION BY merchant_id) AS cnt
FROM menu_item_copies;

-- Order g belongs to customer 1000001 + (g - 1) % 100, so every customer gets 2,000 orders spread over the past year
-- (7919 is prime, so the minute offsets scatter instead of rising with g). One in ten was cancelled; the rest were
-- delivered, which needs a courier (ticket #3). Courier ids are only labels here: no FK checks them (ADR-0003).
-- total_cents starts at 0 and is set from the lines below.
INSERT INTO orders (customer_id, merchant_id, courier_id, status, total_cents, delivery_address, delivery_lat,
                    delivery_lng, idempotency_key, request_hash, created_at, updated_at)
SELECT c.id,
       pm.merchant_id,
       CASE WHEN g % 10 = 0 THEN NULL ELSE 1 + g % 30 END,
       CASE WHEN g % 10 = 0 THEN 'CANCELLED' ELSE 'DELIVERED' END,
       0,
       c.address,
       c.lat,
       c.lng,
       'history-' || g,
       md5(g::text) || md5(g::text),
       now() - interval '1 day' - ((g * 7919) % 525600) * interval '1 minute',
       now() - interval '1 day' - ((g * 7919) % 525600) * interval '1 minute' + interval '45 minutes'
FROM generate_series(1, 200000) AS g
JOIN customers AS c ON c.id = 1000001 + (g - 1) % 100
JOIN perf_merchants AS pm ON pm.n = 1 + g % (SELECT count(*) FROM perf_merchants);

-- One to three lines per order, each a different item of the order's merchant: (o.id + k) % cnt differs for every
-- k below cnt, so UNIQUE (order_id, menu_item_id) holds even for a merchant with fewer than three items.
INSERT INTO order_lines (order_id, menu_item_id, quantity, price_cents)
SELECT o.id, i.menu_item_id, 1 + (o.id + k) % 3, i.price_cents
FROM orders AS o
CROSS JOIN generate_series(0, 2) AS k
JOIN perf_items AS i ON i.merchant_id = o.merchant_id AND i.n = 1 + (o.id + k) % i.cnt
WHERE o.customer_id > 1000000
  AND k <= o.id % 3
  AND k < i.cnt;

UPDATE orders AS o
SET total_cents = l.total
FROM (SELECT order_id, sum(quantity * price_cents) AS total FROM order_lines GROUP BY order_id) AS l
WHERE l.order_id = o.id
  AND o.customer_id > 1000000;

COMMIT;

-- Fresh planner statistics, so the first runs are planned for 200k orders and not for the few hundred before.
ANALYZE customers;
ANALYZE orders;
ANALYZE order_lines;

SELECT count(*) AS history_orders FROM orders WHERE customer_id > 1000000;
