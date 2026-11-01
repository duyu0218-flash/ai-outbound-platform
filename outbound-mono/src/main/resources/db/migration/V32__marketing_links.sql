-- Module P2: short-link domains, landing whitelist, per-recipient links, click capture and the
-- SMS delivery-report bridge into mk_touch_event (see MARKETING-ARCHITECTURE.md).

-- Short-link domains grouped into pools. Only state='active' domains receive new links; a
-- blocked domain keeps redirecting the links it already carries.
CREATE TABLE mk_link_domain (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 domain VARCHAR(253) CHARACTER SET ascii NOT NULL,
 pool VARCHAR(64) CHARACTER SET ascii NOT NULL DEFAULT 'default',
 icp_number VARCHAR(64) NULL,
 cert_expires_at DATETIME(6) NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'standby',
 weight INT NOT NULL DEFAULT 100,
 probe_state VARCHAR(16) NULL, probe_reason VARCHAR(255) NULL, probed_at DATETIME(6) NULL,
 blocked_reason VARCHAR(255) NULL, blocked_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 UNIQUE KEY mk_link_domain_name(tenant_id,domain),
 INDEX mk_link_domain_pool(tenant_id,pool,state),
 INDEX mk_link_domain_probe(state,probed_at),
 CHECK(state IN ('active','standby','blocked','disabled')),
 CHECK(weight BETWEEN 0 AND 10000)
);

-- Approved landing-page hosts. "*.example.com" approves subdomains; anything else is refused at
-- mint time, so a short link can never become an open redirect.
CREATE TABLE mk_landing_domain (
 tenant_id BIGINT NOT NULL,
 domain VARCHAR(253) CHARACTER SET ascii NOT NULL,
 note VARCHAR(255) NULL,
 created_by BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,domain)
);

-- One row per minted code. kind='personal' links belong to one recipient (phone_hash) and are
-- bound to the message that carries them; kind='public' links serve a whole campaign (PV/UV only).
CREATE TABLE mk_short_link (
 code CHAR(7) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 kind VARCHAR(16) NOT NULL DEFAULT 'personal',
 domain_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 domain VARCHAR(253) CHARACTER SET ascii NOT NULL,
 target_url VARCHAR(2048) NOT NULL,
 phone_hash CHAR(64) CHARACTER SET ascii NULL, contact_id BIGINT NULL,
 snapshot_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 journey_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 journey_member_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 campaign_id VARCHAR(64) NULL, name VARCHAR(128) NULL,
 message_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 expires_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX mk_short_link_tenant(tenant_id,kind,created_at),
 INDEX mk_short_link_message(tenant_id,message_id),
 INDEX mk_short_link_snapshot(tenant_id,snapshot_id),
 INDEX mk_short_link_domain(domain_id)
);

-- Click facts written in batches off the redirect path. ip_prefix keeps only /24 (IPv4) or /48
-- (IPv6). verdict: valid (counted), repeat (same code within 30 minutes), bot.
CREATE TABLE mk_link_click (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 code CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 clicked_at DATETIME(6) NOT NULL,
 verdict VARCHAR(16) NOT NULL, reason VARCHAR(32) NULL,
 ip_prefix VARCHAR(64) NULL, user_agent VARCHAR(255) NULL,
 visitor_hash CHAR(64) CHARACTER SET ascii NOT NULL,
 wechat BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX mk_link_click_code(tenant_id,code,verdict,clicked_at),
 INDEX mk_link_click_tenant(tenant_id,clicked_at)
);

-- Provider delivery codes -> OutcomeCodes. tenant_id=0 rows are platform defaults; provider '*'
-- applies to every adapter. Platform message states are keyed as 'platform:<STATE>'.
CREATE TABLE mk_sms_status_map (
 tenant_id BIGINT NOT NULL DEFAULT 0,
 provider VARCHAR(32) NOT NULL DEFAULT '*',
 status_code VARCHAR(64) NOT NULL,
 outcome VARCHAR(48) NOT NULL,
 note VARCHAR(255) NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,provider,status_code)
);
INSERT INTO mk_sms_status_map(tenant_id,provider,status_code,outcome,note) VALUES
 (0,'*','platform:DELIVERED','SMS_DELIVERED','平台状态：已送达'),
 (0,'*','platform:REJECTED','SMS_FAIL_TEMP','平台状态：被拒（未带运营商码时按临时失败处理）'),
 (0,'*','platform:BLOCKED','SMS_SUBMIT_FAIL','平台状态：提交前被拦截'),
 (0,'*','platform:EXPIRED','SMS_SUBMIT_FAIL','平台状态：提交前过期'),
 (0,'*','DELIVRD','SMS_DELIVERED','运营商：成功送达'),
 (0,'*','UNDELIV','SMS_FAIL_TEMP','运营商：未送达（示例，按通道文档维护）'),
 (0,'*','EXPIRED','SMS_FAIL_TEMP','运营商：送达超时（示例）'),
 (0,'*','MK:0001','SMS_FAIL_INVALID','运营商：空号（示例）'),
 (0,'*','EMPTY','SMS_FAIL_INVALID','运营商：空号（示例）'),
 (0,'*','REJECTD','SMS_FAIL_BLOCKED','运营商：拒收（示例）'),
 (0,'*','BLACK','SMS_FAIL_BLOCKED','运营商：黑名单拦截（示例）'),
 (0,'*','KEYWORD','SMS_FAIL_BLOCKED','运营商：关键字拦截（示例）');

-- Watermarks for cross-tenant scans. The receipt bridge starts after the events that existed
-- when this module was installed (none of them came from marketing).
CREATE TABLE mk_link_cursor (
 name VARCHAR(64) PRIMARY KEY,
 position BIGINT NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
);
INSERT INTO mk_link_cursor(name,position) SELECT 'sms_receipts',COALESCE(MAX(sequence),0) FROM msg_event;
