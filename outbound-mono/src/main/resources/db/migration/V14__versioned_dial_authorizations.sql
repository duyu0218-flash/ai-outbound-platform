ALTER TABLE dial_command_journal ADD COLUMN wire_digest CHAR(64),ADD COLUMN current_fence BIGINT NOT NULL DEFAULT 0;
ALTER TABLE dial_reservation ADD COLUMN owner_expires_at DATETIME(3),ADD COLUMN authorization_state VARCHAR(16) NOT NULL DEFAULT 'UNINSTALLED';
ALTER TABLE voice_attempts ADD COLUMN protocol_version INT NOT NULL DEFAULT 1,ADD COLUMN registration_id CHAR(36),ADD COLUMN send_fence BIGINT NOT NULL DEFAULT 0;

CREATE TABLE voice_dial_authorization (
 tenant_id BIGINT NOT NULL,
 call_id VARCHAR(128) NOT NULL,
 attempt BIGINT NOT NULL,
 registration_id CHAR(36) NOT NULL,
 epoch BIGINT NOT NULL,
 owner VARCHAR(128) NOT NULL,
 fence BIGINT NOT NULL,
 expires_at BIGINT NOT NULL,
 semantic_digest CHAR(64) NOT NULL,
 state VARCHAR(16) NOT NULL,
 proof_id CHAR(36),
 reason VARCHAR(256),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(tenant_id,call_id,attempt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE voice_dial_authorization_history (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 call_id VARCHAR(128) NOT NULL,
 attempt BIGINT NOT NULL,
 registration_id CHAR(36) NOT NULL,
 fence BIGINT NOT NULL,
 evidence LONGTEXT NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 KEY voice_authorization_history(tenant_id,call_id,attempt,fence)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
