-- Classify old and new durable work without rewriting task types or idempotency keys.
ALTER TABLE taskoutbox
 ADD COLUMN integration_lane VARCHAR(16) GENERATED ALWAYS AS (
  CASE
   WHEN task_type='business_callback' AND JSON_CONTAINS_PATH(IF(JSON_VALID(payload_json),payload_json,'{}'),'one','$.share_assignment') THEN 'assignment'
   WHEN task_type='business_callback' AND JSON_CONTAINS_PATH(IF(JSON_VALID(payload_json),payload_json,'{}'),'one','$.interface_request') THEN 'interface'
   WHEN task_type='business_callback' AND JSON_CONTAINS_PATH(IF(JSON_VALID(payload_json),payload_json,'{}'),'one','$.push_type') THEN 'push'
   WHEN task_type='business_callback' THEN 'callback'
   WHEN task_type IN ('recording_ingest','recording_delete') THEN 'recording'
   WHEN task_type='send_sms' THEN 'sms'
   ELSE ''
  END
 ) STORED,
 ADD INDEX ix_taskoutbox_integration_ready(tenant_id,integration_lane,task_type,state,available_at,id);

CREATE TABLE platform_job_cursor (
 job_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 last_id BIGINT NOT NULL DEFAULT 0,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
INSERT INTO platform_job_cursor(job_key) VALUES('balance-push');
CREATE INDEX platform_balance_scan ON platform_document(kind,object_id,tenant_id);
CREATE INDEX msg_send_partition ON msg_message(state,tenant_id,channel_id,scheduled_at,id);
CREATE INDEX msg_query_partition ON msg_delivery(state,tenant_id,channel_id,next_query_at,message_id);
