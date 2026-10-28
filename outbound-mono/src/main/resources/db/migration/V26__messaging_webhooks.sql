CREATE TABLE msg_webhook (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, application_id VARCHAR(64) NOT NULL,
 idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) NOT NULL,
 url VARCHAR(2048) NOT NULL, secret_cipher TEXT NOT NULL, challenge VARCHAR(64) NOT NULL,
 state VARCHAR(32) NOT NULL DEFAULT 'PENDING_VERIFICATION',
 event_types JSON NOT NULL, cursor_sequence BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY msg_hook_idem(tenant_id,application_id,idempotency_key),
 INDEX msg_hook_scope(tenant_id,application_id)
);
CREATE TABLE msg_webhook_delivery (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, subscription_id VARCHAR(64) NOT NULL,
 event_id VARCHAR(64) NOT NULL, payload JSON NOT NULL,
 state VARCHAR(32) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
 lease_token VARCHAR(64) NULL, last_status INT NULL,
 next_run_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY msg_hook_event(subscription_id,event_id), INDEX msg_hook_due(state,next_run_at)
);
