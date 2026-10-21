-- Preserve exact source LONGTEXT phone identities, including retention redaction.
-- Hashes locate keys; every read/delta still compares the complete binary value.
-- A hash collision fails the unique insert rather than merging two identities.
ALTER TABLE dial_daily_counter DROP PRIMARY KEY,DROP INDEX dial_daily_window,
 MODIFY COLUMN phone LONGTEXT NOT NULL,
 ADD COLUMN phone_digest BINARY(32) GENERATED ALWAYS AS(UNHEX(SHA2(phone,256))) STORED,
 ADD PRIMARY KEY(generation_id,scope_type,tenant_id,phone_digest,window_start),
 ADD KEY dial_daily_window(tenant_id,scope_type,phone_digest,ready);

DELIMITER $$
DROP PROCEDURE dial_call_delta$$
CREATE PROCEDURE dial_call_delta(IN tid BIGINT,IN ph LONGTEXT,IN stamp DATETIME(3),IN made DATETIME(3),IN tries BIGINT,IN events_sign BIGINT,IN cid CHAR(36),IN multiplier BIGINT)
BEGIN
 UPDATE dial_daily_counter SET
 legacy_component=legacy_component+multiplier*IF(scope_type='tenant',IF(stamp>=window_start,tries,0),IF(made>=window_start,tries,0)),
 event_component=event_component+IF(events_sign=0,0,events_sign*(SELECT COUNT(*) FROM callevent e WHERE e.call_session_id=cid AND e.event_type='dial_attempt_claimed' AND e.created_at>=window_start)),
 revision=revision+1
 WHERE tenant_id=tid AND ready=TRUE AND (scope_type='tenant' OR scope_type='phone' AND phone_digest=UNHEX(SHA2(ph,256)) AND phone=ph)
 AND generation_id IN(SELECT id FROM dial_migration_generation WHERE state='READY')
 ORDER BY generation_id,scope_type,tenant_id,phone,window_start;
END$$

DROP PROCEDURE dial_event_delta$$
CREATE PROCEDURE dial_event_delta(IN cid CHAR(36),IN etype VARCHAR(64),IN stamp DATETIME(3),IN multiplier BIGINT)
BEGIN
 DECLARE tid BIGINT;
 DECLARE ph LONGTEXT;
 IF etype='dial_attempt_claimed' THEN
  SELECT tenant_id,phone INTO tid,ph FROM callsession WHERE id=cid;
  UPDATE dial_daily_counter SET event_component=event_component+multiplier,revision=revision+1
   WHERE tenant_id=tid AND ready=TRUE AND stamp>=window_start
   AND (scope_type='tenant' OR scope_type='phone' AND phone_digest=UNHEX(SHA2(ph,256)) AND phone=ph)
   AND generation_id IN(SELECT id FROM dial_migration_generation WHERE state='READY')
   ORDER BY generation_id,scope_type,tenant_id,phone,window_start;
 END IF;
END$$

DROP PROCEDURE dial_acquire_reservation$$
CREATE PROCEDURE dial_acquire_reservation(IN tid BIGINT,IN cid CHAR(36),IN att BIGINT,IN ph LONGTEXT,IN line_id BIGINT,IN campaign_id BIGINT,IN gateway_id VARCHAR(128),IN human_id BIGINT,IN call_mode VARCHAR(32),IN call_status VARCHAR(32))
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
 DECLARE raw_digits LONGTEXT;
 SELECT phase,epoch,COALESCE(generation_id,0),enforce_quotas,platform_limit,tenant_default_limit INTO phase_name,ep,gen,enforce_limits,platform_cap,tenant_cap FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF phase_name<>'RUNNING' AND NOT(phase_name='ADMISSION_CLOSED' AND EXISTS(SELECT 1 FROM dial_drain_member WHERE epoch=ep AND tenant_id=tid AND call_id=REPLACE(cid,'-','') AND attempt=att)) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial admission barrier rejects new resource occupancy';
 END IF;
 SET raw_digits=REGEXP_REPLACE(ph,'[^0-9]','');
 SET normalized_phone=IF(CHAR_LENGTH(raw_digits)>32,LEFT(SHA2(raw_digits,256),32),raw_digits);
 IF enforce_limits AND (gen=0 OR NOT EXISTS(SELECT 1 FROM dial_migration_generation WHERE id=gen AND state='READY')) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='durable quota generation is not ready';
 END IF;
 IF enforce_limits AND (att<1 OR CHAR_LENGTH(raw_digits)<6 OR CHAR_LENGTH(raw_digits)>15) THEN
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

DELIMITER ;
