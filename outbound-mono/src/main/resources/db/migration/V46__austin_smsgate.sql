-- Dispatch claimed before any TCP submission. A crashed/unknown dispatch is never resent.
CREATE TABLE msg_sms_dispatch (
 message_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 state VARCHAR(32) NOT NULL DEFAULT 'SUBMITTING',
 part_count INT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX msg_sms_dispatch_tenant(tenant_id,created_at)
);
CREATE TABLE msg_sms_part (
 message_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 part_no INT NOT NULL,
 provider_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
 state VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
 PRIMARY KEY(message_id,part_no),
 UNIQUE KEY msg_sms_provider_id(provider_id)
);
-- Retain early reports until SUBMIT_RESP correlation becomes available.
CREATE TABLE msg_sms_report (
 provider_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 state VARCHAR(32) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
-- Existing messages and batches were exclusively mock; snapshot transport mode for retention-safe queries.
ALTER TABLE msg_message ADD COLUMN simulated BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE msg_batch ADD COLUMN simulated BOOLEAN NOT NULL DEFAULT TRUE;
-- Failed multipart delivery is a final marketing outcome, not an unreported send.
INSERT INTO mk_sms_status_map(tenant_id,provider,status_code,outcome,note)
 VALUES(0,'*','platform:FAILED','SMS_FAIL_TEMP','平台状态：短信分片发送失败');
