CREATE TABLE aivoicetestset (
 id VARCHAR(36) PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 name VARCHAR(120) NOT NULL,
 script_snapshot MEDIUMTEXT NOT NULL,
 script_sha CHAR(64) NOT NULL,
 samples_json MEDIUMTEXT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX idx_voice_ai_set_tenant (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE aivoicetestrun (
 id VARCHAR(36) PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 test_set_id VARCHAR(36) NOT NULL,
 profile_id VARCHAR(40) NOT NULL,
 client_key VARCHAR(80) NOT NULL,
 profile_json TEXT NOT NULL,
 status VARCHAR(24) NOT NULL,
 results_json LONGTEXT NOT NULL,
 audio_json LONGTEXT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_voice_ai_run_request (tenant_id, client_key),
 INDEX idx_voice_ai_run_set (tenant_id, test_set_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
