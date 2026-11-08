-- merchant-service's baseline in merchant_db (M5-5, ADR-0007): the schema the app's V2, V3, V6 and V15 built for the
-- merchant module, in one file. merchant_db starts empty and the simulator reseeds it; there is no data migration.
-- Nothing here points at order tables, and order-service keeps its own copies with no FK back (ADR-0003). CHECKs
-- repeat the request rules so bad rows can't get in another way.

-- Merchants sell menu items and prepare orders (GLOSSARY: Merchant). Lengths match the request validation.
-- version is 1 on create and goes up by 1 on every change, with the row locked, so versions follow commit order. Each
-- MerchantChanged carries it, and order-service's copy takes an event only if its version is higher (M5-3).
CREATE TABLE merchants (
    id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name     VARCHAR(200)     NOT NULL CHECK (length(trim(name)) > 0),
    address  VARCHAR(500)     NOT NULL CHECK (length(trim(address)) > 0),
    lat      DOUBLE PRECISION NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lng      DOUBLE PRECISION NOT NULL CHECK (lng BETWEEN -180 AND 180),
    is_open  BOOLEAN          NOT NULL DEFAULT TRUE,
    version  BIGINT           NOT NULL DEFAULT 0
);

-- Menu items are what a merchant sells (GLOSSARY: Menu Item). Never deleted (ticket #61): orders keep their ids.
-- Prices are whole cents in a BIGINT, so totals never round. version works as on merchants, for MenuItemChanged.
CREATE TABLE menu_items (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    merchant_id   BIGINT       NOT NULL REFERENCES merchants (id),
    name          VARCHAR(200) NOT NULL CHECK (length(trim(name)) > 0),
    price_cents   BIGINT       NOT NULL CHECK (price_cents >= 0),
    is_available  BOOLEAN      NOT NULL DEFAULT TRUE,
    version       BIGINT       NOT NULL DEFAULT 0
);

-- The menu query reads all items of one merchant.
CREATE INDEX menu_items_merchant_id_idx ON menu_items (merchant_id);

-- Spring Modulith's event publication registry, used as the outbox (ADR-0006, tickets #15 and #42).
-- Copied from spring-modulith-events-jdbc 2.1.2, schemas/v2/schema-postgresql.sql; Modulith's own schema init is off.
-- merchant-service reads no topic, so it has no processed_events table.
CREATE TABLE IF NOT EXISTS event_publication
(
  id                     UUID NOT NULL,
  listener_id            TEXT NOT NULL,
  event_type             TEXT NOT NULL,
  serialized_event       TEXT NOT NULL,
  publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
  completion_date        TIMESTAMP WITH TIME ZONE,
  status                 TEXT,
  completion_attempts    INT,
  last_resubmission_date TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx ON event_publication USING hash(serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx ON event_publication (completion_date);
