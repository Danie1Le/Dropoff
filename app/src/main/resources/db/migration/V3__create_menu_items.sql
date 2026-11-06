-- Menu items are what a merchant sells (GLOSSARY: Menu Item). Never deleted: orders keep a foreign key to them.
-- Prices are whole cents in a BIGINT, so totals never round. CHECKs repeat the request rules.
CREATE TABLE menu_items (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    merchant_id   BIGINT       NOT NULL REFERENCES merchants (id),
    name          VARCHAR(200) NOT NULL CHECK (length(trim(name)) > 0),
    price_cents   BIGINT       NOT NULL CHECK (price_cents >= 0),
    is_available  BOOLEAN      NOT NULL DEFAULT TRUE
);

-- The menu query reads all items of one merchant.
CREATE INDEX menu_items_merchant_id_idx ON menu_items (merchant_id);
