-- Marketing P1: data packages, partner matching, segments and snapshot sends.
-- No clear-text phone is stored anywhere: phone_hash = PhoneIdentity.hash (msg recipient hash),
-- phone_cipher = PhoneIdentity.encrypt, phone_mask = 138****8000.

-- A data package; each upload becomes a new version.
CREATE TABLE mk_dataset (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, name VARCHAR(128) NOT NULL, description VARCHAR(512) NOT NULL DEFAULT '',
 created_by BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX mk_dataset_tenant(tenant_id,created_at)
);

-- state: importing -> ready | failed. authorization_ref is required before matching.
CREATE TABLE mk_dataset_version (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 dataset_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 version_no INT NOT NULL, file_name VARCHAR(255) NOT NULL DEFAULT '',
 encoding VARCHAR(16) NOT NULL, header JSON NOT NULL,
 authorization_ref VARCHAR(128) NULL, source VARCHAR(64) NOT NULL DEFAULT '',
 state VARCHAR(16) NOT NULL, error VARCHAR(512) NULL,
 total_rows INT NOT NULL DEFAULT 0, chunk_total INT NOT NULL DEFAULT 0, chunk_done INT NOT NULL DEFAULT 0,
 valid_count INT NOT NULL DEFAULT 0, invalid_count INT NOT NULL DEFAULT 0,
 duplicate_count INT NOT NULL DEFAULT 0, blocked_count INT NOT NULL DEFAULT 0,
 created_by BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), finished_at DATETIME(6) NULL,
 UNIQUE KEY mk_dataset_version_no(tenant_id,dataset_id,version_no),
 INDEX mk_dataset_version_state(state,created_at)
);

-- Upload staging: parsed records, encrypted per chunk, cleared once the chunk is imported.
-- A chunk commits together with its rows, so an interrupted import resumes at the next chunk.
CREATE TABLE mk_dataset_chunk (
 version_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 chunk_no INT NOT NULL, tenant_id BIGINT NOT NULL,
 first_line INT NOT NULL, cipher MEDIUMTEXT NULL, state VARCHAR(16) NOT NULL,
 PRIMARY KEY(version_id,chunk_no), INDEX mk_dataset_chunk_state(state,version_id)
);

-- status: valid | invalid | duplicate. blocked_reason is the ContactPolicy denial at import time.
-- unique_hash is set only on the first valid occurrence, which makes deduplication a key.
CREATE TABLE mk_dataset_row (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 version_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 dataset_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 line_no INT NOT NULL, status VARCHAR(16) NOT NULL, reason VARCHAR(128) NOT NULL DEFAULT '',
 phone_hash CHAR(64) CHARACTER SET ascii NULL, unique_hash CHAR(64) CHARACTER SET ascii NULL,
 phone_cipher VARCHAR(512) CHARACTER SET ascii NULL, phone_mask VARCHAR(32) NOT NULL DEFAULT '',
 region VARCHAR(64) NULL, carrier VARCHAR(32) NULL, source VARCHAR(64) NULL,
 attrs JSON NOT NULL, blocked_reason VARCHAR(128) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY mk_dataset_row_line(version_id,line_no),
 UNIQUE KEY mk_dataset_row_unique(version_id,unique_hash),
 INDEX mk_dataset_row_phone(tenant_id,phone_hash),
 INDEX mk_dataset_row_status(version_id,status,id)
);

-- Joint-modelling partners. adapter: mock | ant_das | hash_file.
CREATE TABLE mk_partner (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, code VARCHAR(64) NOT NULL, name VARCHAR(128) NOT NULL,
 adapter VARCHAR(32) NOT NULL, product VARCHAR(64) NOT NULL, config JSON NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY mk_partner_code(tenant_id,code)
);

-- state: queued | running | paused | awaiting_file | finished | failed.
CREATE TABLE mk_match_job (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 partner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 adapter VARCHAR(32) NOT NULL, product VARCHAR(64) NOT NULL,
 version_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 authorization_ref VARCHAR(128) NULL, origin VARCHAR(16) NOT NULL DEFAULT 'dataset',
 top_n INT NOT NULL DEFAULT 3, state VARCHAR(16) NOT NULL, pause_reason VARCHAR(512) NULL,
 total INT NOT NULL DEFAULT 0,
 created_by BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), finished_at DATETIME(6) NULL,
 INDEX mk_match_job_tenant(tenant_id,created_at),
 INDEX mk_match_job_state(state,created_at)
);

