BEGIN;

ALTER TABLE "user" ADD COLUMN IF NOT EXISTS agent_status VARCHAR(32) NOT NULL DEFAULT 'offline';
ALTER TABLE "user" ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMP;
ALTER TABLE smslog ADD COLUMN IF NOT EXISTS provider_message_id VARCHAR(255);
ALTER TABLE smslog ADD COLUMN IF NOT EXISTS provider_error VARCHAR(2000);
ALTER TABLE smslog ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;

UPDATE smslog SET updated_at = created_at WHERE updated_at IS NULL;

CREATE INDEX IF NOT EXISTS ix_smslog_provider_message_id ON smslog (provider_message_id);

COMMIT;
