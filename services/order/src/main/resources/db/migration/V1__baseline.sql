-- order-service's baseline in order_db (M5-6, ADR-0007): the schema the app's V1 to V17 left for the order module, in
-- one file. order_db starts empty and the simulator reseeds it; there is no data migration. Merchants, menu items,
-- couriers and offers live in other services' databases, so orders.merchant_id, courier_id and assigned_offer_id and
-- order_lines.menu_item_id hold ids that no FK checks (ADR-0003). CHECKs repeat the Java rules (ticket #3) so bad rows
-- can't get in another way.

-- Customers place orders and receive deliveries (GLOSSARY: Customer, ticket #9). Lengths match the request validation.
CREATE TABLE customers (
    id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name     VARCHAR(200)     NOT NULL CHECK (length(trim(name)) > 0),
    address  VARCHAR(500)     NOT NULL CHECK (length(trim(address)) > 0),
    lat      DOUBLE PRECISION NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lng      DOUBLE PRECISION NOT NULL CHECK (lng BETWEEN -180 AND 180)
);

-- Orders, their lines and their status history (GLOSSARY: Order, Order Line, Order Status).
CREATE TABLE orders (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id        BIGINT           NOT NULL REFERENCES customers (id),
    -- Checked against merchant_copies when the order is placed (M5-3), never by a FK.
    merchant_id        BIGINT           NOT NULL,
    -- Filled from CourierAssigned and cleared from CourierUnassigned.
    courier_id         BIGINT,
    status             VARCHAR(20)      NOT NULL
        CHECK (status IN ('PLACED', 'ACCEPTED', 'PREPARING', 'READY', 'PICKED_UP', 'DELIVERED', 'CANCELLED')),
    total_cents        BIGINT           NOT NULL CHECK (total_cents >= 0),
    -- The Delivery Address: the customer's address copied when the order is placed; never changes (ticket #9).
    delivery_address   VARCHAR(500)     NOT NULL CHECK (length(trim(delivery_address)) > 0),
    delivery_lat       DOUBLE PRECISION NOT NULL CHECK (delivery_lat BETWEEN -90 AND 90),
    delivery_lng       DOUBLE PRECISION NOT NULL CHECK (delivery_lng BETWEEN -180 AND 180),
    idempotency_key    VARCHAR(255)     NOT NULL UNIQUE,
    -- SHA-256 hex of the canonical request (M2-4).
    request_hash       CHAR(64)         NOT NULL,
    created_at         TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ      NOT NULL DEFAULT now(),
    -- The offer behind courier_id, as a high-water mark (ticket #85). CourierAssigned writes the courier only for an
    -- offer newer than this one, and CourierUnassigned clears the courier only for this exact offer, so a stale or
    -- reordered event can never bring an earlier courier back. Unassigning keeps the id; it is never reset.
    assigned_offer_id  BIGINT,
    -- An order is picked up or delivered only by its assigned courier (ticket #3).
    CHECK (status NOT IN ('PICKED_UP', 'DELIVERED') OR courier_id IS NOT NULL)
);

-- Merchant and customer order lists (M2-6) and the auto-cancel job (M2-7).
CREATE INDEX orders_merchant_id_status_idx ON orders (merchant_id, status, created_at);
CREATE INDEX orders_customer_id_idx ON orders (customer_id, created_at);
CREATE INDEX orders_placed_created_at_idx ON orders (created_at) WHERE status = 'PLACED';
-- The unfiltered merchant order list (M2-6) reads one merchant's orders in (created_at, id) order. The index above
-- keeps each status apart, so it can't return them in that order without a sort.
CREATE INDEX orders_merchant_id_created_at_idx ON orders (merchant_id, created_at, id);

-- price_cents is the Price Snapshot: the menu item's price when the order was placed (ADR-0004). menu_item_id is
-- checked against menu_item_copies when the order is placed, never by a FK.
CREATE TABLE order_lines (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id      BIGINT NOT NULL REFERENCES orders (id),
    menu_item_id  BIGINT NOT NULL,
    quantity      INT    NOT NULL CHECK (quantity > 0),
    price_cents   BIGINT NOT NULL CHECK (price_cents >= 0),
    UNIQUE (order_id, menu_item_id)
);

-- Permanent audit history of status changes, with who made each one (tickets #10, #15). Never deleted.
-- from_status is NULL for the row written when the order is placed.
CREATE TABLE order_events (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id     BIGINT      NOT NULL REFERENCES orders (id),
    from_status  VARCHAR(20)
        CHECK (from_status IN ('PLACED', 'ACCEPTED', 'PREPARING', 'READY', 'PICKED_UP', 'DELIVERED', 'CANCELLED')),
    to_status    VARCHAR(20) NOT NULL
        CHECK (to_status IN ('PLACED', 'ACCEPTED', 'PREPARING', 'READY', 'PICKED_UP', 'DELIVERED', 'CANCELLED')),
    actor        VARCHAR(20) NOT NULL CHECK (actor IN ('CUSTOMER', 'MERCHANT', 'COURIER', 'SYSTEM')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX order_events_order_id_idx ON order_events (order_id);

-- The order module's own copies of merchants and menu items (M5-3, tickets #16, #42, ADR-0007). It validates and
-- prices orders from these, so ordering keeps working while merchant-service is down. Filled from the compacted
-- merchants and menu-items topics with upserts; rebuilt by reading both topics from offset 0 when empty. Events can
-- arrive out of order, so a copy takes an event only if its version is higher than the one it holds.
-- No foreign keys: a copy may arrive before or without the row it copies. No CHECKs either: merchant already validated
-- every value it publishes.
CREATE TABLE merchant_copies (
    merchant_id BIGINT           PRIMARY KEY,
    name        VARCHAR(200)     NOT NULL,
    address     VARCHAR(500)     NOT NULL,
    open        BOOLEAN          NOT NULL,
    lat         DOUBLE PRECISION NOT NULL,
    lng         DOUBLE PRECISION NOT NULL,
    version     BIGINT           NOT NULL DEFAULT 0
);

CREATE TABLE menu_item_copies (
    menu_item_id BIGINT       PRIMARY KEY,
    merchant_id  BIGINT       NOT NULL,
    name         VARCHAR(200) NOT NULL,
    price_cents  BIGINT       NOT NULL,
    available    BOOLEAN      NOT NULL,
    version      BIGINT       NOT NULL DEFAULT 0
);

-- Spring Modulith's event publication registry, used as the outbox (ADR-0006, tickets #15 and #42).
-- Copied from spring-modulith-events-jdbc 2.1.2, schemas/v2/schema-postgresql.sql; Modulith's own schema init is off.
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

-- Kafka delivers at least once, so a consumer records each event it handled and skips a repeat (ticket #15,
-- ADR-0006). The row is written in the same transaction as the handler's work: a rolled-back handler leaves no row,
-- and the redelivery runs it again. Keyed per consumer group, since every group handles every event once.
CREATE TABLE processed_events (
    consumer_group VARCHAR(100) NOT NULL,
    event_id       UUID         NOT NULL,
    processed_at   TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (consumer_group, event_id)
);
