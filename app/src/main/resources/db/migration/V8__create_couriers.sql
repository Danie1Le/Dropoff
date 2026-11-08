-- Couriers and their last known location (GLOSSARY: Courier). Owned by the dispatch module (tickets #12, #42).
-- A new courier is OFFLINE with no location. Every location update overwrites last_lat / last_lng (ticket #12).
-- CHECKs repeat the Java rules so bad rows can't get in another way.
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

-- V5 left orders.courier_id without a FK because couriers did not exist yet. It stays until M5-4 splits the
-- dispatch tables out (ADR-0003, ticket #42).
ALTER TABLE orders ADD CONSTRAINT orders_courier_id_fkey FOREIGN KEY (courier_id) REFERENCES couriers (id);