-- One paid call per row: reserved -> pending (CAS, the only permission to send)
-- -> success | no_data | failed | uncertain; cached when a valid result is reused.
-- Pending and uncertain requests are never sent again automatically.
-- The normalized result is copied here so later cache refreshes do not rewrite past jobs.
CREATE TABLE mk_match_request (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 job_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 position INT NOT NULL, row_id BIGINT NULL,
 phone_hash CHAR(64) CHARACTER SET ascii NOT NULL,
 phone_cipher VARCHAR(512) CHARACTER SET ascii NOT NULL, phone_mask VARCHAR(32) NOT NULL,
 state VARCHAR(16) NOT NULL, code VARCHAR(32) NOT NULL DEFAULT '',
 message VARCHAR(255) NOT NULL DEFAULT '', request_id VARCHAR(128) NOT NULL DEFAULT '',
 charged BOOLEAN NOT NULL DEFAULT FALSE,
 hit BOOLEAN NULL, score DECIMAL(5,2) NULL, grade CHAR(1) NULL,
 best_hours JSON NULL, labels JSON NULL,
 sent_at DATETIME(6) NULL, finished_at DATETIME(6) NULL,
 UNIQUE KEY mk_match_request_position(job_id,position),
 UNIQUE KEY mk_match_request_phone(job_id,phone_hash),
 INDEX mk_match_request_state(job_id,state,position),
 INDEX mk_match_request_lookup(tenant_id,phone_hash)
);

-- 30-day cache per partner + product + number; a cached hit is never charged again.
CREATE TABLE mk_match_result (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 partner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 product VARCHAR(64) NOT NULL, phone_hash CHAR(64) CHARACTER SET ascii NOT NULL,
 hit BOOLEAN NOT NULL, score DECIMAL(5,2) NULL, grade CHAR(1) NULL,
 best_hours JSON NOT NULL, labels JSON NOT NULL, raw JSON NOT NULL,
 source VARCHAR(16) NOT NULL, request_id VARCHAR(128) NOT NULL DEFAULT '',
 matched_at DATETIME(6) NOT NULL, expires_at DATETIME(6) NOT NULL,
 UNIQUE KEY mk_match_result_key(tenant_id,partner_id,product,phone_hash)
);

CREATE TABLE mk_segment (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, name VARCHAR(128) NOT NULL, rules JSON NOT NULL,
 created_by BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 INDEX mk_segment_tenant(tenant_id,created_at)
);

-- A frozen copy of the rule result; control_percent members are held out (grp='control').
CREATE TABLE mk_segment_snapshot (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 segment_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 name VARCHAR(160) NOT NULL, rules JSON NOT NULL, control_percent DECIMAL(5,2) NOT NULL DEFAULT 0,
 size INT NOT NULL DEFAULT 0, test_size INT NOT NULL DEFAULT 0, control_size INT NOT NULL DEFAULT 0,
 created_by BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX mk_snapshot_segment(tenant_id,segment_id,created_at)
);

CREATE TABLE mk_snapshot_member (
 snapshot_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 sequence BIGINT NOT NULL, tenant_id BIGINT NOT NULL,
 phone_hash CHAR(64) CHARACTER SET ascii NOT NULL,
 phone_cipher VARCHAR(512) CHARACTER SET ascii NOT NULL, phone_mask VARCHAR(32) NOT NULL,
 contact_id BIGINT NULL, grp VARCHAR(8) NOT NULL,
 PRIMARY KEY(snapshot_id,sequence),
 UNIQUE KEY mk_snapshot_member_phone(snapshot_id,phone_hash)
);

-- Bulk AI-call or SMS task created from a snapshot. state: running | paused | finished | cancelled.
CREATE TABLE mk_send_task (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 snapshot_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 channel VARCHAR(8) NOT NULL, campaign_id BIGINT NULL, config JSON NOT NULL,
 state VARCHAR(16) NOT NULL, cursor_sequence BIGINT NOT NULL DEFAULT 0,
 total INT NOT NULL DEFAULT 0, processed INT NOT NULL DEFAULT 0,
 accepted INT NOT NULL DEFAULT 0, rejected INT NOT NULL DEFAULT 0, held_out INT NOT NULL DEFAULT 0,
 error VARCHAR(512) NULL, created_by BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), finished_at DATETIME(6) NULL,
 INDEX mk_send_task_snapshot(tenant_id,snapshot_id),
 INDEX mk_send_task_state(state,created_at)
);

-- state: claimed -> accepted | rejected | held_out. Re-processing a claimed item is safe: SMS
-- uses an idempotency key and calls a unique campaign_contact_key.
CREATE TABLE mk_send_item (
 task_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 sequence BIGINT NOT NULL, tenant_id BIGINT NOT NULL,
 phone_mask VARCHAR(32) NOT NULL, state VARCHAR(16) NOT NULL,
 reason VARCHAR(255) NOT NULL DEFAULT '',
 target_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 PRIMARY KEY(task_id,sequence), INDEX mk_send_item_state(task_id,state)
);
