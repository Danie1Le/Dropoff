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
  can't keep up, so one run leaves about 15,000 orders waiting for a courier. A later run on the same stack would count
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
| Most orders waiting for a courier | 13,669, 13,256, 13,960 | 13,669 |

This p95 doesn't measure dispatch. The timer records an order only when a courier accepts it. With 30 couriers, all 30
were BUSY at the end of every run, about 27 orders were dispatched in 10 minutes, and about 15,800 stayed READY.
The ones that got a courier were the lucky few that turned READY just as a courier came free, so their p95 is a couple
of seconds. The wait of the rest shows in `dropoff_oldest_waiting_order_age_seconds`, which reached 553 s in run 3.
That is close to the whole run.

More couriers would not fix this. A delivery takes several minutes at the simulator's 30 km/h, and the load brings
about 27 orders a second. Keeping up would take thousands of couriers. The simulator runs one bot per courier in a
256 MB container and seeds a fixed 30 (`Seeder.COURIERS`), so this baseline keeps 30. Treat dispatch p95 as a
courier-capacity number. M7-3/4 compare it only between runs with the same courier count.

### Top 5 queries by total time

The median of the 3 runs for each statement. The order was the same in all 3 runs of a scenario.

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
- The status `UPDATE` and the `order_events` insert run about 2.8 and 3.8 times per order, from the merchant bots'
  accept, prepare and ready steps. They are cheap each but lead the total.
- The deep history page is the most expensive statement per call (1.09 ms, about 16 times the first page's
  0.07 ms), as offset paging predicts. It still adds only about 1 ms to the endpoint. Each page request also runs a
  `count(*)` over the customer's 2,000 orders.

## Reproducing

Docker Desktop must be running. Close everything else; the stack needs about 4 GB.

```bash
git checkout 0f784d5f7d5c911f22de99d2b3060a8ac056b550
./gradlew :services:merchant:bootJar :services:order:bootJar :services:dispatch:bootJar :tools:simulator:installDist
./gradlew --stop
docker compose --profile demo up -d --build      # wait for "Running 41 bots" in the simulator's log
docker compose exec -T postgres psql -U order_user -d order_db -v ON_ERROR_STOP=1 -f - < ops/perf/seed-history.sql
```

Then, for each run:

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
