-- Persistent retry scheduling for outbound call attempts.
-- Run after taking a backup and before deploying the matching application release.

BEGIN;

ALTER TABLE callsession
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS ix_callsession_next_attempt_at
    ON callsession (next_attempt_at);

COMMIT;
