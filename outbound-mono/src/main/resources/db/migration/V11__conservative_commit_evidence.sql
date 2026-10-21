CREATE TABLE capacity_commit_evidence (
 transaction_id CHAR(32) NOT NULL PRIMARY KEY,
 run_id VARCHAR(128) NOT NULL,
 clock_origin CHAR(32) NOT NULL,
 lo_us BIGINT NOT NULL,
 hi_us BIGINT NOT NULL,
 clock_error_us BIGINT NOT NULL,
 clock_valid BOOLEAN NOT NULL,
 CHECK(lo_us<=hi_us),
 KEY capacity_commit_run(run_id,lo_us)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE capacity_identity_evidence (
 evidence_kind VARCHAR(16) NOT NULL,
 tenant_id BIGINT NOT NULL,
 call_id CHAR(36) NOT NULL,
 attempt BIGINT NOT NULL,
 transaction_id CHAR(32) NOT NULL,
 PRIMARY KEY(evidence_kind,tenant_id,call_id,attempt),
 KEY capacity_identity_commit(transaction_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
