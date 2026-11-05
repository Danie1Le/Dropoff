-- Merchants sell menu items and prepare orders (GLOSSARY: Merchant).
-- Lengths match the request validation; CHECKs repeat the rules so bad rows can't get in another way.
CREATE TABLE merchants (
    id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name     VARCHAR(200)     NOT NULL CHECK (length(trim(name)) > 0),
    address  VARCHAR(500)     NOT NULL CHECK (length(trim(address)) > 0),
    lat      DOUBLE PRECISION NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lng      DOUBLE PRECISION NOT NULL CHECK (lng BETWEEN -180 AND 180),
    is_open  BOOLEAN          NOT NULL DEFAULT TRUE
);
