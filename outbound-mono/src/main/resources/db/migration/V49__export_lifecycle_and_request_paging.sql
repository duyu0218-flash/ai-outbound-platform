ALTER TABLE ops_export_job
 ADD COLUMN storage_key VARCHAR(240) NULL,
 ADD COLUMN content_bytes BIGINT NULL,
 ADD COLUMN expires_at DATETIME(3) NULL,
 ADD COLUMN cleanup_after DATETIME(3) NULL,
 ADD KEY export_tenant_queue(state,tenant_id,created_at,id),
 ADD KEY export_owner_queue(tenant_id,user_id,state),
 ADD KEY export_expiry(state,expires_at);
-- Existing private BLOB downloads remain available for a seven-day migration grace period.
UPDATE ops_export_job SET expires_at=UTC_TIMESTAMP(3)+INTERVAL 7 DAY WHERE state='ready';
ALTER TABLE platform_document ADD KEY document_history(tenant_id,kind,updated_at,object_id);
