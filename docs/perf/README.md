# Performance

M7 measures the three services under load, picks one or two fixes from the numbers, and measures again (ticket #35,
`docs/PLAN.md` section M7, `docs/milestones/M7.md`). This file holds the setup and the baseline (M7-2). M7-3/4 add each
change with its `EXPLAIN`, and M7-5 adds the after table and the summary.

## Setup

| | |
|---|---|
| Commit | `0f784d5f7d5c911f22de99d2b3060a8ac056b550` (main after M7-1, #124) |
| Date | 2026-11-08 |
| CPU | Intel Core i7-9700F @ 3.00 GHz, 8 cores, 8 threads |
| RAM | 16 GB (15.9 GB visible), about 4.8 GB free before the stack started |
| OS | Windows 11 Home 10.0.26300 |
| Docker | Docker Desktop, engine 29.9.2, WSL2 kernel 6.18.54.1; the VM sees 8 CPUs and 7.73 GiB |
| Container limits | as in `compose.yaml`, unchanged: merchant, order and dispatch 512 MB each (`-Xmx256m`), simulator 256 MB (`-Xmx128m`), k6 256 MB; Postgres, Kafka, Redis, Prometheus and Grafana have no limit |
| Load generator | `grafana/k6:2.3.0`, in the same Docker VM as the services |
| Data | the simulator's seed (10 merchants with 8 menu items each, 50 customers, 30 couriers) plus `ops/perf/seed-history.sql` (100 customers with 2,000 orders each, 200,000 orders) |
| Couriers | 30, the simulator's default (see [Dispatch p95](#dispatch-p95)) |

The stack ran with the `demo` profile, which is the `services` profile (merchant, order, dispatch) plus the simulator.
The simulator is needed: its merchant bots accept and prepare the orders `place-orders` sends, and its courier bots take
the dispatch offers. Without it, orders stop at PLACED and dispatch has nothing to measure. Its customer bots also place
about 6 orders a minute in the background, which is noise next to the load.

Nothing else ran on the machine during the runs. No container was OOM-killed or restarted (checked with
`docker inspect` after every run). The busiest containers at the end of a `place-orders` run used: order 407 to 427 MB
of 512 MB, dispatch 411 to 420 MB, merchant 322 to 328 MB, Kafka about 1 GB, Postgres 354 to 403 MB.

## Method

- Each scenario ran 3 times with `LOAD_PROFILE=full` (the ticket #35 shape):
  - `place-orders`: 5 to 50 orders/s over 10 min.
  - `browse`: 100 req/s for 5 min.
  - `order-history`: 50 req/s for 5 min.
- `pg_stat_statements_reset()` ran right before each run, so each run's top 5 holds only that run.
- `browse` and `order-history` change no data, so their six runs shared one stack.
- Each `place-orders` run started from a fresh stack (`down -v`, `up`, simulator seed, history seed). The 30 couriers
  can't keep up, so one run leaves about 15,800 orders READY and waiting for a courier (the order_db count at run end,
  see [Dispatch p95](#dispatch-p95)). A later run on the same stack would count
  their long waits in its dispatch time.
- Every number in the tables is the median of the 3 runs, taken separately for each column.
- Latency is k6's `http_req_duration` for the measured requests only (`kind:load`), so setup requests don't count.
- Dispatch time is `dropoff_dispatch_duration_seconds` from Prometheus: `histogram_quantile(0.95, sum by (le)
  (increase(dropoff_dispatch_duration_seconds_bucket[<run length>])))` at the end of the run.

## Baseline

All three scenarios passed their thresholds (p95 under 300 ms, errors under 1%) in every run.

| Scenario | Endpoint | Throughput | p50 | p95 | p99 | Errors |
|---|---|---|---|---|---|---|
| `place-orders` | `POST /orders` | 27.42 req/s (16,471 orders) | 7.08 ms | 13.42 ms | 66.24 ms | 0.00% |
| `browse` | all | 100.00 req/s | 1.41 ms | 1.80 ms | 2.20 ms | 0.00% |
| | `GET /merchants` | | 1.41 ms | 1.80 ms | 2.23 ms | |
| | `GET /merchants/{id}/menu` | | 1.41 ms | 1.79 ms | 2.17 ms | |
| `order-history` | all | 50.00 req/s | 3.30 ms | 4.45 ms | 5.39 ms | 0.00% |
| | `GET /customers/{id}/orders`, page 0 | | 2.74 ms | 3.45 ms | 4.65 ms | |
| | `GET /customers/{id}/orders`, deep page | | 3.69 ms | 4.69 ms | 5.71 ms | |

`place-orders` ramps from 5 to 50 orders/s, so its average is about 27.5/s. k6 dropped 29 of about 16,500 planned
iterations in the median run (29, 64 and 0 in the three runs), which means it briefly ran out of VUs while requests
were slow.

The three runs, for the spread:

| Scenario | p50 (runs 1, 2, 3) | p95 | p99 |
|---|---|---|---|
| `place-orders` | 7.08, 7.33, 7.06 ms | 13.42, 16.32, 12.39 ms | 66.24, 527.47, 40.70 ms |
| `browse` | 1.41, 1.42, 1.41 ms | 1.80, 1.79, 1.80 ms | 2.21, 2.19, 2.20 ms |
| `order-history` | 3.39, 3.30, 3.28 ms | 4.80, 4.45, 4.40 ms | 6.15, 5.39, 5.27 ms |

Run 2 of `place-orders` had a p99 of 527 ms, while the others stayed under 70 ms. The median keeps that one spike out of
the baseline.

### Dispatch p95

| | Runs 1, 2, 3 | Median |
|---|---|---|
| Dispatch time p95 (READY to accept) | 2.12 s, 32.07 s, 2.13 s | 2.13 s |
| Dispatch time p50 | 1.00 s, 0.93 s, 0.93 s | 0.93 s |
| Orders dispatched during the run | 24, 29, 27 | 27 |
| Peak orders waiting for a courier (Prometheus `max_over_time(dropoff_orders_waiting_for_courier)`) | 13,669, 13,256, 13,960 | 13,669 |
| Orders READY at run end (order_db `orders` count, `status = 'READY'`) | 15,697, 15,777, 15,847 | 15,777 |

This p95 doesn't measure dispatch. The timer records an order only when a courier accepts it. With 30 couriers, all 30
were BUSY at the end of every run, about 27 orders were dispatched in 10 minutes, and about 15,800 stayed READY at the end (the order_db count above;
the Prometheus peak is lower, 13,669; the two are different sources, read at different times, and not reconciled here).
The ones that got a courier were the lucky few that turned READY just as a courier came free, so their p95 is a couple
of seconds. The rest never got one: about 27 of roughly 16,500 placed orders (under 0.2%) were accepted by a courier.

More couriers would not fix this. A delivery takes several minutes at the simulator's 30 km/h, and the load brings
about 27 orders a second. Keeping up would take thousands of couriers. The simulator runs one bot per courier in a
256 MB container and seeds a fixed 30 (`Seeder.COURIERS`), so this baseline keeps 30. Treat dispatch p95 as a
courier-capacity number. M7-3/4 compare it only between runs with the same courier count.

### Top 5 queries by total time

The median of the 3 runs for each statement. The five statements and their order were the same in all 3 runs of a scenario, except `place-orders` run 3, where ranks 3
and 4 swap (`update order_lines` 2,988 ms above `insert into orders` 2,981 ms).

`place-orders` (one run places about 16,500 orders):

| DB | Calls | Total | Mean | Statement |
|---|---|---|---|---|
| order_db | 45,619 | 8,766 ms | 0.19 ms | `UPDATE orders o SET status = $1, updated_at = $2 FROM (SELECT id, status FROM orders WHERE id = $3 FOR UPDATE) old WHERE ...` |
| order_db | 62,179 | 6,906 ms | 0.11 ms | `INSERT INTO order_events (order_id, from_status, to_status, actor, created_at) VALUES (...)` |
| order_db | 16,531 | 3,056 ms | 0.18 ms | `insert into orders (courier_id, created_at, customer_id, ...)` |
| order_db | 33,158 | 3,034 ms | 0.09 ms | `update order_lines set order_id=$1 where id=$2` |
| order_db | 45,619 | 2,812 ms | 0.06 ms | `select o1_0.id, o1_0.courier_id, ... from orders o1_0 where ...` |

`browse`:

| DB | Calls | Total | Mean | Statement |
|---|---|---|---|---|
| merchant_db | 14,969 | 684 ms | 0.05 ms | `select ... from merchants m1_0 order by m1_0.id offset $1 rows fetch first $2 rows only` |
| merchant_db | 15,033 | 609 ms | 0.04 ms | `select ... from merchants m1_0 where m1_0.id=$1` |
| merchant_db | 15,033 | 374 ms | 0.03 ms | `select ... from menu_items mi1_0 where mi1_0.merchant_id=$1 order by ...` |
| dispatch_db | 2,862 | 280 ms | 0.10 ms | `update couriers set last_lat=$1, last_lng=$2, name=$3, status=$4, updated_at=$5 where id=$6` |
| merchant_db | 30,002 | 218 ms | 0.01 ms | `BEGIN READ ONLY` |

`order-history`:

| DB | Calls | Total | Mean | Statement |
|---|---|---|---|---|
| order_db | 7,530 | 8,142 ms | 1.09 ms | `SELECT * FROM orders WHERE customer_id = $1 ORDER BY created_at DESC, id DESC offset $2 rows fetch next $3 rows only` |
| order_db | 15,002 | 2,357 ms | 0.16 ms | `SELECT count(*) FROM orders WHERE customer_id = $1` |
| order_db | 15,005 | 1,635 ms | 0.11 ms | `select ... from order_lines l1_0 where l1_0.order_id = any ($1) order by l1_0.id` |
| order_db | 7,472 | 532 ms | 0.07 ms | `SELECT * FROM orders WHERE customer_id = $1 ORDER BY created_at DESC, id DESC fetch first $2 rows only` |
| order_db | 15,002 | 359 ms | 0.02 ms | `select count(*) from customers c1_0 where c1_0.id=$1` |

What stands out, for M7-3/4 to confirm with `EXPLAIN (ANALYZE, BUFFERS)`:

- The worst p95 is `POST /orders` (13.42 ms), and the worst p99 too. Every endpoint is far under the 300 ms threshold.
- Each order costs two `update order_lines set order_id` after its insert, about 2 per order. That is Hibernate
  writing the foreign key of a one-to-many after the line insert. A batching or mapping fix removes them.
- The status `UPDATE` and the `order_events` insert run about 2.8 and 3.8 times per order (45,619 and 62,179 calls for 16,531 order inserts). They come from the whole
  order lifecycle: PLACED, the merchant accept, prepare and ready steps, and the dispatch and courier transitions. They
  are cheap each but lead the total.
- The deep history page is the most expensive statement per call (1.09 ms, about 16 times the first page's
  0.07 ms), as offset paging predicts. It still adds only about 1 ms to the endpoint. Each page request also runs a
  `count(*)` over the customer's 2,000 orders.

### Targets for M7-3/M7-4

Rank candidates by total DB time from `pg_stat_statements` and by per-call cost, not by endpoint latency, since every p95 is
far under 300 ms. Targets: the `place-orders` status `UPDATE` and `order_events` insert, the extra `order_lines` update per
order, and the deep-page `OFFSET` plus `count(*)` in `order-history`. Dispatch p95 is not a target: it is courier capacity.

## M7-3: changes and before/after

Three changes in one PR, all in order-service and all aimed at the targets above. The API and its behaviour are the same:
the existing tests pass unchanged, and two new `OrderListRepositoryIT` cases pin the history order (ties broken by the
newest id) and the totals on every page.

Deviation from #122 ("each optimization its own PR", one thing at a time): this PR has three changes. Re-measuring is about an hour per scenario on this host, and the line change and the status change both sit on the same `POST /orders` path. Splitting would have meant re-measuring `place-orders` for each. So the k6 `place-orders` p50 gain cannot be split between the two changes. The per-statement `pg_stat_statements` numbers below attribute each statement separately; use them as the evidence for each change.

| Change | Target it hits |
|---|---|
| `OrderLine` owns `order_id` (`@ManyToOne`, `mappedBy` on `Order.lines`) | the `update order_lines set order_id` after every line insert |
| Status commands run `UPDATE orders ... WHERE id = $3 AND status IN ($4) RETURNING old.status` | the status `UPDATE` |
| `V3`: index `(customer_id, created_at, id)` replaces `(customer_id, created_at)`; later history pages use a deferred join | the deep-page `OFFSET` |

### Measurement

Same setup and method as the baseline (demo profile, `LOAD_PROFILE=full`, 3 runs each, `pg_stat_statements_reset()`
before each run, `MSYS_NO_PATHCONV=1`, every command from Git Bash). Only the two affected scenarios were measured
again: `order-history` with 3 runs on one stack, and `place-orders` on a fresh stack for each run (`down -v`, `up`,
simulator seed, history seed). `browse` reads only merchant-service, which this PR doesn't touch. The code was at
`b0300b6` on `feat/m7-3-optimizations`, on 2026-11-08. Autovacuum had processed `orders` and `order_lines` before
every run (it starts about a minute after the history seed). No container was OOM-killed or restarted. The host had
less free memory than for the baseline (about 2 GB free with the stack down, against 4.8 GB), so for `place-orders`
one extra pair of runs was added: `main` (`ddca65c`) and this branch, back to back, on the same host state.

### `order-history`

| Endpoint | Before p50 / p95 / p99 | After p50 / p95 / p99 | Change (p50 / p95 / p99) |
|---|---|---|---|
| all | 3.30 / 4.45 / 5.39 ms | 3.15 / 4.03 / 5.01 ms | −4.5% / −9.4% / −7.1% |
| page 0 | 2.74 / 3.45 / 4.65 ms | 2.78 / 3.58 / 4.69 ms | +1.5% / +3.8% / +0.9% |
| deep page | 3.69 / 4.69 / 5.71 ms | 3.31 / 4.21 / 5.21 ms | −10.3% / −10.2% / −8.8% |

50.00 req/s and 0.00% errors in every run, as before. After, runs 1, 2 and 3: p50 3.35, 3.15, 3.15 ms; p95 5.21, 4.03,
3.97 ms; p99 7.71, 5.01, 4.77 ms. Page 0 is unchanged within run-to-run noise. The deep page gains about 0.4 to 0.5 ms
at every percentile, about what its query lost.

`pg_stat_statements`, median of the 3 runs:

| Statement | Before calls / total / mean | After calls / total / mean |
|---|---|---|
| deep page: `... OFFSET $2 ROWS FETCH NEXT $3 ROWS ONLY`, now the deferred join | 7,530 / 8,142 ms / 1.09 ms | 7,495 / 1,843 ms / 0.25 ms |
| page 0: `... FETCH FIRST $2 ROWS ONLY`, now `... LIMIT $2` | 7,472 / 532 ms / 0.07 ms | 7,507 / 473 ms / 0.06 ms |
| `SELECT count(*) FROM orders WHERE customer_id = $1` | 15,002 / 2,357 ms / 0.16 ms | 15,002 / 2,443 ms / 0.16 ms |
| order lines of the page (`... where l1_0.order_id = any ($1)`) | 15,005 / 1,635 ms / 0.11 ms | 15,005 / 1,746 ms / 0.12 ms |

The two page queries together went from 8,674 ms to 2,316 ms per run (−73%). The deep page alone went from 1.09 ms to
0.25 ms a call (−77%). The `count(*)` is now the most expensive history statement. It already reads only the index
(`Heap Fetches: 0`, 13 to 19 buffers). Dropping or caching it would change the `page.totalElements` the API returns,
so it stays.

Why the deep page was slow. The old index stopped at `created_at`, so the list's `ORDER BY created_at DESC, id DESC`
couldn't come from it. For a deep page Postgres read all 2,000 of the customer's orders from 2,102 heap pages, sorted
them, and threw 1,980 away (warm cache, customer 1000042, offset 1980):

```text
Limit  (actual time=3.471..3.476 rows=20.00 loops=1)
  Buffers: shared hit=2123
  ->  Sort  (actual time=3.204..3.413 rows=2000.00 loops=1)
        Sort Key: created_at DESC, id DESC
        ->  Bitmap Heap Scan on orders  (actual time=1.105..2.512 rows=2000.00 loops=1)
              Heap Blocks: exact=2102
              ->  Bitmap Index Scan on orders_customer_id_idx  (rows=4000.00 loops=1)
Execution Time: 3.630 ms
```

After. With `id` in the index, a backward scan returns the ids already in list order. The deferred join skips the
offset inside an index-only scan and reads only the 20 rows it keeps, by primary key:

```sql
SELECT o.* FROM orders o
JOIN (SELECT id FROM orders WHERE customer_id = :customerId
      ORDER BY created_at DESC, id DESC OFFSET :offset LIMIT :limit) page ON page.id = o.id
ORDER BY o.created_at DESC, o.id DESC
```

```text
Sort  (rows=20.00 loops=1)
  Buffers: shared hit=99
  ->  Nested Loop  (rows=20.00 loops=1)
        ->  Limit  (rows=20.00 loops=1)
              ->  Index Only Scan Backward using orders_customer_id_created_at_id_idx on orders  (rows=2000.00 loops=1)
                    Heap Fetches: 0
                    Buffers: shared hit=19
        ->  Index Scan using orders_pkey on orders o  (rows=1.00 loops=20)
              Buffers: shared hit=80
```

2,123 buffers became 99. The generic plan Postgres can switch to for a prepared statement has the same shape (checked
with `plan_cache_mode = force_generic_plan`).

Page 0 skips the join. A first try used the deferred join for every page and made page 0 slower: its 20 primary-key
probes cost 84 buffers and 0.15 ms, against 23 buffers and 0.06 ms for a plain `LIMIT` on the backward index scan.
With nothing to skip, the join only adds work. `OrderListRepositoryCustomImpl.findForCustomer` now runs the plain query for page
0 and the join for every later page. The numbers above are from that version.

Keyset paging (`WHERE (created_at, id) < ($1, $2)`) would make a deep page as cheap as page 0. It would also replace
`?page=` with a cursor and drop the page count, which changes the API contract, so it wasn't used. The deferred join still walks the skipped index entries, so its cost grows with the offset, but it reads no
table rows for them.

### `place-orders`

| | Before (baseline median) | After (median of 3) | Change |
|---|---|---|---|
| Throughput | 27.42 req/s (16,471 orders) | 27.40 req/s (16,459 orders) | 0% |
| p50 | 7.08 ms | 6.69 ms | −5.5% |
| p95 | 13.42 ms | 17.60 ms | +31% |
| p99 | 66.24 ms | 1,260.54 ms | see below |
| Errors | 0.00% | 0.00% | |

After, runs 1, 2 and 3: p50 6.69, 6.72, 6.67 ms; p95 17.60, 19.42, 14.68 ms; p99 2,074.09, 1,260.54, 889.28 ms. k6
dropped 206, 52 and 71 iterations.

The p95 and p99 rise is most likely the host, not the change, but this PR did not prove it. All 3 after-run p99 values (2,074.09, 1,260.54, 889.28 ms) exceed the baseline's worst (527 ms), and k6 dropped 206 / 52 / 71 iterations, against 29 / 64 / 0 in the baseline. One extra back-to-back pair on the same host state (n=1) points at the host:

| Run | p50 | p95 | p99 |
|---|---|---|---|
| `main` (`ddca65c`), 1 run | 7.18 ms | 14.58 ms | 268.38 ms |
| this branch, 1 run | 6.57 ms | 12.38 ms | 49.48 ms |

In that pair this branch had a p99 of 49 ms against 268 ms on `main`. That is one pair, so the tail cause is most likely host pauses on a host short of memory (about 2 GB free, against 4.8 GB at the baseline: GC, Docker, paging), not the SQL. Two more back-to-back pairs would settle it; they were not run. p50 is the stable comparison: 6.57 to 6.72 ms on this branch in all four runs, against 7.06 to 7.33 ms in the baseline and 7.18 ms on `main`, about 0.4 ms (-5%) faster. Part of that is the DB time saved per order below (about
0.19 ms for the line updates), and part is the two round trips those updates no longer make.

`pg_stat_statements` for the targeted statements in order_db (calls / total / mean). "Control" is the `main` run of the
pair above. The baseline's top 5 didn't list the line insert.

| Statement | Before (baseline median) | Control (`main`, 1 run) | After (median of 3) |
|---|---|---|---|
| status `UPDATE` | 45,619 / 8,766 ms / 0.19 ms | 45,519 / 8,880 ms / 0.20 ms | 45,348 / 8,283 ms / 0.18 ms |
| `update order_lines set order_id=$1 where id=$2` | 33,158 / 3,034 ms / 0.09 ms | 32,892 / 3,097 ms / 0.09 ms | gone |
| `insert into order_lines (...)` | not in the top 5 | 32,892 / 2,790 ms / 0.08 ms | 33,001 / 2,728 ms / 0.08 ms |
| `INSERT INTO order_events (...)` | 62,179 / 6,906 ms / 0.11 ms | 62,012 / 7,048 ms / 0.11 ms | 61,836 / 7,197 ms / 0.12 ms |
| `insert into orders (...)` | 16,531 / 3,056 ms / 0.18 ms | 16,493 / 3,122 ms / 0.19 ms | 16,488 / 3,120 ms / 0.19 ms |

- **`order_lines`:** the control shows that Hibernate already wrote `order_id` in the line `INSERT` and then set it
  again with an `UPDATE`, once per line. That came from the unidirectional `@OneToMany @JoinColumn`. With the line
  owning the column, the `UPDATE` is gone and the insert costs the same: 3,097 ms of DB time per run saved, about
  0.19 ms per order, with no other cost.
- **Status `UPDATE`:** the old statement joined `orders` to a `FOR UPDATE` sub-select of itself to read the old status,
  so it probed the primary key twice and added a `LockRows` node. An `UPDATE` with the source statuses in its `WHERE`
  already waits for a racing command's row lock and re-checks the `WHERE` on the new row version (READ COMMITTED). So
  `RETURNING old.status` (new in Postgres 18) gives the same answer, with one probe:

  ```text
  -- before
  Update on orders o
    ->  Nested Loop
          ->  Subquery Scan on old
                ->  LockRows
                      ->  Index Scan using orders_pkey on orders  (Index Cond: (id = 150000))
          ->  Index Scan using orders_pkey on orders o  (Index Cond: (id = old.id))
  -- after
  Update on orders
    ->  Index Scan using orders_pkey on orders  (Index Cond: (id = 150000))
          Filter: ((status)::text = ANY ('{DELIVERED,CANCELLED}'::text[]))
  ```

  The gain is small, 0.19 ms to 0.18 ms a call and 8,766 ms to 8,283 ms a run (−5.5%). Most of an `UPDATE`'s cost is
  writing the new row version and its index entries. `status` is in three indexes, so the update can't be HOT.
- **`order_events`:** unchanged. It is one audit row per status change (ticket #10), so it can't be skipped, and its
  0.11 ms includes the foreign-key check on `orders`.

The four statements other than the line insert took 21,762 ms of DB time per run in the baseline, 22,147 ms in the
control and 18,600 ms after (−15% against the baseline). Dispatch p95 is
courier capacity, as in the baseline: runs 1, 2, 3 gave 8.16, 2.38 and 2.72 s (median 2.72 s, baseline 2.13 s), with
about 28 orders dispatched per run and 30 couriers. It isn't a result of this PR.

### What's left

- `order-history`: the `count(*)` per page, now the top statement, costs 0.16 ms. Removing it changes the API.
- `place-orders`: the status `UPDATE` and the `order_events` insert are one each per status change. Fewer of them
  would mean fewer transitions or no audit trail, which are behaviour changes. A second optimization PR (M7-4) has no
  target worth it in these numbers.

## Reproducing

Docker Desktop must be running. Close everything else; the stack needs about 4 GB.

```bash
git checkout 0f784d5f7d5c911f22de99d2b3060a8ac056b550
./gradlew :services:merchant:bootJar :services:order:bootJar :services:dispatch:bootJar :tools:simulator:installDist
./gradlew --stop
docker compose --profile demo up -d --build      # wait for "Running 41 bots" in the simulator's log
docker compose exec -T postgres psql -U order_user -d order_db -v ON_ERROR_STOP=1 -f - < ops/perf/seed-history.sql
```

Then repeat the block below for all three scenarios (`browse.js`, `order-history.js`, `place-orders.js`), three runs each,
changing the script name and the `-N` suffix of the summary file (`browse-1.json`, `browse-2.json`, ...):

```bash
docker compose exec postgres psql -U dropoff -d dropoff -c "SELECT pg_stat_statements_reset()"
LOAD_PROFILE=full docker compose --profile demo --profile load run --rm k6 run \
  --summary-export=/results/browse-1.json browse.js
docker compose exec postgres psql -U dropoff -d dropoff -c "SELECT d.datname, s.calls,
  round(s.total_exec_time) AS total_ms, round(s.mean_exec_time::numeric, 2) AS mean_ms, left(s.query, 80) AS query
  FROM pg_stat_statements s JOIN pg_database d ON d.oid = s.dbid ORDER BY s.total_exec_time DESC LIMIT 5"
```

The summary JSON lands in `ops/k6/results/` (git-ignored). For `place-orders`, start each run from a fresh stack
(`docker compose --profile demo --profile load down -v`, then the `up` and the seed above). After the run, read the
dispatch time from Prometheus (http://localhost:9090), with the run length in seconds as the range:

```promql
histogram_quantile(0.95, sum by (le) (increase(dropoff_dispatch_duration_seconds_bucket[600s])))
max_over_time(dropoff_orders_waiting_for_courier[600s])
```

Stop everything afterwards with `docker compose --profile demo --profile load down -v`.

Two traps on Windows:

- Run every `docker compose` command from the same shell. The stack was started from PowerShell and the first k6 run
  from Git Bash. The two shells wrote the `./ops/postgres/init` bind mount as different paths, so `compose run`
  recreated the Postgres container just as k6 started, with cold caches. That run was thrown away and repeated.
- In Git Bash, set `MSYS_NO_PATHCONV=1` for the k6 command. Without it Git Bash rewrites `/results/...` to
  `C:/Program Files/Git/results/...`, and k6 can't write the summary.
