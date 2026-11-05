-- Uncertain events retain resource reservations until independent reconciliation.
CREATE TABLE voice_event_quarantine (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  event_identity CHAR(64) NOT NULL,
  uuid VARCHAR(128) NOT NULL,
  event_name VARCHAR(64) NOT NULL,
  tenant_id BIGINT,
  call_id VARCHAR(128),
  attempt BIGINT,
  state VARCHAR(32) NOT NULL DEFAULT 'RECONCILE_REQUIRED',
  error_type VARCHAR(128) NOT NULL,
  identity_json JSON NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  resolved_at TIMESTAMP(3),
  UNIQUE KEY voice_quarantine_identity(event_identity),
  KEY voice_quarantine_call(uuid,state,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
