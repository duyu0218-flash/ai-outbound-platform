CREATE TABLE msg_call_intent (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, call_id VARCHAR(64) NOT NULL, attempt INT NOT NULL,
 rule_id VARCHAR(64) NULL, message_id VARCHAR(64) NULL,
 state VARCHAR(32) NOT NULL DEFAULT 'WAITING_CALL', reason VARCHAR(256) NOT NULL DEFAULT '',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY msg_call_attempt(tenant_id,call_id,attempt), INDEX msg_call_due(state,created_at)
);
