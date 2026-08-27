-- Production PostgreSQL upgrade for the 2026-08-28 hardening release.
-- Run only after taking a database backup and stopping write traffic.

BEGIN;

-- AI event names such as ai_start and ai_decision are extensible. Existing
-- deployments may still use the original PostgreSQL EventType enum.
ALTER TABLE callevent
    ALTER COLUMN event_type TYPE VARCHAR(64)
    USING event_type::text;

CREATE INDEX IF NOT EXISTS ix_callevent_event_type
    ON callevent (event_type);
CREATE INDEX IF NOT EXISTS ix_callsession_status
    ON callsession (status);
CREATE INDEX IF NOT EXISTS ix_callsession_updated_at
    ON callsession (updated_at);

COMMIT;
