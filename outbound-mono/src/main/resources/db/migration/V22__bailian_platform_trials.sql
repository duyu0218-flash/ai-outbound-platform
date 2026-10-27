CREATE TABLE aivoicetrial (
 id VARCHAR(36) PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 contact_id BIGINT NOT NULL,
 client_key VARCHAR(80) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 status VARCHAR(24) NOT NULL,
 script_snapshot MEDIUMTEXT NOT NULL,
 profile_json TEXT NOT NULL,
 events_json MEDIUMTEXT NOT NULL,
 result_json TEXT NOT NULL,
 published_json TEXT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_voice_trial_request (tenant_id, client_key),
 INDEX idx_voice_trial_tenant (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE aivoicetrialaudio (
 trial_id VARCHAR(36) NOT NULL,
 tenant_id BIGINT NOT NULL,
 audio_id VARCHAR(40) NOT NULL,
 direction VARCHAR(12) NOT NULL,
 sample_rate INT NOT NULL,
 pcm MEDIUMBLOB NOT NULL,
 PRIMARY KEY (trial_id, audio_id),
 INDEX idx_voice_trial_audio (tenant_id, trial_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
