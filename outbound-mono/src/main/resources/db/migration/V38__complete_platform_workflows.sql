-- Complete the business workspaces without rewriting historical calls/publications.
CREATE TABLE platform_knowledge_upload (
 tenant_id BIGINT NOT NULL,id VARCHAR(64) NOT NULL,library_id VARCHAR(64) NOT NULL,
 filename VARCHAR(240) NOT NULL,file_sha CHAR(64) NOT NULL,file_bytes LONGBLOB NULL,
 settings_json TEXT NOT NULL,state VARCHAR(24) NOT NULL DEFAULT 'queued',revision INT NOT NULL DEFAULT 1,
 progress INT NOT NULL DEFAULT 0,segments_json LONGTEXT NULL,confirmation_sha CHAR(64) NULL,error VARCHAR(2000) NULL,
 created_by BIGINT NULL,created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,id),INDEX platform_knowledge_upload_state(state,created_at),
 INDEX platform_knowledge_upload_library(tenant_id,library_id,created_at)
);
CREATE TABLE platform_knowledge_gap (
 tenant_id BIGINT NOT NULL,id VARCHAR(64) NOT NULL,source_type VARCHAR(16) NOT NULL,
 source_id VARCHAR(64) NOT NULL,question_sha CHAR(64) NOT NULL,question VARCHAR(2000) NOT NULL,
 campaign_id BIGINT NULL,context_json LONGTEXT NOT NULL,state VARCHAR(24) NOT NULL DEFAULT 'pending',
 revision INT NOT NULL DEFAULT 1,library_id VARCHAR(64) NULL,item_id VARCHAR(64) NULL,
 resolution VARCHAR(2000) NULL,resolution_sha CHAR(64) NULL,resolved_by BIGINT NULL,
 created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,id),UNIQUE KEY platform_gap_source(tenant_id,source_type,source_id,question_sha),
 INDEX platform_gap_queue(tenant_id,state,created_at)
);
CREATE TABLE platform_contact_value (
 tenant_id BIGINT NOT NULL,contact_id BIGINT NOT NULL,values_json LONGTEXT NOT NULL,
 updated_at DATETIME(3) NOT NULL,PRIMARY KEY(tenant_id,contact_id)
);
ALTER TABLE platform_contact_batch_member ADD COLUMN variables_json LONGTEXT NULL;
CREATE TABLE platform_audience_member (
 tenant_id BIGINT NOT NULL,snapshot_id VARCHAR(128) NOT NULL,contact_id BIGINT NOT NULL,
 variables_json LONGTEXT NOT NULL,PRIMARY KEY(tenant_id,snapshot_id,contact_id)
);
ALTER TABLE platform_contact_upload ADD COLUMN schema_json TEXT NULL;
CREATE TABLE platform_debug_session (
 tenant_id BIGINT NOT NULL,id VARCHAR(64) NOT NULL,request_key VARCHAR(64) NOT NULL,
 name VARCHAR(160) NOT NULL,script_id BIGINT NOT NULL,script_version INT NOT NULL,
 mode VARCHAR(24) NOT NULL,provider VARCHAR(64) NOT NULL,state VARCHAR(24) NOT NULL,
 revision INT NOT NULL DEFAULT 1,snapshot_json LONGTEXT NOT NULL,state_json LONGTEXT NOT NULL,
 created_by BIGINT NULL,created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,id),UNIQUE KEY platform_debug_request(tenant_id,request_key),
 INDEX platform_debug_history(tenant_id,created_at)
);
CREATE TABLE platform_debug_turn (
 tenant_id BIGINT NOT NULL,session_id VARCHAR(64) NOT NULL,id VARCHAR(64) NOT NULL,
 turn_number INT NOT NULL,input_text VARCHAR(2000) NOT NULL,result_json LONGTEXT NOT NULL,
 created_at DATETIME(3) NOT NULL,PRIMARY KEY(tenant_id,session_id,id),
 UNIQUE KEY platform_debug_turn_order(tenant_id,session_id,turn_number)
);
CREATE TABLE platform_message_job (
 tenant_id BIGINT NOT NULL,id VARCHAR(64) NOT NULL,request_key VARCHAR(64) NOT NULL,
 campaign_id VARCHAR(64) NOT NULL,state VARCHAR(24) NOT NULL DEFAULT 'queued',
 after_contact BIGINT NOT NULL DEFAULT 0,submitted_count INT NOT NULL DEFAULT 0,
 chunks_json LONGTEXT NOT NULL,error VARCHAR(2000) NULL,
 created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,id),UNIQUE KEY platform_message_request(tenant_id,request_key),
 INDEX platform_message_pending(state,created_at)
);
