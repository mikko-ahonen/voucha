-- A transition log row records what actually ran. When a dev-only Fault-tier
-- transform rewrote the input or output of a transition, the row is tagged here
-- naming the seam and interceptor, so an auditor sees "this was debug-injected"
-- rather than a fabricated-but-plausible checkpoint (design: testing-api.md).
ALTER TABLE node_transition_log
  ADD COLUMN debug_provenance JSONB NULL;
