CREATE TABLE dial_command_journal (
 tenant_id BIGINT NOT NULL,
 call_id CHAR(36) NOT NULL,
 attempt BIGINT NOT NULL,
 protocol_version INT NOT NULL DEFAULT 1,
 semantic_digest CHAR(64) NOT NULL,
 frozen_payload LONGTEXT NOT NULL,
 state VARCHAR(32) NOT NULL DEFAULT 'PREPARED',
 provider_uuid VARCHAR(128),
 accepted_result LONGTEXT,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 accepted_at DATETIME(3),
 PRIMARY KEY(tenant_id,call_id,attempt),
 KEY dial_journal_recovery(state,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE voice_mock_schedule (
 uuid CHAR(36) NOT NULL PRIMARY KEY,
 phase VARCHAR(16) NOT NULL,
 due DOUBLE NOT NULL,
 end_at DOUBLE NOT NULL,
 KEY voice_mock_due(due)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
