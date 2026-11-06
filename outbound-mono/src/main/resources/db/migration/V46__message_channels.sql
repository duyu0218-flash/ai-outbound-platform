CREATE TABLE msg_channel (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, name VARCHAR(128) NOT NULL,
 protocol VARCHAR(16) NOT NULL, environment VARCHAR(16) NOT NULL DEFAULT 'test',
 state VARCHAR(16) NOT NULL DEFAULT 'DRAFT', verification VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
 forms JSON NOT NULL, config JSON NOT NULL, credential_cipher TEXT NOT NULL,
 verification_note VARCHAR(512) NULL, verified_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX msg_channel_owner(tenant_id,created_at,id)
);
CREATE TABLE msg_channel_binding (
 tenant_id BIGINT NOT NULL, channel_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 resource_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 kind VARCHAR(16) NOT NULL, provider_ref VARCHAR(128) NOT NULL,
 state VARCHAR(24) NOT NULL DEFAULT 'PENDING', evidence VARCHAR(512) NOT NULL DEFAULT '',
 expires_at DATETIME(6) NULL, updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(channel_id,resource_id), INDEX msg_binding_owner(tenant_id,resource_id)
);
CREATE TABLE msg_asset (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, tenant_id BIGINT NOT NULL,
 name VARCHAR(128) NOT NULL, mime VARCHAR(64) NOT NULL, size_bytes BIGINT NOT NULL,
 sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'READY', expires_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), INDEX msg_asset_owner(tenant_id,created_at,id)
);
CREATE TABLE msg_delivery (
 message_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, channel_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(24) NOT NULL DEFAULT 'SUBMITTING', provider_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 part_count INT NOT NULL DEFAULT 0, query_count INT NOT NULL DEFAULT 0,
 next_query_at DATETIME(6) NULL, last_error VARCHAR(64) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX msg_delivery_owner(tenant_id,created_at,message_id),
 INDEX msg_delivery_query(state,next_query_at), UNIQUE KEY msg_delivery_provider(channel_id,provider_id)
);
CREATE TABLE msg_delivery_part (
 message_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 channel_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 part_no INT NOT NULL, provider_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL, state VARCHAR(24) NOT NULL,
 PRIMARY KEY(message_id,part_no), UNIQUE KEY msg_part_provider(channel_id,provider_id)
);
CREATE TABLE msg_provider_event (
 channel_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 event_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 tenant_id BIGINT NOT NULL, message_id VARCHAR(64) NULL, provider_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 kind VARCHAR(24) NOT NULL, state VARCHAR(32) NOT NULL,
 payload_cipher TEXT NOT NULL, request_hash CHAR(64) NOT NULL,
 applied BOOLEAN NOT NULL DEFAULT FALSE, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(channel_id,event_key), INDEX msg_provider_early(channel_id,provider_id,applied),
 INDEX msg_provider_owner(tenant_id,message_id,created_at)
);
CREATE TABLE msg_channel_window (
 channel_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 window_key VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, used BIGINT NOT NULL,
 expires_at DATETIME(6) NOT NULL, PRIMARY KEY(channel_id,window_key)
);
CREATE TABLE msg_cost_event (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, channel_id VARCHAR(64) NOT NULL,
 message_id VARCHAR(64) NOT NULL, event_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 amount BIGINT NOT NULL, units INT NOT NULL, source VARCHAR(24) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY msg_cost_idem(channel_id,event_key), INDEX msg_cost_owner(tenant_id,created_at,id)
);
CREATE TABLE msg_reconcile_batch (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, channel_id VARCHAR(64) NOT NULL,
 external_ref VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) NOT NULL, total_amount BIGINT NOT NULL, item_count INT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY msg_bill_idem(channel_id,external_ref), INDEX msg_bill_owner(tenant_id,created_at,id)
);
CREATE TABLE msg_reconcile_item (
 batch_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, item_no INT NOT NULL,
 message_id VARCHAR(64) NULL, provider_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 billed_amount BIGINT NOT NULL, recorded_amount BIGINT NULL, state VARCHAR(24) NOT NULL,
 PRIMARY KEY(batch_id,item_no)
);
ALTER TABLE msg_message
 ADD COLUMN channel_id VARCHAR(64) NULL,
 ADD COLUMN simulated BOOLEAN NOT NULL DEFAULT TRUE,
 ADD COLUMN content_cipher MEDIUMTEXT NULL,
 ADD COLUMN billable_units INT NOT NULL DEFAULT 1,
 ADD COLUMN billing_basis VARCHAR(16) NOT NULL DEFAULT 'delivered',
 ADD COLUMN settled_amount BIGINT NOT NULL DEFAULT 0;
UPDATE msg_message SET settled_amount=sales_amount WHERE billing_state IN ('SETTLED','REFUNDED');
ALTER TABLE msg_batch ADD COLUMN simulated BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE msg_application
 ADD COLUMN previous_secret_cipher TEXT NULL,
 ADD COLUMN previous_secret_until DATETIME(6) NULL,
 ADD COLUMN rate_per_minute INT NOT NULL DEFAULT 120,
 ADD COLUMN daily_limit INT NOT NULL DEFAULT 10000,
 ADD COLUMN daily_budget BIGINT NOT NULL DEFAULT 1000000000;
INSERT INTO mk_sms_status_map(tenant_id,provider,status_code,outcome,note)
 VALUES(0,'*','platform:FAILED','SMS_FAIL_TEMP','平台消息明确发送失败');
