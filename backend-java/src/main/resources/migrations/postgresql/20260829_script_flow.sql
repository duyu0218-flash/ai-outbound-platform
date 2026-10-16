CREATE TABLE IF NOT EXISTS scriptflowversion (
    id SERIAL PRIMARY KEY,
    tenant_id INTEGER NOT NULL REFERENCES tenant(id),
    script_template_id INTEGER NOT NULL REFERENCES scripttemplate(id),
    version INTEGER NOT NULL DEFAULT 1,
    name VARCHAR NOT NULL DEFAULT '',
    description VARCHAR NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL DEFAULT 'draft',
    graph_json VARCHAR NOT NULL DEFAULT '{}',
    created_by INTEGER REFERENCES "user"(id),
    published_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_script_flow_template_version UNIQUE (script_template_id, version)
);
CREATE INDEX IF NOT EXISTS ix_scriptflowversion_tenant_id ON scriptflowversion(tenant_id);
CREATE INDEX IF NOT EXISTS ix_scriptflowversion_script_template_id ON scriptflowversion(script_template_id);
CREATE INDEX IF NOT EXISTS ix_scriptflowversion_status ON scriptflowversion(status);
ALTER TABLE campaign ADD COLUMN IF NOT EXISTS script_flow_version_id INTEGER REFERENCES scriptflowversion(id);
ALTER TABLE callsession ADD COLUMN IF NOT EXISTS script_flow_version_id INTEGER REFERENCES scriptflowversion(id);
ALTER TABLE callsession ADD COLUMN IF NOT EXISTS flow_node_key VARCHAR(128);
