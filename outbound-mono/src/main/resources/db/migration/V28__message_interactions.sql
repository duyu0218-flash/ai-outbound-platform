ALTER TABLE msg_message ADD COLUMN contact_id BIGINT NULL;
CREATE TABLE msg_interaction (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, application_id VARCHAR(64) NOT NULL,
 message_id VARCHAR(64) NOT NULL, contact_id BIGINT NOT NULL,
 event_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 business_event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
 request_hash CHAR(64) NOT NULL, kind VARCHAR(32) NOT NULL, source VARCHAR(32) NOT NULL,
 payload_cipher TEXT NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY msg_interaction_key(tenant_id,application_id,event_key),
 UNIQUE KEY msg_conversion_key(tenant_id,application_id,business_event_id),
 INDEX msg_interaction_tenant(tenant_id,created_at)
);
