BEGIN;
ALTER TABLE recordingasset ADD COLUMN IF NOT EXISTS attempt INTEGER NOT NULL DEFAULT 0;
ALTER TABLE callanalysis ADD COLUMN IF NOT EXISTS automatic_result_json TEXT NOT NULL DEFAULT '{}';
ALTER TABLE callanalysis ADD COLUMN IF NOT EXISTS needs_review BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE realtimesession ADD COLUMN IF NOT EXISTS last_event_sequence BIGINT;
CREATE TABLE IF NOT EXISTS callusage (
 id SERIAL PRIMARY KEY, tenant_id INTEGER NOT NULL REFERENCES tenant(id),
 call_session_id UUID NOT NULL REFERENCES callsession(id), attempt INTEGER NOT NULL,
 answered_at TIMESTAMP, ended_at TIMESTAMP, ai_ended_at TIMESTAMP,
 telephony_seconds DOUBLE PRECISION, ai_seconds DOUBLE PRECISION,
 duration_source VARCHAR NOT NULL DEFAULT 'missing', created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT uq_callusage_attempt UNIQUE(call_session_id, attempt)
);
CREATE INDEX IF NOT EXISTS ix_callusage_tenant_id ON callusage(tenant_id);
CREATE INDEX IF NOT EXISTS ix_callusage_call_session_id ON callusage(call_session_id);
COMMIT;
