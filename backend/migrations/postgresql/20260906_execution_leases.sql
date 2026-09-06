BEGIN;
ALTER TABLE taskoutbox ADD COLUMN IF NOT EXISTS lease_token VARCHAR(64);
ALTER TABLE speechturn ADD COLUMN IF NOT EXISTS attempt INTEGER NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS ix_speechturn_call_attempt ON speechturn(call_session_id, attempt, created_at);
CREATE INDEX IF NOT EXISTS ix_taskoutbox_ready_type ON taskoutbox(task_type, state, available_at);
CREATE INDEX IF NOT EXISTS ix_callsession_tenant_phone_started ON callsession(tenant_id, phone, started_at);
COMMIT;
