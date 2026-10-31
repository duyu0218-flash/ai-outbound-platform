-- B-side client portal. Additive only: existing tables, columns and contracts stay unchanged.
CREATE TABLE client_org (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id INT NOT NULL, code VARCHAR(64) NOT NULL,
 name VARCHAR(200) NOT NULL, contact_name VARCHAR(100) NULL, contact_phone VARCHAR(32) NULL,
 contract_start DATE NULL, contract_end DATE NULL, status VARCHAR(16) NOT NULL DEFAULT 'active',
 remark VARCHAR(500) NULL, revision INT NOT NULL DEFAULT 0, created_by INT NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_client_org_code (tenant_id,code), KEY ix_client_org_tenant (tenant_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE client_setting (
 tenant_id INT NOT NULL, client_id BIGINT NOT NULL, revision INT NOT NULL, config_json JSON NOT NULL,
 created_by INT NOT NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY (tenant_id,client_id,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE client_user (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id INT NOT NULL, client_id BIGINT NOT NULL,
 username VARCHAR(200) NOT NULL, password_hash VARCHAR(255) NOT NULL, full_name VARCHAR(100) NOT NULL,
 phone VARCHAR(32) NULL, role VARCHAR(16) NOT NULL DEFAULT 'owner', grants_json JSON NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE, must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
 token_version INT NOT NULL DEFAULT 0, failed_login_attempts INT NOT NULL DEFAULT 0,
 locked_until DATETIME(3) NULL, last_login_at DATETIME(3) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_client_user_name (username), KEY ix_client_user_org (tenant_id,client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE client_campaign (
 tenant_id INT NOT NULL, campaign_id INT NOT NULL PRIMARY KEY, client_id BIGINT NOT NULL,
 deliver_history BOOLEAN NOT NULL DEFAULT FALSE, revision INT NOT NULL DEFAULT 0, bound_by INT NOT NULL,
 bound_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), KEY ix_client_campaign_org (tenant_id,client_id),
 CONSTRAINT fk_client_campaign_campaign FOREIGN KEY (campaign_id) REFERENCES campaign (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE client_campaign_log (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id INT NOT NULL, campaign_id INT NOT NULL,
 from_client_id BIGINT NULL, to_client_id BIGINT NULL, actor_id INT NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), KEY ix_client_campaign_log (tenant_id,campaign_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE client_lead (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id INT NOT NULL, client_id BIGINT NOT NULL,
 campaign_id INT NOT NULL, call_session_id CHAR(36) NOT NULL, contact_id INT NULL, dedup_key CHAR(64) NOT NULL,
 phone_masked VARCHAR(32) NOT NULL, contact_name VARCHAR(200) NULL, result_code VARCHAR(64) NOT NULL,
 intent VARCHAR(100) NOT NULL, summary TEXT NOT NULL, slots_json JSON NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'delivered', withdraw_reason VARCHAR(32) NULL, withdraw_note VARCHAR(500) NULL,
 follow_state VARCHAR(16) NOT NULL DEFAULT 'pending', invalid_reason VARCHAR(32) NULL, follow_note VARCHAR(1000) NULL,
 next_follow_at DATETIME(3) NULL, assignee_id BIGINT NULL, dispute_state VARCHAR(16) NOT NULL DEFAULT 'none',
 dispute_note VARCHAR(1000) NULL, first_viewed_at DATETIME(3) NULL, delivered_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), revision INT NOT NULL DEFAULT 0,
 UNIQUE KEY uq_client_lead_call (client_id,call_session_id),
 KEY ix_client_lead_list (tenant_id,client_id,delivered_at,id),
 KEY ix_client_lead_follow (tenant_id,client_id,follow_state,delivered_at),
 KEY ix_client_lead_dedup (tenant_id,client_id,dedup_key,delivered_at),
 KEY ix_client_lead_call (tenant_id,call_session_id),
 KEY ix_client_lead_campaign (tenant_id,campaign_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE client_lead_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id INT NOT NULL, client_id BIGINT NOT NULL,
 lead_id BIGINT NULL, actor_kind VARCHAR(16) NOT NULL, actor_id BIGINT NULL, action VARCHAR(32) NOT NULL,
 detail_json JSON NOT NULL, event_key VARCHAR(160) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_client_lead_event (tenant_id,event_key), KEY ix_client_lead_event_lead (tenant_id,lead_id,id),
 KEY ix_client_lead_event_org (tenant_id,client_id,action,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE client_staff_scope (
 tenant_id INT NOT NULL, user_id INT NOT NULL, client_id BIGINT NOT NULL,
 PRIMARY KEY (tenant_id,user_id,client_id), KEY ix_client_staff_scope_org (tenant_id,client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
