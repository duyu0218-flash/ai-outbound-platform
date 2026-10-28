-- Independent messages: no call_id dependency. Amounts are integer micro-CNY; sandbox ledger only.
CREATE TABLE msg_resource (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, kind VARCHAR(32) NOT NULL, name VARCHAR(128) NOT NULL,
 state VARCHAR(32) NOT NULL DEFAULT 'DRAFT', payload JSON NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX msg_resource_tenant(tenant_id,kind,created_at)
);
CREATE TABLE msg_application (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, name VARCHAR(128) NOT NULL,
 secret_cipher TEXT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 grants JSON NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX msg_application_tenant(tenant_id)
);
CREATE TABLE msg_nonce (
 application_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 nonce VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expires_at DATETIME(6) NOT NULL, PRIMARY KEY(application_id,nonce), INDEX msg_nonce_expiry(expires_at)
);
CREATE TABLE msg_account (
 tenant_id BIGINT PRIMARY KEY, available BIGINT NOT NULL DEFAULT 0,
 frozen BIGINT NOT NULL DEFAULT 0, spent BIGINT NOT NULL DEFAULT 0,
 CHECK(available>=0 AND frozen>=0 AND spent>=0)
);
CREATE TABLE msg_message (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, application_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) CHARACTER SET ascii NOT NULL,
 product_id VARCHAR(64) NOT NULL, template_id VARCHAR(64) NOT NULL, sender_id VARCHAR(64) NOT NULL,
 campaign_id VARCHAR(64) NULL, call_id VARCHAR(64) NULL,
 recipient_cipher TEXT NOT NULL, recipient_hash CHAR(64) NOT NULL, recipient_mask VARCHAR(64) NOT NULL,
 payload JSON NOT NULL, state VARCHAR(32) NOT NULL,
 requested_form VARCHAR(16) NOT NULL, actual_transport VARCHAR(32) NULL,
 billing_state VARCHAR(32) NOT NULL DEFAULT 'HELD', sales_amount BIGINT NOT NULL,
 purchase_amount BIGINT NULL, price_version VARCHAR(64) NOT NULL,
 scheduled_at DATETIME(6) NOT NULL, expires_at DATETIME(6) NOT NULL,
 submitted_at DATETIME(6) NULL, finished_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY msg_idempotency(tenant_id,application_id,idempotency_key),
 INDEX msg_due(state,scheduled_at), INDEX msg_tenant(tenant_id,created_at,id),
 INDEX msg_frequency(tenant_id,recipient_hash,created_at)
);
CREATE TABLE msg_attempt (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, message_id VARCHAR(64) NOT NULL,
 attempt_no INT NOT NULL, state VARCHAR(32) NOT NULL, adapter VARCHAR(32) NOT NULL,
 provider_id VARCHAR(128) NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY msg_attempt_no(message_id,attempt_no)
);
CREATE TABLE msg_event (
 sequence BIGINT AUTO_INCREMENT PRIMARY KEY,
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 tenant_id BIGINT NOT NULL, application_id VARCHAR(64) NOT NULL,
 message_id VARCHAR(64) NOT NULL, type VARCHAR(64) NOT NULL, payload JSON NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX msg_event_scope(tenant_id,application_id,sequence), INDEX msg_event_message(tenant_id,message_id,sequence)
);
CREATE TABLE msg_journal (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, operation_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 message_id VARCHAR(64) NULL, kind VARCHAR(32) NOT NULL,
 available_delta BIGINT NOT NULL, frozen_delta BIGINT NOT NULL, spent_delta BIGINT NOT NULL, clearing_delta BIGINT NOT NULL,
 reason VARCHAR(256) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY msg_journal_operation(tenant_id,operation_key),
 CHECK(available_delta+frozen_delta+spent_delta+clearing_delta=0)
);
CREATE TABLE msg_suppression (
 tenant_id BIGINT NOT NULL, recipient_hash CHAR(64) NOT NULL,
 reason VARCHAR(256) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,recipient_hash)
);
CREATE TABLE msg_inbox (
 tenant_id BIGINT NOT NULL, adapter VARCHAR(32) NOT NULL,
 event_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 message_id VARCHAR(64) NOT NULL, payload JSON NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,adapter,event_key)
);
CREATE TABLE msg_batch (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, application_id VARCHAR(64) NOT NULL,
 idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) NOT NULL, payload JSON NOT NULL,
 state VARCHAR(32) NOT NULL DEFAULT 'QUEUED', results JSON NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY msg_batch_idem(tenant_id,application_id,idempotency_key), INDEX msg_batch_due(state,created_at)
);
