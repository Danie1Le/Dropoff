-- The offer behind orders.courier_id, as a high-water mark (ticket #85). CourierAssigned writes the courier only for an
-- offer newer than this one, and CourierUnassigned clears the courier only for this exact offer, so a stale or
-- reordered event can never bring an earlier courier back. Unassigning keeps the id; it is never reset.
-- No FK: offers belongs to the dispatch module, and orders must not depend on it (ADR-0003, ADR-0008).
ALTER TABLE orders ADD COLUMN assigned_offer_id BIGINT;
