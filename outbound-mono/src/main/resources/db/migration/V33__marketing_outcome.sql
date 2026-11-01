-- P3 call outcome classification (see MARKETING-ARCHITECTURE.md, MARKETING-P3-ACCEPTANCE.md).
-- One row per call: three layers (connection / answer quality / intent), the automatic codes kept
-- beside the effective codes so manual corrections never lose the original verdict.
CREATE TABLE mk_call_outcome (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 call_id CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
 attempt INT NOT NULL DEFAULT 0,
 campaign_id BIGINT NULL,
 phone_hash CHAR(64) CHARACTER SET ascii NULL,
 -- CLASSIFIED, or SKIPPED with skip_reason (no master key, invalid phone, classification error)
 state VARCHAR(16) NOT NULL,
 skip_reason VARCHAR(255) NULL,
 connection_code VARCHAR(48) NULL, answer_code VARCHAR(48) NULL, intent_code VARCHAR(48) NULL,
 auto_connection_code VARCHAR(48) NULL, auto_answer_code VARCHAR(48) NULL,
 auto_intent_code VARCHAR(48) NULL,
 connection_confidence DECIMAL(4,3) NULL, answer_confidence DECIMAL(4,3) NULL,
 intent_confidence DECIMAL(4,3) NULL, confidence DECIMAL(4,3) NULL,
 counts_against_user BOOLEAN NOT NULL DEFAULT TRUE,
 rule_version INT NOT NULL DEFAULT 0,
 evidence JSON NOT NULL,
 needs_review BOOLEAN NOT NULL DEFAULT FALSE,
 review_reason VARCHAR(255) NULL,
 -- NONE, PENDING, CONFIRMED (reviewer agreed), CORRECTED (reviewer overrode at least one layer)
 review_state VARCHAR(16) NOT NULL DEFAULT 'NONE',
 review_note VARCHAR(500) NULL,
 reviewed_by BIGINT NULL, reviewed_at DATETIME(3) NULL,
 call_finished_at DATETIME(3) NULL,
 classified_at DATETIME(3) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 UNIQUE KEY mk_call_outcome_call(call_id),
 INDEX mk_call_outcome_finished(tenant_id,call_finished_at),
 INDEX mk_call_outcome_campaign(tenant_id,campaign_id,call_finished_at),
 INDEX mk_call_outcome_review(tenant_id,review_state,needs_review)
);

-- Per-tenant versioned thresholds and phrase libraries. Version 0 is the built-in default and is
-- never stored; the highest PUBLISHED version is active. Drafts are edited until published.
CREATE TABLE mk_outcome_rule (
 tenant_id BIGINT NOT NULL, version INT NOT NULL,
 state VARCHAR(16) NOT NULL,
 config JSON NOT NULL, note VARCHAR(500) NULL,
 created_by BIGINT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 published_at DATETIME(6) NULL,
 PRIMARY KEY (tenant_id,version),
 INDEX mk_outcome_rule_state(tenant_id,state,version)
);

-- Manual correction log; mk_call_outcome holds the effective result, this keeps every decision.
CREATE TABLE mk_outcome_review (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 call_id CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
 layer VARCHAR(16) NOT NULL,
 auto_code VARCHAR(48) NULL, previous_code VARCHAR(48) NULL, corrected_code VARCHAR(48) NULL,
 note VARCHAR(500) NULL, reviewer BIGINT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX mk_outcome_review_call(tenant_id,call_id)
);

-- Cross-tenant scan position of the outcome worker over callsession(updated_at,id).
CREATE TABLE mk_outcome_watermark (
 name VARCHAR(64) PRIMARY KEY,
 last_updated_at DATETIME(3) NOT NULL,
 last_call_id CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
);
