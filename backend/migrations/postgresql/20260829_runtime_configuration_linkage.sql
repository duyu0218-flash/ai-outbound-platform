BEGIN;

ALTER TABLE callsession ADD COLUMN IF NOT EXISTS human_agent_id INTEGER;
ALTER TABLE callsession ADD COLUMN IF NOT EXISTS telephony_line_id INTEGER;
ALTER TABLE telephonyline ADD COLUMN IF NOT EXISTS priority INTEGER NOT NULL DEFAULT 100;
ALTER TABLE telephonyline ADD COLUMN IF NOT EXISTS weight INTEGER NOT NULL DEFAULT 1;
ALTER TABLE telephonyline ADD COLUMN IF NOT EXISTS credential_ref VARCHAR(128) NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS ix_callsession_telephony_line_id ON callsession (telephony_line_id);

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_callsession_human_agent_id') THEN
    ALTER TABLE callsession
      ADD CONSTRAINT fk_callsession_human_agent_id FOREIGN KEY (human_agent_id) REFERENCES "user"(id);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_callsession_telephony_line_id') THEN
    ALTER TABLE callsession
      ADD CONSTRAINT fk_callsession_telephony_line_id FOREIGN KEY (telephony_line_id) REFERENCES telephonyline(id);
  END IF;
END $$;

COMMIT;
