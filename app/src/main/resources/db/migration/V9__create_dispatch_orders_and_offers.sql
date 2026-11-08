-- Dispatch's own copy of each ready order, and the offers sent to couriers (GLOSSARY: Dispatch, Offer). Owned by the
-- dispatch module, which never reads orders (ticket #42). The FKs to orders and couriers stay until M5-4 splits the
-- dispatch tables out (ADR-0003). CHECKs repeat the Java rules so bad rows can't get in another way.

-- Filled from OrderReady. WAITING means no courier has accepted yet; an accept sets ASSIGNED and courier_id (M3-3).
CREATE TABLE dispatch_orders (
    order_id          BIGINT           PRIMARY KEY REFERENCES orders (id),
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

-- One row per offer to one courier (ticket #11). The courier has 30 s to respond.
CREATE TABLE offers (
    id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id      BIGINT      NOT NULL REFERENCES dispatch_orders (order_id),
    courier_id    BIGINT      NOT NULL REFERENCES couriers (id),
    status        VARCHAR(20) NOT NULL CHECK (status IN ('OFFERED', 'ACCEPTED', 'DECLINED', 'TIMED_OUT')),
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
