-- Additive migration. Run on the managed primary before the new application.
BEGIN;
CREATE TABLE IF NOT EXISTS gatewaynode (
    id VARCHAR(64) PRIMARY KEY,
    endpoint VARCHAR(512) NOT NULL,
    capacity INTEGER NOT NULL DEFAULT 0,
    ready BOOLEAN NOT NULL DEFAULT FALSE,
    checked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE callsession ADD COLUMN IF NOT EXISTS gateway_node_id VARCHAR(64);
ALTER TABLE callsession ADD COLUMN IF NOT EXISTS gateway_endpoint VARCHAR(512);
CREATE INDEX IF NOT EXISTS ix_callsession_gateway_capacity ON callsession(gateway_node_id,status);
CREATE INDEX IF NOT EXISTS ix_taskoutbox_stream_order ON taskoutbox(aggregate_id,task_type,state,created_at,id);
COMMIT;
