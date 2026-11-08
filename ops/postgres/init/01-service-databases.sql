-- One database and one user per service (M5-4, ADR-0007). Postgres runs the files in this folder once, as the
-- superuser, when the data volume is empty. After changing them, reset with `docker compose down -v`, start again and
-- reseed with the simulator. Local-only passwords: each equals its user name.
--
-- Each user owns its database, so its Flyway can create tables in the public schema. CONNECT is revoked from PUBLIC,
-- so no service can log in to, join or write another service's tables. The app keeps the `dropoff` database
-- (POSTGRES_DB) until it becomes order-service in M5-6; merchant_db is used from M5-5.
CREATE USER dispatch_user WITH PASSWORD 'dispatch_user';
CREATE USER merchant_user WITH PASSWORD 'merchant_user';
CREATE USER order_user WITH PASSWORD 'order_user';

CREATE DATABASE dispatch_db OWNER dispatch_user;
CREATE DATABASE merchant_db OWNER merchant_user;
CREATE DATABASE order_db OWNER order_user;

REVOKE CONNECT ON DATABASE dispatch_db FROM PUBLIC;
REVOKE CONNECT ON DATABASE merchant_db FROM PUBLIC;
REVOKE CONNECT ON DATABASE order_db FROM PUBLIC;
