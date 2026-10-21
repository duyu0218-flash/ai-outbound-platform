-- V3 S1 protocol foundation only. Existing lock_compat reservations and
-- triggers remain the live authority; no application mode is switched here.
CREATE TABLE dial_v3_coordinator (
 id TINYINT NOT NULL PRIMARY KEY,
 owner VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NULL,
 epoch BIGINT NOT NULL DEFAULT 0,
 lease_until DATETIME(6) NULL,
 state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'RECOVERING',
 CONSTRAINT ck_v3_coordinator_id CHECK (id=1),
 CONSTRAINT ck_v3_coordinator_state CHECK (state IN ('RECOVERING','READY'))
) ENGINE=InnoDB;
INSERT INTO dial_v3_coordinator(id) VALUES(1);

-- Concurrent resources only. CPS windows and L/E daily policy are deliberately
-- not represented by a refundable concurrent-resource counter.
CREATE TABLE dial_v3_quota_scope (
 scope_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 hard_limit BIGINT NOT NULL,
 held BIGINT NOT NULL DEFAULT 0,
 revision BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT ck_v3_scope_bounds CHECK (hard_limit>=0 AND held>=0 AND held<=hard_limit)
) ENGINE=InnoDB;

CREATE TABLE dial_v3_quota_grant (
 grant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 coordinator_epoch BIGINT NOT NULL,
 grantee VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 grantee_epoch BIGINT NOT NULL,
 issued BIGINT NOT NULL,
 consumed BIGINT NOT NULL DEFAULT 0,
 not_before DATETIME(6) NOT NULL,
 valid_until DATETIME(6) NOT NULL,
 state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
 closed_at DATETIME(6) NULL,
 CONSTRAINT ck_v3_grant_counts CHECK (issued>0 AND consumed>=0 AND consumed<=issued),
 CONSTRAINT ck_v3_grant_window CHECK (not_before<valid_until),
 CONSTRAINT ck_v3_grant_state CHECK (state IN ('ALLOCATING','OPEN','CLOSED')),
 INDEX ix_v3_grant_expiry(state,valid_until)
) ENGINE=InnoDB;

CREATE TABLE dial_v3_quota_grant_scope (
 grant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 scope_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(grant_id,scope_key),
 INDEX ix_v3_grant_scope(scope_key,grant_id),
 CONSTRAINT fk_v3_grant_scope_grant FOREIGN KEY(grant_id) REFERENCES dial_v3_quota_grant(grant_id),
 CONSTRAINT fk_v3_grant_scope_scope FOREIGN KEY(scope_key) REFERENCES dial_v3_quota_scope(scope_key)
) ENGINE=InnoDB;

CREATE TABLE dial_v3_quota_allocation (
 allocation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 dial_key VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 grant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 active_key VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin
  GENERATED ALWAYS AS (CASE WHEN state='ACTIVE' THEN dial_key ELSE NULL END) STORED,
 release_proof VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NULL,
 created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
 released_at DATETIME(6) NULL,
 UNIQUE KEY uq_v3_active_dial_key(active_key),
 INDEX ix_v3_allocation_grant(grant_id,state),
 CONSTRAINT fk_v3_allocation_grant FOREIGN KEY(grant_id) REFERENCES dial_v3_quota_grant(grant_id),
 CONSTRAINT ck_v3_allocation_state CHECK (state IN ('ACTIVE','RELEASED')),
 CONSTRAINT ck_v3_release_proof CHECK ((state='ACTIVE' AND release_proof IS NULL AND released_at IS NULL)
  OR (state='RELEASED' AND release_proof IS NOT NULL AND released_at IS NOT NULL))
) ENGINE=InnoDB;
