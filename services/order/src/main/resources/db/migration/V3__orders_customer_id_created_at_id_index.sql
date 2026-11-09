-- The customer order list (M2-6) sorts by (created_at DESC, id DESC). The old index stopped at created_at, so a deep
-- page read every one of the customer's orders from the table and sorted them (M7-3, docs/perf/README.md). With id in
-- the index, a backward index-only scan returns the ids in list order, which the deferred join in
-- OrderListRepository.findForCustomer uses to skip the offset, and the count reads the index too. It replaces the old
-- one: customer_id leads both, so nothing else needs the narrower index.
CREATE INDEX orders_customer_id_created_at_id_idx ON orders (customer_id, created_at, id);
DROP INDEX orders_customer_id_idx;
