-- Level-1 edge auth (design: testing-api.md) freezes the caller's resolved
-- identity onto the event: the actor and the claims a Level-2 policy reads. The
-- raw credential (token, headers) is never persisted -- only what it resolved to.
ALTER TABLE event_log ADD COLUMN actor TEXT NULL;
--;;
ALTER TABLE event_log ADD COLUMN claims JSONB NULL;
