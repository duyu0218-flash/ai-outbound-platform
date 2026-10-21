-- Existing pending commands may already have reached the PBX. Never classify
-- them as safe-to-send when upgrading an existing ledger.
ALTER TABLE voice_attempts
  ADD COLUMN send_state VARCHAR(32) NOT NULL DEFAULT 'RECONCILING',
  ADD COLUMN send_intent_at DOUBLE,
  ADD COLUMN send_evidence LONGTEXT,
  ADD COLUMN manual_hold_at DOUBLE,
  ADD KEY voice_send_recovery(send_state,send_intent_at);
UPDATE voice_attempts SET send_state=CASE
  WHEN state='ended' THEN 'ENDED'
  WHEN observed<>0 THEN 'PBX_STARTED'
  WHEN result IS NOT NULL THEN 'PBX_ACCEPTED'
  ELSE 'RECONCILING' END,
  send_intent_at=created,send_evidence=result;

CREATE TABLE dial_admission_control (
  id BIGINT NOT NULL PRIMARY KEY,
  phase VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
  epoch BIGINT NOT NULL DEFAULT 1,
  revision BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
INSERT INTO dial_admission_control(id) VALUES (1);

CREATE TABLE dial_drain_member (
  epoch BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  call_id CHAR(32) NOT NULL,
  attempt BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(epoch,tenant_id,call_id,attempt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
