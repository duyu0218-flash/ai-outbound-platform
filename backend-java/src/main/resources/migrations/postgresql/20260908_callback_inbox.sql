-- Additive migration. Apply before enabling CALLBACK_INBOX_ENABLED.
CREATE TABLE IF NOT EXISTS callbackinboxpartition (
    id INTEGER PRIMARY KEY, pending_count INTEGER NOT NULL DEFAULT 0,
    pending_bytes INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_callback_capacity CHECK (pending_count >= 0 AND pending_bytes >= 0)
);
INSERT INTO callbackinboxpartition (id) SELECT generate_series(0, 63) ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS callbackinbox (
    id BIGSERIAL PRIMARY KEY, receipt_key VARCHAR(64) NOT NULL UNIQUE,
    partition_id INTEGER NOT NULL, call_id UUID NOT NULL, kind VARCHAR(32) NOT NULL,
    body_json VARCHAR NOT NULL, body_digest VARCHAR(64) NOT NULL, body_bytes INTEGER NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'pending', received_at TIMESTAMP NOT NULL,
    available_at TIMESTAMP NOT NULL, completed_at TIMESTAMP,
    attempts INTEGER NOT NULL DEFAULT 0, error_type VARCHAR(128) NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS ix_callback_ready ON callbackinbox(partition_id, state, id);
CREATE INDEX IF NOT EXISTS ix_callback_call_order ON callbackinbox(call_id, state, id);
CREATE INDEX IF NOT EXISTS ix_callback_age ON callbackinbox(state, received_at);
CREATE INDEX IF NOT EXISTS ix_callback_cleanup ON callbackinbox(state, completed_at);
CREATE TABLE IF NOT EXISTS callbackinboxworker (
    id VARCHAR(64) PRIMARY KEY, heartbeat_at TIMESTAMP NOT NULL,
    processed BIGINT NOT NULL DEFAULT 0, max_latency_ms DOUBLE PRECISION NOT NULL DEFAULT 0
);
