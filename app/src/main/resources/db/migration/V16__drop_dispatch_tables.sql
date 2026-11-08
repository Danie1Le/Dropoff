-- Dispatch moved to dispatch-service with its own dispatch_db (M5-4, ADR-0003, ADR-0007), so the foreign keys between
-- order and dispatch tables go, both directions. orders.courier_id and orders.assigned_offer_id keep their values:
-- they now hold ids from another database, filled from CourierAssigned and never checked by a FK. Nothing in this app
-- reads couriers, offers or dispatch_orders any more, so the tables go too. dispatch_db starts empty, and the
-- simulator reseeds (no data migration, ADR-0007).
ALTER TABLE orders DROP CONSTRAINT orders_courier_id_fkey;
ALTER TABLE orders DROP CONSTRAINT orders_assigned_offer_id_fkey;

-- Children first: offers points at dispatch_orders and couriers, and dispatch_orders at couriers and orders.
DROP TABLE offers;
DROP TABLE dispatch_orders;
DROP TABLE couriers;
