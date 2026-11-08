-- Merchant moved to merchant-service with its own merchant_db (M5-5, ADR-0003, ADR-0007), so the last foreign keys from
-- order tables to merchant tables go. orders.merchant_id and order_lines.menu_item_id keep their values: they now hold
-- ids from another database, checked against the order module's own copies when an order is placed (M5-3), never by a
-- FK. Nothing in this app reads merchants or menu_items any more, so the tables go too. merchant_db starts empty, and
-- the simulator reseeds (no data migration, ADR-0007).
ALTER TABLE orders DROP CONSTRAINT orders_merchant_id_fkey;
ALTER TABLE order_lines DROP CONSTRAINT order_lines_menu_item_id_fkey;

-- Child first: menu_items points at merchants.
DROP TABLE menu_items;
DROP TABLE merchants;
