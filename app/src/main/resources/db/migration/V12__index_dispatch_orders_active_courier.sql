-- Every courier location PUT (M4-2) looks up the courier's ASSIGNED or PICKED_UP orders. DONE rows are never deleted,
-- so without an index that lookup scans the whole table. Not unique: a courier given a late order back can hold two
-- active orders at once (ticket #85).
CREATE INDEX dispatch_orders_active_courier_idx ON dispatch_orders (courier_id) WHERE status IN ('ASSIGNED', 'PICKED_UP');
