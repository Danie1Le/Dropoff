-- The order module's own copies of merchants and menu items (M5-3, tickets #16, #42, ADR-0007). It validates and
-- prices orders from these, never from the merchant module's tables, so ordering keeps working once merchant becomes
-- its own service and while that service is down. Filled from the compacted merchants and menu-items topics with
-- upserts (the latest event per id wins); rebuilt by reading both topics from offset 0 when empty.
-- No foreign keys: a copy may arrive before or without the row it copies, and the source tables move to another
-- database at the split. No CHECKs either: merchant already validated every value it publishes.
CREATE TABLE merchant_copies (
    merchant_id BIGINT           PRIMARY KEY,
    name        VARCHAR(200)     NOT NULL,
    address     VARCHAR(500)     NOT NULL,
    open        BOOLEAN          NOT NULL,
    lat         DOUBLE PRECISION NOT NULL,
    lng         DOUBLE PRECISION NOT NULL
);

CREATE TABLE menu_item_copies (
    menu_item_id BIGINT       PRIMARY KEY,
    merchant_id  BIGINT       NOT NULL,
    name         VARCHAR(200) NOT NULL,
    price_cents  BIGINT       NOT NULL,
    available    BOOLEAN      NOT NULL
);
