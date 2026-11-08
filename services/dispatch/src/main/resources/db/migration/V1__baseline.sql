-- dispatch-service's baseline in dispatch_db (M5-4, ADR-0007): the schema the app's V6, V8, V9, V11, V12 and V13 built
-- for the dispatch module, in one file. dispatch_db starts empty and the simulator reseeds it; there is no data
-- migration. Order ids come from order-service's database, so nothing here has a FK to orders (ADR-0003); a
-- dispatch_orders row exists only because an OrderReady event said so. CHECKs repeat the Java rules so bad rows can't
-- get in another way.

-- Couriers and their last known location (GLOSSARY: Courier, tickets #12, #42). A new courier is OFFLINE with no
-- location. Every location update overwrites last_lat / last_lng.
CREATE TABLE couriers (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(200)     NOT NULL CHECK (length(trim(name)) > 0),
    status      VARCHAR(20)      NOT NULL DEFAULT 'OFFLINE' CHECK (status IN ('OFFLINE', 'AVAILABLE', 'BUSY')),
    last_lat    DOUBLE PRECISION CHECK (last_lat BETWEEN -90 AND 90),
    last_lng    DOUBLE PRECISION CHECK (last_lng BETWEEN -180 AND 180),
    created_at  TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ      NOT NULL DEFAULT now(),
    -- A location is both coordinates or neither.
    CHECK ((last_lat IS NULL) = (last_lng IS NULL)),
    -- Dispatch finds AVAILABLE couriers by location, so an AVAILABLE courier always has one (ticket #42).
    CHECK (status <> 'AVAILABLE' OR last_lat IS NOT NULL)
);

-- Dispatch's own copy of each ready order (GLOSSARY: Dispatch), filled from OrderReady. WAITING means no courier has
-- accepted yet; an accept sets ASSIGNED and courier_id (M3-3). order_id is order-service's id, with no FK.
CREATE TABLE dispatch_orders (
    order_id          BIGINT           PRIMARY KEY,
    merchant_name     VARCHAR(200)     NOT NULL,
    merchant_address  VARCHAR(500)     NOT NULL,
    pickup_lat        DOUBLE PRECISION NOT NULL CHECK (pickup_lat BETWEEN -90 AND 90),
    pickup_lng        DOUBLE PRECISION NOT NULL CHECK (pickup_lng BETWEEN -180 AND 180),
    delivery_address  VARCHAR(500)     NOT NULL,
    delivery_lat      DOUBLE PRECISION NOT NULL CHECK (delivery_lat BETWEEN -90 AND 90),
    delivery_lng      DOUBLE PRECISION NOT NULL CHECK (delivery_lng BETWEEN -180 AND 180),
    ready_at          TIMESTAMPTZ      NOT NULL,
    courier_id        BIGINT           REFERENCES couriers (id),
    status            VARCHAR(20)      NOT NULL DEFAULT 'WAITING'
        CHECK (status IN ('WAITING', 'ASSIGNED', 'PICKED_UP', 'DONE')),
    -- Only a WAITING order has no courier; CourierUnassigned clears the courier as it goes back to WAITING (ticket #42).
    CHECK ((status = 'WAITING') = (courier_id IS NULL))
);

-- The dispatch-again job takes the oldest WAITING orders first.
CREATE INDEX dispatch_orders_waiting_ready_at_idx ON dispatch_orders (ready_at) WHERE status = 'WAITING';

-- Every courier location PUT (M4-2) looks up the courier's ASSIGNED or PICKED_UP orders. DONE rows are never deleted,
-- so without an index that lookup scans the whole table. Not unique: a courier given a late order back can hold two
-- active orders at once (ticket #85).
CREATE INDEX dispatch_orders_active_courier_idx ON dispatch_orders (courier_id) WHERE status IN ('ASSIGNED', 'PICKED_UP');

-- One row per offer to one courier (GLOSSARY: Offer, ticket #11). The courier has 30 s to respond. WITHDRAWN: dispatch
-- closed an open offer that its courier never answered, when a late OrderPickedUp gave the order back to the courier
-- who already carries it (ticket #85).
CREATE TABLE offers (
    id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id      BIGINT      NOT NULL REFERENCES dispatch_orders (order_id),
    courier_id    BIGINT      NOT NULL REFERENCES couriers (id),
    status        VARCHAR(20) NOT NULL
        CHECK (status IN ('OFFERED', 'ACCEPTED', 'DECLINED', 'TIMED_OUT', 'WITHDRAWN')),
    offered_at    TIMESTAMPTZ NOT NULL,
    expires_at    TIMESTAMPTZ NOT NULL,
    responded_at  TIMESTAMPTZ,
    CHECK (expires_at > offered_at),
    -- An open offer has no response yet.
    CHECK (status <> 'OFFERED' OR responded_at IS NULL)
);

-- The safety net for two dispatches of one order (decision #14): a second open offer fails and rolls back its
-- transaction, courier claim included. OFFERED only, since an order whose courier was unassigned needs a new offer.
CREATE UNIQUE INDEX one_open_offer_per_order ON offers (order_id) WHERE status = 'OFFERED';

-- Each dispatch reads the order's earlier offers to skip couriers offered it in the last 5 min (decision #13).
CREATE INDEX offers_order_id_offered_at_idx ON offers (order_id, offered_at);

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
