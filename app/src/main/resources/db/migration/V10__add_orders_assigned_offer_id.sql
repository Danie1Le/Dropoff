-- The offer behind orders.courier_id, as a high-water mark (ticket #85). CourierAssigned writes the courier only for an
-- offer newer than this one, and CourierUnassigned clears the courier only for this exact offer, so a stale or
-- reordered event can never bring an earlier courier back. Unassigning keeps the id; it is never reset.
-- The FK to offers crosses into the dispatch module, like orders.courier_id's FK to couriers; it stays until M5-4
-- splits the dispatch tables out (ADR-0003).
ALTER TABLE orders ADD COLUMN assigned_offer_id BIGINT REFERENCES offers (id);
