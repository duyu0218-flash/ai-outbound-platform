-- Materialized facts remain opt-in until a frozen generation is verified.
CREATE TABLE dial_migration_generation (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 state VARCHAR(16) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 verified_at DATETIME(3),
 evidence LONGTEXT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
ALTER TABLE dial_admission_control ADD COLUMN generation_id BIGINT NULL,
 ADD COLUMN daily_read VARCHAR(16) NOT NULL DEFAULT 'legacy',
 ADD COLUMN admission_mode VARCHAR(16) NOT NULL DEFAULT 'lock_compat';

CREATE TABLE dial_daily_counter (
 generation_id BIGINT NOT NULL,
 scope_type VARCHAR(8) NOT NULL,
 tenant_id BIGINT NOT NULL,
 phone VARCHAR(32) NOT NULL DEFAULT '',
 window_start DATETIME(3) NOT NULL,
 timezone_revision VARCHAR(128) NOT NULL,
 legacy_component BIGINT NOT NULL,
 event_component BIGINT NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0,
 ready BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(generation_id,scope_type,tenant_id,phone,window_start),
 CHECK(legacy_component>=0 AND event_component>=0),
 KEY dial_daily_window(tenant_id,scope_type,phone,ready)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE dial_fact_revision (
 fact_type VARCHAR(8) NOT NULL,
 fact_id VARCHAR(64) NOT NULL,
 revision BIGINT NOT NULL,
 before_fact LONGTEXT,
 after_fact LONGTEXT,
 changed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(fact_type,fact_id,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE dial_fact_head (
 fact_type VARCHAR(8) NOT NULL,
 fact_id VARCHAR(64) NOT NULL,
 revision BIGINT NOT NULL,
 PRIMARY KEY(fact_type,fact_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

DELIMITER $$
CREATE PROCEDURE dial_fact_change(IN kind VARCHAR(8),IN fid VARCHAR(64),IN old_fact LONGTEXT,IN new_fact LONGTEXT)
BEGIN
 DECLARE rev BIGINT;
 INSERT INTO dial_fact_head(fact_type,fact_id,revision) VALUES(kind,fid,1)
 ON DUPLICATE KEY UPDATE revision=revision+1;
 SELECT revision INTO rev FROM dial_fact_head WHERE fact_type=kind AND fact_id=fid;
 INSERT INTO dial_fact_revision(fact_type,fact_id,revision,before_fact,after_fact)
 VALUES(kind,fid,rev,old_fact,new_fact);
END$$

CREATE PROCEDURE dial_call_delta(IN tid BIGINT,IN ph VARCHAR(32),IN stamp DATETIME(3),IN made DATETIME(3),IN tries BIGINT,IN events_sign BIGINT,IN cid CHAR(36),IN multiplier BIGINT)
BEGIN
 UPDATE dial_daily_counter SET
 legacy_component=legacy_component+multiplier*IF(scope_type='tenant',IF(stamp>=window_start,tries,0),IF(made>=window_start,tries,0)),
 event_component=event_component+IF(events_sign=0,0,events_sign*(SELECT COUNT(*) FROM callevent e WHERE e.call_session_id=cid AND e.event_type='dial_attempt_claimed' AND e.created_at>=window_start)),
 revision=revision+1
 WHERE tenant_id=tid AND ready=TRUE AND (scope_type='tenant' OR scope_type='phone' AND phone=ph)
 AND generation_id IN(SELECT id FROM dial_migration_generation WHERE state='READY')
 ORDER BY generation_id,scope_type,tenant_id,phone,window_start;
END$$

CREATE PROCEDURE dial_event_delta(IN cid CHAR(36),IN etype VARCHAR(64),IN stamp DATETIME(3),IN multiplier BIGINT)
BEGIN
 DECLARE tid BIGINT;
 DECLARE ph VARCHAR(32);
 IF etype='dial_attempt_claimed' THEN
  SELECT tenant_id,phone INTO tid,ph FROM callsession WHERE id=cid;
  UPDATE dial_daily_counter SET event_component=event_component+multiplier,revision=revision+1
   WHERE tenant_id=tid AND ready=TRUE AND stamp>=window_start
   AND (scope_type='tenant' OR scope_type='phone' AND phone=ph)
   AND generation_id IN(SELECT id FROM dial_migration_generation WHERE state='READY')
   ORDER BY generation_id,scope_type,tenant_id,phone,window_start;
 END IF;
END$$

CREATE TRIGGER dial_call_insert AFTER INSERT ON callsession FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business facts';
 END IF;
 CALL dial_call_delta(NEW.tenant_id,NEW.phone,NEW.started_at,NEW.created_at,NEW.attempts,0,NEW.id,1);
 CALL dial_fact_change('call',NEW.id,NULL,JSON_OBJECT('tenant',NEW.tenant_id,'phone',NEW.phone,'started',NEW.started_at,'created',NEW.created_at,'attempts',NEW.attempts));
END$$

CREATE TRIGGER dial_call_update AFTER UPDATE ON callsession FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business facts';
 END IF;
 IF NOT(OLD.tenant_id<=>NEW.tenant_id AND OLD.phone<=>NEW.phone AND OLD.started_at<=>NEW.started_at AND OLD.created_at<=>NEW.created_at AND OLD.attempts<=>NEW.attempts) THEN
  CALL dial_call_delta(OLD.tenant_id,OLD.phone,OLD.started_at,OLD.created_at,OLD.attempts,IF(OLD.tenant_id<>NEW.tenant_id OR OLD.phone<>NEW.phone,-1,0),OLD.id,-1);
  CALL dial_call_delta(NEW.tenant_id,NEW.phone,NEW.started_at,NEW.created_at,NEW.attempts,IF(OLD.tenant_id<>NEW.tenant_id OR OLD.phone<>NEW.phone,1,0),NEW.id,1);
  CALL dial_fact_change('call',NEW.id,JSON_OBJECT('tenant',OLD.tenant_id,'phone',OLD.phone,'started',OLD.started_at,'created',OLD.created_at,'attempts',OLD.attempts),
   JSON_OBJECT('tenant',NEW.tenant_id,'phone',NEW.phone,'started',NEW.started_at,'created',NEW.created_at,'attempts',NEW.attempts));
 END IF;
END$$

CREATE TRIGGER dial_call_delete BEFORE DELETE ON callsession FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business facts';
 END IF;
 -- FK cascades do not fire child triggers; remove joined E before the cascade.
 CALL dial_call_delta(OLD.tenant_id,OLD.phone,OLD.started_at,OLD.created_at,OLD.attempts,-1,OLD.id,-1);
 CALL dial_fact_change('call',OLD.id,JSON_OBJECT('tenant',OLD.tenant_id,'phone',OLD.phone,'started',OLD.started_at,'created',OLD.created_at,'attempts',OLD.attempts),NULL);
END$$

CREATE TRIGGER dial_event_insert AFTER INSERT ON callevent FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business facts';
 END IF;
 IF NEW.event_type='dial_attempt_claimed' THEN
  CALL dial_event_delta(NEW.call_session_id,NEW.event_type,NEW.created_at,1);
  CALL dial_fact_change('event',CAST(NEW.id AS CHAR),NULL,JSON_OBJECT('call',NEW.call_session_id,'type',NEW.event_type,'created',NEW.created_at));
 END IF;
END$$

CREATE TRIGGER dial_event_update AFTER UPDATE ON callevent FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business facts';
 END IF;
 IF OLD.event_type='dial_attempt_claimed' OR NEW.event_type='dial_attempt_claimed' THEN
  CALL dial_event_delta(OLD.call_session_id,OLD.event_type,OLD.created_at,-1);
  CALL dial_event_delta(NEW.call_session_id,NEW.event_type,NEW.created_at,1);
  CALL dial_fact_change('event',CAST(NEW.id AS CHAR),JSON_OBJECT('call',OLD.call_session_id,'type',OLD.event_type,'created',OLD.created_at),JSON_OBJECT('call',NEW.call_session_id,'type',NEW.event_type,'created',NEW.created_at));
 END IF;
END$$

CREATE TRIGGER dial_event_delete BEFORE DELETE ON callevent FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business facts';
 END IF;
 IF OLD.event_type='dial_attempt_claimed' THEN
  CALL dial_event_delta(OLD.call_session_id,OLD.event_type,OLD.created_at,-1);
  CALL dial_fact_change('event',CAST(OLD.id AS CHAR),JSON_OBJECT('call',OLD.call_session_id,'type',OLD.event_type,'created',OLD.created_at),NULL);
 END IF;
END$$
CREATE TRIGGER dial_freeze_contact_insert BEFORE INSERT ON contact FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_contact_update BEFORE UPDATE ON contact FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_contact_delete BEFORE DELETE ON contact FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_tenant_insert BEFORE INSERT ON tenant FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_tenant_update BEFORE UPDATE ON tenant FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_tenant_delete BEFORE DELETE ON tenant FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_adminsetting_insert BEFORE INSERT ON adminsetting FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_adminsetting_update BEFORE UPDATE ON adminsetting FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_adminsetting_delete BEFORE DELETE ON adminsetting FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_campaign_insert BEFORE INSERT ON campaign FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_campaign_update BEFORE UPDATE ON campaign FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_campaign_delete BEFORE DELETE ON campaign FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_phonesuppression_insert BEFORE INSERT ON phonesuppression FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_phonesuppression_update BEFORE UPDATE ON phonesuppression FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_phonesuppression_delete BEFORE DELETE ON phonesuppression FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_telephonyline_insert BEFORE INSERT ON telephonyline FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_telephonyline_update BEFORE UPDATE ON telephonyline FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_telephonyline_delete BEFORE DELETE ON telephonyline FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_gatewaynode_insert BEFORE INSERT ON gatewaynode FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_gatewaynode_update BEFORE UPDATE ON gatewaynode FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_gatewaynode_delete BEFORE DELETE ON gatewaynode FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_conversationstate_insert BEFORE INSERT ON conversationstate FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_conversationstate_update BEFORE UPDATE ON conversationstate FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_conversationstate_delete BEFORE DELETE ON conversationstate FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_handoffrequest_insert BEFORE INSERT ON handoffrequest FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_handoffrequest_update BEFORE UPDATE ON handoffrequest FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_handoffrequest_delete BEFORE DELETE ON handoffrequest FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_callusage_insert BEFORE INSERT ON callusage FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_callusage_update BEFORE UPDATE ON callusage FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
CREATE TRIGGER dial_freeze_callusage_delete BEFORE DELETE ON callusage FOR EACH ROW
BEGIN
 DECLARE maintenance_phase VARCHAR(32);
 SELECT phase INTO maintenance_phase FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF maintenance_phase='FROZEN' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes business writers';
 END IF;
END$$
DELIMITER ;
