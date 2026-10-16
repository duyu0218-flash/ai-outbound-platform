BEGIN;

CREATE TABLE IF NOT EXISTS taskoutbox (
    id UUID PRIMARY KEY,
    tenant_id INTEGER NOT NULL REFERENCES tenant(id),
    task_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    payload_json TEXT NOT NULL DEFAULT '{}',
    state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 5,
    available_at TIMESTAMP NOT NULL,
    locked_at TIMESTAMP,
    last_error VARCHAR(2000) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_taskoutbox_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX IF NOT EXISTS ix_taskoutbox_tenant_id ON taskoutbox(tenant_id);
CREATE INDEX IF NOT EXISTS ix_taskoutbox_task_type ON taskoutbox(task_type);
CREATE INDEX IF NOT EXISTS ix_taskoutbox_aggregate_id ON taskoutbox(aggregate_id);
CREATE INDEX IF NOT EXISTS ix_taskoutbox_state ON taskoutbox(state);
CREATE INDEX IF NOT EXISTS ix_taskoutbox_available_at ON taskoutbox(available_at);

COMMIT;
