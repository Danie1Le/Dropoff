-- pg_stat_statements for the M7 performance work (M7-1, ticket #35): Postgres counts calls, total and mean time and
-- rows for every normalized statement, across all databases. compose.yaml preloads the library; this creates its view
-- in the `dropoff` admin database (POSTGRES_DB, where this file runs). Read it there as the superuser, who sees every
-- service's statements, for example the top 5 by total time:
--   docker compose exec postgres psql -U dropoff -d dropoff -c "SELECT d.datname, s.calls,
--     round(s.total_exec_time) AS total_ms, round(s.mean_exec_time::numeric, 2) AS mean_ms, left(s.query, 80) AS query
--     FROM pg_stat_statements s JOIN pg_database d ON d.oid = s.dbid ORDER BY s.total_exec_time DESC LIMIT 5"
-- Reset the counters before a run with: SELECT pg_stat_statements_reset();
-- Like 01, this runs only on an empty volume: after pulling it, `docker compose down -v` and reseed.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
