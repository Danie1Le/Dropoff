-- Orders, their lines and their status history (GLOSSARY: Order, Order Line, Order Status). Owned by the order module.
-- Cross-module foreign keys stay until the service split (ADR-0003). orders.courier_id gets its FK to couriers in M3-1.
-- CHECKs repeat the Java status rules (ticket #3) so bad rows can't get in another way.
CREATE TABLE orders (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id       BIGINT           NOT NULL REFERENCES customers (id),
    merchant_id       BIGINT           NOT NULL REFERENCES merchants (id),
    courier_id        BIGINT,
    status            VARCHAR(20)      NOT NULL
        CHECK (status IN ('PLACED', 'ACCEPTED', 'PREPARING', 'READY', 'PICKED_UP', 'DELIVERED', 'CANCELLED')),
    total_cents       BIGINT           NOT NULL CHECK (total_cents >= 0),
    -- The Delivery Address: the customer's address copied when the order is placed; never changes (ticket #9).
    delivery_address  VARCHAR(500)     NOT NULL CHECK (length(trim(delivery_address)) > 0),
    delivery_lat      DOUBLE PRECISION NOT NULL CHECK (delivery_lat BETWEEN -90 AND 90),
    delivery_lng      DOUBLE PRECISION NOT NULL CHECK (delivery_lng BETWEEN -180 AND 180),
    idempotency_key   VARCHAR(255)     NOT NULL UNIQUE,
    -- SHA-256 hex of the canonical request (M2-4).
    request_hash      CHAR(64)         NOT NULL,
    created_at        TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ      NOT NULL DEFAULT now(),
    -- An order is picked up or delivered only by its assigned courier (ticket #3).
    CHECK (status NOT IN ('PICKED_UP', 'DELIVERED') OR courier_id IS NOT NULL)
);

-- Merchant and customer order lists (M2-6) and the auto-cancel job (M2-7).
CREATE INDEX orders_merchant_id_status_idx ON orders (merchant_id, status, created_at);
CREATE INDEX orders_customer_id_idx ON orders (customer_id, created_at);
CREATE INDEX orders_placed_created_at_idx ON orders (created_at) WHERE status = 'PLACED';

-- price_cents is the Price Snapshot: the menu item's price when the order was placed (ADR-0004).
CREATE TABLE order_lines (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id      BIGINT NOT NULL REFERENCES orders (id),
    menu_item_id  BIGINT NOT NULL REFERENCES menu_items (id),
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
