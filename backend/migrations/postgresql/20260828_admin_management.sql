-- Production PostgreSQL upgrade for admin users, telephony lines, settings and audit logs.
-- Run after taking a backup and before deploying the matching application release.

BEGIN;

CREATE TABLE IF NOT EXISTS telephonyline (
    id SERIAL PRIMARY KEY,
    tenant_id INTEGER NOT NULL REFERENCES tenant(id),
    name VARCHAR(200) NOT NULL,
    provider VARCHAR(100) NOT NULL DEFAULT 'sip',
    gateway_url VARCHAR(1000) NOT NULL DEFAULT '',
    caller_id VARCHAR(64) NOT NULL DEFAULT '',
    max_concurrency INTEGER NOT NULL DEFAULT 10,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_telephonyline_tenant_name UNIQUE (tenant_id, name),
    CONSTRAINT ck_telephonyline_concurrency CHECK (max_concurrency >= 1 AND max_concurrency <= 10000)
);

CREATE INDEX IF NOT EXISTS ix_telephonyline_tenant_id ON telephonyline (tenant_id);
CREATE INDEX IF NOT EXISTS ix_telephonyline_name ON telephonyline (name);

CREATE TABLE IF NOT EXISTS adminsetting (
    id SERIAL PRIMARY KEY,
    tenant_id INTEGER NOT NULL REFERENCES tenant(id),
    section VARCHAR(64) NOT NULL,
    data_json TEXT NOT NULL DEFAULT '{}',
    updated_by INTEGER REFERENCES "user"(id),
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_adminsetting_tenant_section UNIQUE (tenant_id, section)
);

CREATE INDEX IF NOT EXISTS ix_adminsetting_tenant_id ON adminsetting (tenant_id);
CREATE INDEX IF NOT EXISTS ix_adminsetting_section ON adminsetting (section);

CREATE TABLE IF NOT EXISTS auditlog (
    id SERIAL PRIMARY KEY,
    tenant_id INTEGER NOT NULL REFERENCES tenant(id),
    actor_user_id INTEGER REFERENCES "user"(id),
    actor_username VARCHAR(200) NOT NULL DEFAULT 'system',
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(100) NOT NULL,
    resource_id VARCHAR(200),
    detail VARCHAR(4000) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_auditlog_tenant_id ON auditlog (tenant_id);
CREATE INDEX IF NOT EXISTS ix_auditlog_actor_username ON auditlog (actor_username);
CREATE INDEX IF NOT EXISTS ix_auditlog_action ON auditlog (action);
CREATE INDEX IF NOT EXISTS ix_auditlog_resource_type ON auditlog (resource_type);
CREATE INDEX IF NOT EXISTS ix_auditlog_created_at ON auditlog (created_at);

COMMIT;
