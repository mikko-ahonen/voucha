-- Receipt order per target node (decision D19): claim-pending refuses an event
-- while an older RECEIVED event targets the same node. That probe needs status
-- and the (received_at, event_id) order in the index, or a large backlog aimed
-- at one node makes every claim walk every event for it -- quadratic, as the
-- T6 benchmark showed (17 events/s draining 1,500).
CREATE INDEX ix_event_log_pending_order
    ON event_log (tree_id, target_node_id, status, received_at, event_id);
