ALTER TABLE campaign
    ADD COLUMN IF NOT EXISTS voice_ai_pipeline VARCHAR(16) NOT NULL DEFAULT 'inherit';

ALTER TABLE callsession
    ADD COLUMN IF NOT EXISTS voice_ai_pipeline VARCHAR(16) NOT NULL DEFAULT 'legacy';

ALTER TABLE "user"
    ADD COLUMN IF NOT EXISTS failed_login_attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE "user"
    ADD COLUMN IF NOT EXISTS locked_until TIMESTAMP;

ALTER TABLE "user"
    ADD COLUMN IF NOT EXISTS token_version INTEGER NOT NULL DEFAULT 0;

ALTER TABLE "user"
    ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS ix_callsession_voice_ai_pipeline
    ON callsession (voice_ai_pipeline);

CREATE INDEX IF NOT EXISTS ix_user_locked_until
    ON "user" (locked_until);
