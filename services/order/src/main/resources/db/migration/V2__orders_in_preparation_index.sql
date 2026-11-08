-- The in-preparation gauges (M6-1, tickets #13, #34) count ACCEPTED and PREPARING orders every 15 s. A partial index
-- holds only those rows, so the query reads a handful of entries instead of scanning every order ever placed.
CREATE INDEX orders_in_preparation_idx ON orders (id) WHERE status IN ('ACCEPTED', 'PREPARING');
