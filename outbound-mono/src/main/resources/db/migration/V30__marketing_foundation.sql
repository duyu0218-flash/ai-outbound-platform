-- Marketing domain foundation shared by every module (see MARKETING-ARCHITECTURE.md).
-- Version allocation: V31 datasets/matching/segments, V32 short links,
-- V33 outcome classification, V34 journeys/profiles/dashboard.
-- Phones are never stored in clear text: phone_hash = MessageSecrets.hash(tenant, E.164),
-- identical to msg_message.recipient_hash, so marketing rows join messaging rows directly.

-- Who was sent what, from which segment snapshot / journey step. Written when a send or a call
-- is created from marketing; read when delivery reports, clicks and call outcomes come back.
CREATE TABLE mk_dispatch (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, channel VARCHAR(16) NOT NULL,
 target_type VARCHAR(32) NOT NULL, target_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 phone_hash CHAR(64) CHARACTER SET ascii NOT NULL, contact_id BIGINT NULL,
 snapshot_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 journey_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 journey_member_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY mk_dispatch_target(tenant_id,target_type,target_id),
 INDEX mk_dispatch_phone(tenant_id,phone_hash,created_at),
 INDEX mk_dispatch_snapshot(tenant_id,snapshot_id),
 INDEX mk_dispatch_journey(tenant_id,journey_id)
);

-- Append-only, idempotent touch facts from every channel (codes in OutcomeCodes).
CREATE TABLE mk_touch_event (
 sequence BIGINT AUTO_INCREMENT PRIMARY KEY,
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 tenant_id BIGINT NOT NULL, phone_hash CHAR(64) CHARACTER SET ascii NOT NULL,
 contact_id BIGINT NULL, channel VARCHAR(16) NOT NULL, code VARCHAR(48) NOT NULL,
 source_type VARCHAR(32) NOT NULL,
 source_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 dispatch_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 snapshot_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 journey_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 journey_member_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 detail JSON NOT NULL, occurred_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY mk_touch_source(tenant_id,source_type,source_id,code),
 INDEX mk_touch_phone(tenant_id,phone_hash,occurred_at),
 INDEX mk_touch_code(tenant_id,code,occurred_at),
 INDEX mk_touch_snapshot(tenant_id,snapshot_id,code),
 INDEX mk_touch_journey(tenant_id,journey_id,code)
);
