CREATE TABLE voice_statement_entry (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT NOT NULL,
 source_mode VARCHAR(10) NOT NULL,reference_no VARCHAR(128) NOT NULL,
 kind VARCHAR(16) NOT NULL, currency CHAR(3) NOT NULL, signed_amount DECIMAL(18,6) NOT NULL,
 call_id CHAR(36), attempt INT, bill_seconds INT,unit_seconds INT,unit_price DECIMAL(16,6),
 tariff_version VARCHAR(100), settlement_source VARCHAR(160) NOT NULL,
 posted_at DATETIME(3) NOT NULL, created_by BIGINT NOT NULL, row_hash CHAR(64) NOT NULL,
 opening_key VARCHAR(10) GENERATED ALWAYS AS (IF(kind='opening','opening',NULL)) STORED,
 UNIQUE KEY voice_statement_ref(tenant_id,source_mode,reference_no),
 UNIQUE KEY voice_one_opening(tenant_id,source_mode,opening_key),
 KEY voice_bill_call(tenant_id,call_id,attempt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
