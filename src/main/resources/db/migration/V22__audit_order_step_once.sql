-- Each step of an order happens once, so it is recorded once, even if its event is delivered again (retries).
-- Replaces the plain order index, which this one covers.
CREATE UNIQUE INDEX audit_entry_order_step_once ON audit_entry (order_id, action) WHERE order_id IS NOT NULL;
DROP INDEX audit_entry_order_idx;
