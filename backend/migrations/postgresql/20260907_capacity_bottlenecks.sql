BEGIN;
ALTER TABLE gatewaynode ADD COLUMN IF NOT EXISTS next_dial_at TIMESTAMP;
CREATE TABLE IF NOT EXISTS taskreceipt (
    id UUID PRIMARY KEY,
    tenant_id INTEGER NOT NULL REFERENCES tenant(id),
    task_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    completed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS ix_taskreceipt_tenant_id ON taskreceipt(tenant_id);
CREATE INDEX IF NOT EXISTS ix_taskoutbox_archive ON taskoutbox(updated_at, id) WHERE state = 'COMPLETED';
CREATE INDEX IF NOT EXISTS ix_taskoutbox_active_stream ON taskoutbox(aggregate_id, task_type, created_at, id)
    WHERE state IN ('PENDING','FAILED','PROCESSING');
CREATE INDEX IF NOT EXISTS ix_taskoutbox_ready_tenant ON taskoutbox(task_type, tenant_id, available_at, id)
    WHERE state IN ('PENDING','FAILED');
CREATE INDEX IF NOT EXISTS ix_knowledgeitem_revision ON knowledgeitem(tenant_id, is_active, updated_at, version);
COMMIT;
