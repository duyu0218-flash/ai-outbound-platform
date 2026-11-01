-- Module P4: journeys, cross-channel frequency control, user profiles and the funnel dashboard.
-- Reads only mk_touch_event / mk_dispatch from other modules (see MARKETING-ARCHITECTURE.md).
-- Times in these tables are application-local (the JVM zone, like mk_touch_event.occurred_at)
-- and are always written explicitly by the application clock.

-- Per-tenant admission, pricing and lifecycle settings; absent row = documented defaults.
CREATE TABLE mk_policy_config (
 tenant_id BIGINT PRIMARY KEY,
 timezone VARCHAR(64) NOT NULL,
 window_start CHAR(5) NOT NULL, window_end CHAR(5) NOT NULL,
 sms_daily_cap INT NOT NULL, sms_weekly_cap INT NOT NULL,
 call_daily_cap INT NOT NULL, call_weekly_cap INT NOT NULL,
 sms_call_gap_minutes INT NOT NULL,
 sms_unit_price DECIMAL(10,4) NOT NULL, call_unit_price DECIMAL(10,4) NOT NULL,
 approval_threshold DECIMAL(14,2) NOT NULL,
 stage_rules JSON NULL,
 updated_by BIGINT NULL, updated_at DATETIME(6) NOT NULL
);

CREATE TABLE mk_journey (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, name VARCHAR(128) NOT NULL, description VARCHAR(512) NULL,
 -- draft | published | pending_approval | running | paused | stopped | completed
 status VARCHAR(24) NOT NULL,
 draft JSON NOT NULL, latest_version INT NOT NULL DEFAULT 0,
 run_version_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 snapshot_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 estimate JSON NULL,
 requested_by BIGINT NULL, approved_by BIGINT NULL,
 created_by BIGINT NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 started_at DATETIME(6) NULL, ended_at DATETIME(6) NULL,
 INDEX mk_journey_list(tenant_id,created_at),
 INDEX mk_journey_status(status)
);

-- Published definitions are immutable: rows are only ever inserted.
CREATE TABLE mk_journey_version (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 journey_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 version INT NOT NULL, definition JSON NOT NULL,
 definition_hash CHAR(64) CHARACTER SET ascii NOT NULL,
 published_by BIGINT NULL, published_at DATETIME(6) NOT NULL,
 UNIQUE KEY mk_journey_version_no(tenant_id,journey_id,version)
);

CREATE TABLE mk_journey_member (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 journey_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 version_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 snapshot_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 member_sequence BIGINT NOT NULL,
 phone_hash CHAR(64) CHARACTER SET ascii NOT NULL,
 phone_cipher VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL,
 contact_id BIGINT NULL,
 grp VARCHAR(8) CHARACTER SET ascii NOT NULL,
 node_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 node_entered_at DATETIME(6) NOT NULL,
 -- ready (due at next_run_at) | waiting (condition, woken by touch events) | running (claimed)
 -- | done | stopped
 state VARCHAR(16) NOT NULL,
 next_run_at DATETIME(6) NULL,
 claim_token VARCHAR(64) CHARACTER SET ascii NULL, claimed_until DATETIME(6) NULL,
 defer_count INT NOT NULL DEFAULT 0, last_result VARCHAR(255) NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY mk_jm_sequence(tenant_id,journey_id,member_sequence),
 INDEX mk_jm_due(state,next_run_at),
 INDEX mk_jm_node(tenant_id,journey_id,node_id,state),
 INDEX mk_jm_phone(tenant_id,phone_hash,state),
 INDEX mk_jm_created(tenant_id,created_at)
);

-- One row per member per node visit (definitions are acyclic): the run log and the send guard.
CREATE TABLE mk_journey_step (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 journey_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 member_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 node_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 node_type VARCHAR(16) NOT NULL,
 -- sent | denied | control | waited | yes | no | end | failed
 action VARCHAR(16) NOT NULL,
 reason VARCHAR(255) NULL,
 target_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 created_at DATETIME(6) NOT NULL,
 UNIQUE KEY mk_js_visit(member_id,node_id),
 INDEX mk_js_journey(tenant_id,journey_id,node_id,action)
);

CREATE TABLE mk_profile (
 tenant_id BIGINT NOT NULL,
 phone_hash CHAR(64) CHARACTER SET ascii NOT NULL,
 contact_id BIGINT NULL,
 stage VARCHAR(16) NOT NULL,
 sms_count INT NOT NULL DEFAULT 0, sms_delivered INT NOT NULL DEFAULT 0,
 link_clicks INT NOT NULL DEFAULT 0,
 call_count INT NOT NULL DEFAULT 0, call_connected INT NOT NULL DEFAULT 0,
 instant_hangups INT NOT NULL DEFAULT 0, conversations INT NOT NULL DEFAULT 0,
 voice_assistant BOOLEAN NOT NULL DEFAULT FALSE,
 best_intent VARCHAR(16) NULL,
 converted BOOLEAN NOT NULL DEFAULT FALSE,
 codes JSON NOT NULL,
 first_touch_at DATETIME(6) NULL, last_touch_at DATETIME(6) NULL, last_channel VARCHAR(16) NULL,
 stage_changed_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 PRIMARY KEY (tenant_id,phone_hash),
 INDEX mk_profile_stage(tenant_id,stage,updated_at)
);

CREATE TABLE mk_profile_history (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 phone_hash CHAR(64) CHARACTER SET ascii NOT NULL,
 from_stage VARCHAR(16) NULL, to_stage VARCHAR(16) NOT NULL,
 code VARCHAR(48) NULL,
 event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 created_at DATETIME(6) NOT NULL,
 INDEX mk_profile_history_phone(tenant_id,phone_hash,id),
 INDEX mk_profile_history_recent(tenant_id,created_at)
);

-- Idempotency guard: a touch fact changes a profile at most once (listener and catch-up both).
CREATE TABLE mk_profile_applied (
 event_sequence BIGINT PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 applied_at DATETIME(6) NOT NULL
);

-- Watermarks of background readers over append-only tables (e.g. profile catch-up).
CREATE TABLE mk_worker_cursor (
 name VARCHAR(64) CHARACTER SET ascii PRIMARY KEY,
 position BIGINT NOT NULL,
 updated_at DATETIME(6) NOT NULL
);

-- Hourly aggregates read by the dashboard. journey_id / snapshot_id / channel / grp use '' for
-- "not applicable"; metric is 'code:<OutcomeCode>', 'sent' or 'people:<funnel step>'.
CREATE TABLE mk_stat_hourly (
 tenant_id BIGINT NOT NULL,
 stat_hour DATETIME NOT NULL,
 journey_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '',
 snapshot_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '',
 channel VARCHAR(8) CHARACTER SET ascii NOT NULL DEFAULT '',
 grp VARCHAR(8) CHARACTER SET ascii NOT NULL DEFAULT '',
 metric VARCHAR(64) CHARACTER SET ascii NOT NULL,
 value BIGINT NOT NULL,
 PRIMARY KEY (tenant_id,stat_hour,journey_id,snapshot_id,channel,grp,metric),
 INDEX mk_stat_journey(tenant_id,journey_id,stat_hour),
 INDEX mk_stat_snapshot(tenant_id,snapshot_id,stat_hour)
);
