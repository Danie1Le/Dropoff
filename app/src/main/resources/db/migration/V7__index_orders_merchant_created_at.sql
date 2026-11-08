-- The unfiltered merchant order list (M2-6) reads one merchant's orders in (created_at, id) order. The V5 index
-- (merchant_id, status, created_at) keeps each status apart, so it can't return them in that order without a sort.
CREATE INDEX orders_merchant_id_created_at_idx ON orders (merchant_id, created_at, id);
