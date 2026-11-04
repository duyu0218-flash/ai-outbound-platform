CREATE TABLE script_reach (
 tenant_id BIGINT NOT NULL, script_id BIGINT NOT NULL, flow_id BIGINT NOT NULL,
 call_id CHAR(36) NOT NULL, kind VARCHAR(12) NOT NULL, target_id VARCHAR(160) NOT NULL,
 target_label VARCHAR(255) NOT NULL, cohort_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,script_id,call_id,kind,target_id),
 KEY ix_script_reach_period(tenant_id,script_id,cohort_at,kind,flow_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
