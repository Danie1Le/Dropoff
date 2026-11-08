-- Versions for merchants, menu items and the order module's copies of them (M5-3, ADR-0007). Merchant sets
-- version 1 on create and adds 1 on every change, with the row locked, so versions follow commit order. Each
-- MerchantChanged/MenuItemChanged event carries the version. Events can reach the copies out of order (async
-- externalization, a republish on restart, a DLT replay), so a copy takes an event only if its version is higher than
-- the one the copy holds. Rows from before this migration start at 0, so their next event always applies.
ALTER TABLE merchants ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE menu_items ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE merchant_copies ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE menu_item_copies ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
