-- Preserve correlated provider terminal facts after callback transport acknowledgement deletes
-- its outbox row. The fact commits atomically with the original callback, never from a timeout.
CREATE TABLE voice_terminal_fact (
 uuid VARCHAR(128) NOT NULL PRIMARY KEY,
 tenant BIGINT NOT NULL,
 call_id VARCHAR(128) NOT NULL,
 attempt BIGINT NOT NULL,
 body LONGBLOB NOT NULL,
 recorded_at DOUBLE NOT NULL,
 KEY voice_terminal_retention(recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

DELIMITER $$
CREATE TRIGGER voice_terminal_outbox_insert AFTER INSERT ON voice_outbox FOR EACH ROW
BEGIN
 DECLARE doc LONGTEXT CHARACTER SET utf8mb4;
 SET doc=CONVERT(NEW.body USING utf8mb4);
 IF JSON_VALID(doc) THEN
  IF JSON_UNQUOTE(JSON_EXTRACT(doc,'$.kind'))='status'
     AND JSON_UNQUOTE(JSON_EXTRACT(doc,'$.payload.status')) IN
       ('ended','completed','failed','busy','no_answer','voicemail','canceled') THEN
   INSERT IGNORE INTO voice_terminal_fact(uuid,tenant,call_id,attempt,body,recorded_at)
   SELECT a.uuid,a.tenant,a.call_id,a.attempt,NEW.body,NEW.created
   FROM voice_attempts a
   WHERE a.uuid=COALESCE(JSON_UNQUOTE(JSON_EXTRACT(doc,'$.payload.provider_call_id')),
                         JSON_UNQUOTE(JSON_EXTRACT(doc,'$.payload.telephony_call_id')))
     AND a.call_id=JSON_UNQUOTE(JSON_EXTRACT(doc,'$.call_id'))
     AND a.tenant=JSON_EXTRACT(doc,'$.payload.tenant_id')
     AND a.attempt=JSON_EXTRACT(doc,'$.payload.attempt');
  END IF;
 END IF;
END$$
DELIMITER ;
