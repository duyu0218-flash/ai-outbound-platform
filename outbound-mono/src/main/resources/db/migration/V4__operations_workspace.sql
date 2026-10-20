-- Additive operations data. V1-V3 and the original call/ledger contracts stay immutable.
CREATE TABLE ops_team (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id INT NOT NULL,
 name VARCHAR(120) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, revision INT NOT NULL DEFAULT 0,
 settings_json JSON NOT NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_ops_team (tenant_id,name), KEY ix_ops_team_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ops_member (
 tenant_id INT NOT NULL, team_id BIGINT NOT NULL, user_id INT NOT NULL, is_lead BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(tenant_id,team_id,user_id), KEY ix_ops_member_user(tenant_id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ops_permission (
 tenant_id INT NOT NULL,user_id INT NOT NULL, can_export BOOLEAN NOT NULL DEFAULT FALSE,
 can_unmask BOOLEAN NOT NULL DEFAULT FALSE, can_record BOOLEAN NOT NULL DEFAULT TRUE,
 PRIMARY KEY(tenant_id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ops_lead (
 tenant_id INT NOT NULL,contact_id INT NOT NULL,owner_id INT NULL, stage VARCHAR(32) NOT NULL DEFAULT 'open',
 next_at DATETIME(3) NULL,revision INT NOT NULL DEFAULT 0,form_id BIGINT NULL,form_revision INT NULL,
 fields_json JSON NOT NULL,tags_json JSON NOT NULL,updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(tenant_id,contact_id), KEY ix_ops_lead_owner(tenant_id,owner_id,next_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ops_history (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,tenant_id INT NOT NULL,contact_id INT NOT NULL,
 actor_id INT NOT NULL,event_key VARCHAR(80) NOT NULL,payload_hash CHAR(64) NOT NULL,
 action VARCHAR(32) NOT NULL,detail_json JSON NOT NULL,created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_ops_history_event(tenant_id,event_key),KEY ix_ops_history_contact(tenant_id,contact_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ops_view (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,tenant_id INT NOT NULL,user_id INT NOT NULL,
 kind VARCHAR(24) NOT NULL,name VARCHAR(100) NOT NULL,config_json JSON NOT NULL,revision INT NOT NULL DEFAULT 0,
 UNIQUE KEY uq_ops_view(tenant_id,user_id,kind,name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ops_config (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,tenant_id INT NOT NULL,kind VARCHAR(24) NOT NULL,
 name VARCHAR(120) NOT NULL,revision INT NOT NULL DEFAULT 1,config_json JSON NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),UNIQUE KEY uq_ops_config(tenant_id,kind,name,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ops_notice (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,tenant_id INT NOT NULL,user_id INT NOT NULL,
 event_key VARCHAR(160) NOT NULL,title VARCHAR(200) NOT NULL,path VARCHAR(240) NOT NULL,
 read_at DATETIME(3) NULL,created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_ops_notice(tenant_id,user_id,event_key),KEY ix_ops_notice_user(tenant_id,user_id,read_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ops_presence (
 tenant_id INT NOT NULL,user_id INT NOT NULL,available_after DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ops_tag_link (
 tenant_id INT NOT NULL,target_kind VARCHAR(24) NOT NULL,object_id VARCHAR(80) NOT NULL,tag VARCHAR(120) NOT NULL,
 PRIMARY KEY(tenant_id,target_kind,object_id,tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
