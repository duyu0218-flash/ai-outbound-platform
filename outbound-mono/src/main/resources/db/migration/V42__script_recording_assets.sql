CREATE TABLE script_recording_asset (
 tenant_id BIGINT NOT NULL,script_id BIGINT NOT NULL,segment_key CHAR(64) CHARACTER SET ascii NOT NULL,
 prompt_text VARCHAR(4000) NOT NULL,media_type VARCHAR(64) NOT NULL,content MEDIUMBLOB NOT NULL,
 revision INT NOT NULL,updated_by BIGINT NOT NULL,updated_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,script_id,segment_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
