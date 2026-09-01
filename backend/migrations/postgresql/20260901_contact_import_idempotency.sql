CREATE TABLE IF NOT EXISTS contactimportjob (
    id UUID PRIMARY KEY,
    tenant_id INTEGER NOT NULL REFERENCES tenant(id),
    request_key VARCHAR(128) NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'processing',
    result_json TEXT NOT NULL DEFAULT '{}',
    last_error VARCHAR(2000) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_contact_import_tenant_key UNIQUE (tenant_id, request_key)
);

CREATE INDEX IF NOT EXISTS ix_contactimportjob_tenant_id ON contactimportjob (tenant_id);
CREATE INDEX IF NOT EXISTS ix_contactimportjob_request_key ON contactimportjob (request_key);
CREATE INDEX IF NOT EXISTS ix_contactimportjob_state ON contactimportjob (state);
