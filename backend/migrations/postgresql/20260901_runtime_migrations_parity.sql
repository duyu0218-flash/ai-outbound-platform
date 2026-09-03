-- Move the remaining additive runtime DDL into the versioned release path.

ALTER TABLE callsession ADD COLUMN IF NOT EXISTS human_agent_id INTEGER;
ALTER TABLE callsession ADD COLUMN IF NOT EXISTS telephony_line_id INTEGER;
ALTER TABLE callsession ADD COLUMN IF NOT EXISTS script_flow_version_id INTEGER;
ALTER TABLE callsession ADD COLUMN IF NOT EXISTS flow_node_key VARCHAR(128);
ALTER TABLE callsession ADD COLUMN IF NOT EXISTS voice_ai_pipeline VARCHAR(16) NOT NULL DEFAULT 'legacy';
ALTER TABLE callsession ADD COLUMN IF NOT EXISTS campaign_contact_key VARCHAR(128);

ALTER TABLE campaign ADD COLUMN IF NOT EXISTS script_flow_version_id INTEGER;
ALTER TABLE campaign ADD COLUMN IF NOT EXISTS voice_ai_pipeline VARCHAR(16) NOT NULL DEFAULT 'inherit';
ALTER TABLE campaign ADD COLUMN IF NOT EXISTS dispatch_enabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE realtimesession ADD COLUMN IF NOT EXISTS attempt INTEGER NOT NULL DEFAULT 0;

ALTER TABLE telephonyline ADD COLUMN IF NOT EXISTS priority INTEGER NOT NULL DEFAULT 100;
ALTER TABLE telephonyline ADD COLUMN IF NOT EXISTS weight INTEGER NOT NULL DEFAULT 1;
ALTER TABLE telephonyline ADD COLUMN IF NOT EXISTS credential_ref VARCHAR(128) NOT NULL DEFAULT '';

ALTER TABLE "user" ADD COLUMN IF NOT EXISTS agent_status VARCHAR(32) NOT NULL DEFAULT 'offline';
ALTER TABLE "user" ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMP;
ALTER TABLE "user" ADD COLUMN IF NOT EXISTS failed_login_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE "user" ADD COLUMN IF NOT EXISTS locked_until TIMESTAMP;
ALTER TABLE "user" ADD COLUMN IF NOT EXISTS token_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE "user" ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMP;

ALTER TABLE smslog ADD COLUMN IF NOT EXISTS provider_message_id VARCHAR(255);
ALTER TABLE smslog ADD COLUMN IF NOT EXISTS provider_error VARCHAR(2000);
ALTER TABLE smslog ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS ix_callsession_telephony_line_id ON callsession (telephony_line_id);
DROP INDEX IF EXISTS uq_callsession_campaign_contact;
CREATE UNIQUE INDEX IF NOT EXISTS ix_callsession_campaign_contact_key
    ON callsession (campaign_contact_key) WHERE campaign_contact_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_campaign_dispatch_enabled ON campaign (dispatch_enabled);
CREATE INDEX IF NOT EXISTS ix_realtimesession_attempt ON realtimesession (attempt);
CREATE INDEX IF NOT EXISTS ix_smslog_provider_message_id ON smslog (provider_message_id);

UPDATE campaign SET dispatch_enabled = TRUE WHERE status = 'running';
