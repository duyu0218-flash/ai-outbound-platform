CREATE TABLE ops_interception (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT NOT NULL,
 call_id CHAR(36) NOT NULL,phone VARCHAR(64) NOT NULL,reason VARCHAR(240) NOT NULL,
 created_at DATETIME(3) NOT NULL,UNIQUE KEY interception_call(tenant_id,call_id),
 KEY interception_date(tenant_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ops_export_job (
 id CHAR(36) NOT NULL PRIMARY KEY,tenant_id BIGINT NOT NULL,user_id BIGINT NOT NULL,
 request_key VARCHAR(64) NOT NULL,kind VARCHAR(40) NOT NULL,query_json TEXT NOT NULL,
 state VARCHAR(20) NOT NULL DEFAULT 'queued',content MEDIUMBLOB NULL,error VARCHAR(400) NULL,access_json TEXT NULL,
 created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,
 UNIQUE KEY export_request(tenant_id,user_id,request_key),KEY export_queue(state,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE ops_notice ADD COLUMN deleted_at DATETIME(3) NULL;
CREATE TABLE ops_account_transfer (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT NOT NULL,user_id BIGINT NOT NULL,
 request_key VARCHAR(64) NOT NULL,amount DECIMAL(16,2) NOT NULL,note VARCHAR(300) NOT NULL,
 actor_id BIGINT NOT NULL,source_mode VARCHAR(20) NOT NULL,created_at DATETIME(3) NOT NULL,
 UNIQUE KEY account_transfer_request(tenant_id,request_key),KEY account_ledger(tenant_id,user_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
