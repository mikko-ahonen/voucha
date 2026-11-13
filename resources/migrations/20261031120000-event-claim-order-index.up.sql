-- The claim takes the oldest eligible event: pending, with no older pending event for
-- the same node. Ordered by (status, received_at) alone, the planner adds an incremental
-- sort on event_id and must find a second eligible row to close each sort group; with a
-- backlog aimed at one node only one row per node is eligible, so every claim probed the
-- whole backlog (docs/design/performance.md: 113 ms at 5,000 pending, quadratic to drain).
-- In the claim's full order the index scan is the order, and the limit stops at the first
-- eligible row.
CREATE INDEX ix_event_log_claim_order ON event_log (status, received_at, event_id);
