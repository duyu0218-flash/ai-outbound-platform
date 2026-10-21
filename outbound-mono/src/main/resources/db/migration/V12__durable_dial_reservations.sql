-- The first install is offline and drained. Existing unknown voice commands
-- cannot be reclassified as free resources by installing these tables.
DELIMITER $$
CREATE PROCEDURE dial_assert_quota_install_drained()
BEGIN
 IF EXISTS(SELECT 1 FROM callsession WHERE status IN('DIALING','ANSWERED','IN_AI','WAITING_HUMAN','HANDOFF_TRANSFERRING','IN_HUMAN'))
 OR EXISTS(SELECT 1 FROM voice_attempts WHERE state!='ended') THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='durable quota installation requires a fully drained database';
 END IF;
END$$
CALL dial_assert_quota_install_drained()$$
DROP PROCEDURE dial_assert_quota_install_drained$$
DELIMITER ;

ALTER TABLE dial_admission_control
 ADD COLUMN enforce_quotas BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN platform_limit BIGINT NOT NULL DEFAULT 20,
 ADD COLUMN tenant_default_limit BIGINT NOT NULL DEFAULT 20;

CREATE TABLE dial_reservation (
 reservation_id CHAR(36) NOT NULL PRIMARY KEY,
 generation_id BIGINT NOT NULL,
 tenant_id BIGINT NOT NULL,
 call_id CHAR(36) NOT NULL,
 attempt BIGINT NOT NULL,
 admission_sequence BIGINT NOT NULL,
 epoch BIGINT NOT NULL,
 state VARCHAR(16) NOT NULL,
 dimensions JSON NOT NULL,
 phone_key VARCHAR(32) NOT NULL,
 owner VARCHAR(128) NOT NULL DEFAULT 'lock_compat',
 fence BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 released_at DATETIME(3),
 UNIQUE KEY dial_reservation_attempt(tenant_id,call_id,attempt,admission_sequence),
 KEY dial_reservation_live(state,generation_id,tenant_id,call_id,attempt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE dial_phone_guard (
 generation_id BIGINT NOT NULL,
 tenant_id BIGINT NOT NULL,
 phone_key VARCHAR(32) NOT NULL,
 reservation_id CHAR(36) NOT NULL,
 enforced BOOLEAN NOT NULL,
 exclusive_phone VARCHAR(32) GENERATED ALWAYS AS(IF(enforced,phone_key,NULL)) STORED,
 revision BIGINT NOT NULL DEFAULT 1,
 PRIMARY KEY(generation_id,tenant_id,phone_key,reservation_id),
 UNIQUE KEY dial_phone_exclusive(generation_id,tenant_id,exclusive_phone),
 UNIQUE KEY dial_phone_reservation(reservation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE dial_quota_counter (
 generation_id BIGINT NOT NULL,
 scope_type VARCHAR(16) NOT NULL,
 scope_id VARCHAR(128) NOT NULL,
 active BIGINT NOT NULL DEFAULT 0,
 hard_limit BIGINT NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1,
 PRIMARY KEY(generation_id,scope_type,scope_id),
 CHECK(active>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

DELIMITER $$
CREATE PROCEDURE dial_apply_quota(IN gen BIGINT,IN scopes JSON,IN delta INT,IN enforce_limits BOOLEAN)
BEGIN
 DECLARE done INT DEFAULT 0;
 DECLARE typ VARCHAR(16);
 DECLARE sid VARCHAR(128);
 DECLARE lim BIGINT;
 DECLARE occupied BIGINT;
 DECLARE item CURSOR FOR SELECT t.typ,t.sid,t.lim FROM JSON_TABLE(scopes,'$[*]' COLUMNS(typ VARCHAR(16) PATH '$.type',sid VARCHAR(128) PATH '$.id',lim BIGINT PATH '$.limit')) t ORDER BY t.typ,t.sid;
 DECLARE CONTINUE HANDLER FOR NOT FOUND SET done=1;
 OPEN item;
 counter_loop: LOOP
  FETCH item INTO typ,sid,lim;
  IF done=1 THEN LEAVE counter_loop;END IF;
  IF delta>0 THEN INSERT IGNORE INTO dial_quota_counter(generation_id,scope_type,scope_id,active,hard_limit) VALUES(gen,typ,sid,0,lim);END IF;
  SET occupied=NULL;
  SELECT active INTO occupied FROM dial_quota_counter WHERE generation_id=gen AND scope_type=typ AND scope_id=sid FOR UPDATE;
  IF occupied IS NULL OR occupied+delta<0 OR (delta>0 AND enforce_limits AND occupied+delta>lim) THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='durable dial quota condition failed';
  END IF;
  UPDATE dial_quota_counter SET active=active+delta,hard_limit=IF(delta>0,lim,hard_limit),revision=revision+1 WHERE generation_id=gen AND scope_type=typ AND scope_id=sid;
 END LOOP;
 CLOSE item;
END$$

CREATE PROCEDURE dial_release_reservation(IN tid BIGINT,IN cid CHAR(36),IN att BIGINT)
BEGIN
 DECLARE rid CHAR(36);
 DECLARE gen BIGINT;
 DECLARE scopes JSON;
 SELECT reservation_id,generation_id,dimensions INTO rid,gen,scopes FROM dial_reservation WHERE tenant_id=tid AND call_id=cid AND attempt=att AND state='RESERVED' ORDER BY admission_sequence DESC LIMIT 1 FOR UPDATE;
 IF rid IS NOT NULL THEN
  DELETE FROM dial_phone_guard WHERE reservation_id=rid;
  CALL dial_apply_quota(gen,scopes,-1,FALSE);
  UPDATE dial_reservation SET state='RELEASED',released_at=UTC_TIMESTAMP(3) WHERE reservation_id=rid AND state='RESERVED';
  IF ROW_COUNT()<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='reservation release was not unique';END IF;
 ELSE
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='active call has no durable reservation';
 END IF;
END$$

CREATE PROCEDURE dial_acquire_reservation(IN tid BIGINT,IN cid CHAR(36),IN att BIGINT,IN ph VARCHAR(32),IN line_id BIGINT,IN campaign_id BIGINT,IN gateway_id VARCHAR(128),IN human_id BIGINT,IN call_mode VARCHAR(32),IN call_status VARCHAR(32))
BEGIN
 DECLARE phase_name VARCHAR(32);
 DECLARE ep BIGINT;
 DECLARE gen BIGINT;
 DECLARE enforce_limits BOOLEAN;
 DECLARE platform_cap BIGINT;
 DECLARE tenant_cap BIGINT;
 DECLARE line_cap BIGINT;
 DECLARE campaign_cap BIGINT;
 DECLARE gateway_cap BIGINT;
 DECLARE policy LONGTEXT;
 DECLARE scopes JSON;
 DECLARE rid CHAR(36);
 DECLARE seq BIGINT;
 DECLARE normalized_phone VARCHAR(32);
 SELECT phase,epoch,COALESCE(generation_id,0),enforce_quotas,platform_limit,tenant_default_limit INTO phase_name,ep,gen,enforce_limits,platform_cap,tenant_cap FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF phase_name<>'RUNNING' AND NOT(phase_name='ADMISSION_CLOSED' AND EXISTS(SELECT 1 FROM dial_drain_member WHERE epoch=ep AND tenant_id=tid AND call_id=REPLACE(cid,'-','') AND attempt=att)) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial admission barrier rejects new resource occupancy';
 END IF;
 SET normalized_phone=REGEXP_REPLACE(ph,'[^0-9]','');
 IF enforce_limits AND (gen=0 OR NOT EXISTS(SELECT 1 FROM dial_migration_generation WHERE id=gen AND state='READY')) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='durable quota generation is not ready';
 END IF;
 IF enforce_limits AND (att<1 OR CHAR_LENGTH(normalized_phone)<6 OR CHAR_LENGTH(normalized_phone)>15) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='invalid durable dial identity';
 END IF;
 SELECT data_json INTO policy FROM adminsetting WHERE tenant_id=tid AND section='capacity' LIMIT 1 FOR SHARE;
 IF policy IS NOT NULL AND JSON_VALID(policy) AND JSON_EXTRACT(policy,'$.max_concurrent_calls') IS NOT NULL THEN
  SET tenant_cap=CAST(JSON_UNQUOTE(JSON_EXTRACT(policy,'$.max_concurrent_calls')) AS UNSIGNED);
 END IF;
 SET tenant_cap=LEAST(platform_cap,tenant_cap);
 SET scopes=JSON_ARRAY(JSON_OBJECT('type','platform','id','0','limit',platform_cap),JSON_OBJECT('type','tenant','id',CAST(tid AS CHAR),'limit',tenant_cap));
 IF line_id IS NOT NULL THEN
  SELECT max_concurrency INTO line_cap FROM telephonyline WHERE id=line_id AND tenant_id=tid FOR SHARE;
  IF line_cap IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='reservation line is missing';END IF;
  SET scopes=JSON_ARRAY_APPEND(scopes,'$',JSON_OBJECT('type','line','id',CONCAT(tid,':',line_id),'limit',line_cap));
 END IF;
 IF campaign_id IS NOT NULL THEN
  SELECT concurrency INTO campaign_cap FROM campaign WHERE id=campaign_id AND tenant_id=tid FOR SHARE;
  IF campaign_cap IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='reservation campaign is missing';END IF;
  SET scopes=JSON_ARRAY_APPEND(scopes,'$',JSON_OBJECT('type','campaign','id',CONCAT(tid,':',campaign_id),'limit',campaign_cap));
 END IF;
 IF gateway_id IS NOT NULL THEN
  SELECT capacity INTO gateway_cap FROM gatewaynode WHERE id=gateway_id FOR SHARE;
  IF gateway_cap IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='reservation gateway is missing';END IF;
  SET scopes=JSON_ARRAY_APPEND(scopes,'$',JSON_OBJECT('type','gateway','id',gateway_id,'limit',gateway_cap));
 END IF;
 IF human_id IS NOT NULL AND (call_mode='HUMAN_ONLY' OR call_status IN('IN_HUMAN','HANDOFF_TRANSFERRING')) THEN
  SET scopes=JSON_ARRAY_APPEND(scopes,'$',JSON_OBJECT('type','human','id',CONCAT(tid,':',human_id),'limit',1));
 END IF;
 SET rid=UUID();
 SELECT COALESCE(MAX(admission_sequence),0)+1 INTO seq FROM dial_reservation WHERE tenant_id=tid AND call_id=cid AND attempt=att;
 INSERT INTO dial_phone_guard(generation_id,tenant_id,phone_key,reservation_id,enforced) VALUES(gen,tid,normalized_phone,rid,enforce_limits);
 CALL dial_apply_quota(gen,scopes,1,enforce_limits);
 INSERT INTO dial_reservation(reservation_id,generation_id,tenant_id,call_id,attempt,admission_sequence,epoch,state,dimensions,phone_key)
 VALUES(rid,gen,tid,cid,att,seq,ep,'RESERVED',scopes,normalized_phone);
END$$

CREATE TRIGGER dial_reservation_insert AFTER INSERT ON callsession FOR EACH ROW PRECEDES dial_call_insert
BEGIN
 IF NEW.status IN('DIALING','ANSWERED','IN_AI','WAITING_HUMAN','HANDOFF_TRANSFERRING','IN_HUMAN') THEN
  CALL dial_acquire_reservation(NEW.tenant_id,NEW.id,NEW.attempts,NEW.phone,NEW.telephony_line_id,NEW.campaign_id,NEW.gateway_node_id,NEW.human_agent_id,NEW.mode,NEW.status);
 END IF;
END$$

CREATE TRIGGER dial_reservation_update AFTER UPDATE ON callsession FOR EACH ROW PRECEDES dial_call_update
BEGIN
 DECLARE old_active BOOLEAN;
 DECLARE new_active BOOLEAN;
 DECLARE changed BOOLEAN;
 SET old_active=OLD.status IN('DIALING','ANSWERED','IN_AI','WAITING_HUMAN','HANDOFF_TRANSFERRING','IN_HUMAN');
 SET new_active=NEW.status IN('DIALING','ANSWERED','IN_AI','WAITING_HUMAN','HANDOFF_TRANSFERRING','IN_HUMAN');
 SET changed=NOT(OLD.tenant_id<=>NEW.tenant_id AND OLD.attempts<=>NEW.attempts AND OLD.phone<=>NEW.phone AND OLD.telephony_line_id<=>NEW.telephony_line_id AND OLD.campaign_id<=>NEW.campaign_id AND OLD.gateway_node_id<=>NEW.gateway_node_id AND OLD.human_agent_id<=>NEW.human_agent_id AND OLD.mode<=>NEW.mode)
  OR (OLD.status IN('IN_HUMAN','HANDOFF_TRANSFERRING'))<>(NEW.status IN('IN_HUMAN','HANDOFF_TRANSFERRING'));
 IF old_active AND (NOT new_active OR changed) THEN CALL dial_release_reservation(OLD.tenant_id,OLD.id,OLD.attempts);END IF;
 IF new_active AND (NOT old_active OR changed) THEN
  CALL dial_acquire_reservation(NEW.tenant_id,NEW.id,NEW.attempts,NEW.phone,NEW.telephony_line_id,NEW.campaign_id,NEW.gateway_node_id,NEW.human_agent_id,NEW.mode,NEW.status);
 END IF;
END$$

CREATE TRIGGER dial_reservation_delete BEFORE DELETE ON callsession FOR EACH ROW PRECEDES dial_call_delete
BEGIN
 IF OLD.status IN('DIALING','ANSWERED','IN_AI','WAITING_HUMAN','HANDOFF_TRANSFERRING','IN_HUMAN') THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='active dial facts cannot be deleted to release resources';
 END IF;
END$$
DELIMITER ;
