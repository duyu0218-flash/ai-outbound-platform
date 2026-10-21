-- Offline, drained installation. Reads stay opt-in; every writer projects facts.
DELIMITER $$
CREATE PROCEDURE voice_assert_counter_install()
BEGIN
 IF EXISTS(SELECT 1 FROM voice_attempts WHERE state!='ended') THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='voice scope counters require a drained offline migration';
 END IF;
END$$
CALL voice_assert_counter_install()$$
DROP PROCEDURE voice_assert_counter_install$$
DELIMITER ;

CREATE INDEX voice_nonce_expiry ON voice_nonces(expires);

CREATE TABLE voice_scope_counter (
 scope_type VARCHAR(8) NOT NULL,
 tenant_id BIGINT NOT NULL,
 line_id VARCHAR(255) NOT NULL DEFAULT '',
 bucket_kind VARCHAR(16) NOT NULL,
 bucket_start BIGINT NOT NULL,
 fact_count BIGINT NOT NULL,
 cost BIGINT NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1,
 PRIMARY KEY(scope_type,tenant_id,line_id,bucket_kind,bucket_start),
 CHECK(fact_count>=0 AND cost>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- For ended rows, created>=cutoff OR ended>=cutoff is exactly
-- MAX(created,COALESCE(ended,created))>=cutoff. Hour-aligned buckets preserve
-- both hour/day cuts, including future timestamps, without double charging.
INSERT INTO voice_scope_counter(scope_type,tenant_id,line_id,bucket_kind,bucket_start,fact_count,cost)
SELECT s.typ,IF(s.typ='global',0,a.tenant),IF(s.typ='line',a.line,''),b.kind,
 CASE b.kind WHEN 'active' THEN 0 WHEN 'created_day' THEN FLOOR(a.created/86400)*86400 ELSE FLOOR(GREATEST(a.created,COALESCE(a.ended,a.created))/3600)*3600 END,
 COUNT(*),IF(b.kind='created_day',0,SUM(a.cost))
FROM voice_attempts a
JOIN (SELECT 'global' typ UNION ALL SELECT 'tenant' UNION ALL SELECT 'line') s
JOIN (SELECT 'active' kind UNION ALL SELECT 'created_day' UNION ALL SELECT 'ended_hour') b
WHERE (b.kind='active' AND a.state!='ended') OR (b.kind='created_day' AND a.send_state!='REJECTED') OR (b.kind='ended_hour' AND a.state='ended')
GROUP BY s.typ,IF(s.typ='global',0,a.tenant),IF(s.typ='line',a.line,''),b.kind,
 CASE b.kind WHEN 'active' THEN 0 WHEN 'created_day' THEN FLOOR(a.created/86400)*86400 ELSE FLOOR(GREATEST(a.created,COALESCE(a.ended,a.created))/3600)*3600 END;

DELIMITER $$
CREATE PROCEDURE voice_counter_delta(IN tid BIGINT,IN lid VARCHAR(255),IN made DOUBLE,IN finished DOUBLE,IN st VARCHAR(32),IN sending VARCHAR(32),IN charge BIGINT,IN delta INT)
BEGIN
 DECLARE phase_name VARCHAR(32);
 SELECT phase INTO phase_name FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF phase_name='FROZEN' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes voice facts';END IF;
 IF delta=-1 THEN
  UPDATE voice_scope_counter SET fact_count=fact_count-1,cost=cost-IF(bucket_kind='created_day',0,charge),revision=revision+1
  WHERE ((scope_type='global' AND tenant_id=0 AND line_id='') OR (scope_type='tenant' AND tenant_id=tid AND line_id='') OR (scope_type='line' AND tenant_id=tid AND line_id=lid))
   AND ((bucket_kind='active' AND bucket_start=0 AND st!='ended')
    OR (bucket_kind='created_day' AND bucket_start=FLOOR(made/86400)*86400 AND sending!='REJECTED')
    OR (bucket_kind='ended_hour' AND bucket_start=FLOOR(GREATEST(made,COALESCE(finished,made))/3600)*3600 AND st='ended'))
  ORDER BY scope_type,tenant_id,line_id,bucket_kind,bucket_start;
  IF ROW_COUNT()<>3*(1+IF(sending!='REJECTED',1,0)) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='voice counter release is missing original facts';END IF;
 ELSEIF delta=1 THEN
 INSERT INTO voice_scope_counter(scope_type,tenant_id,line_id,bucket_kind,bucket_start,fact_count,cost)
 SELECT s.typ,IF(s.typ='global',0,tid),IF(s.typ='line',lid,''),b.kind,
 CASE b.kind WHEN 'active' THEN 0 WHEN 'created_day' THEN FLOOR(made/86400)*86400 ELSE FLOOR(GREATEST(made,COALESCE(finished,made))/3600)*3600 END,
 delta,IF(b.kind='created_day',0,delta*charge)
 FROM (SELECT 'global' typ UNION ALL SELECT 'tenant' UNION ALL SELECT 'line') s
 JOIN (SELECT 'active' kind UNION ALL SELECT 'created_day' UNION ALL SELECT 'ended_hour') b
 WHERE (b.kind='active' AND st!='ended') OR (b.kind='created_day' AND sending!='REJECTED') OR (b.kind='ended_hour' AND st='ended')
 ORDER BY s.typ,b.kind
 ON DUPLICATE KEY UPDATE fact_count=fact_count+VALUES(fact_count),cost=cost+VALUES(cost),revision=revision+1;
 ELSE SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='invalid voice fact delta';END IF;
END$$

CREATE TRIGGER voice_counter_insert AFTER INSERT ON voice_attempts FOR EACH ROW
BEGIN
 CALL voice_counter_delta(NEW.tenant,NEW.line,NEW.created,NEW.ended,NEW.state,NEW.send_state,NEW.cost,1);
 CALL dial_fact_change('voice',SHA2(NEW.uuid,256),NULL,JSON_OBJECT('uuid',NEW.uuid,'tenant',NEW.tenant,'line',NEW.line,'created',NEW.created,'ended',NEW.ended,'state',NEW.state,'send_state',NEW.send_state,'cost',NEW.cost));
END$$
CREATE TRIGGER voice_counter_update AFTER UPDATE ON voice_attempts FOR EACH ROW
BEGIN
 DECLARE phase_name VARCHAR(32);
 SELECT phase INTO phase_name FROM dial_admission_control WHERE id=1 FOR SHARE;
 IF phase_name='FROZEN' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dial maintenance freezes voice facts';END IF;
 IF NOT(OLD.call_id<=>NEW.call_id AND OLD.attempt<=>NEW.attempt AND OLD.uuid<=>NEW.uuid AND OLD.digest<=>NEW.digest AND OLD.protocol_version<=>NEW.protocol_version) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='voice command identity and semantics are immutable';
 END IF;
 IF NOT(OLD.tenant<=>NEW.tenant AND OLD.line<=>NEW.line AND OLD.created<=>NEW.created AND OLD.ended<=>NEW.ended AND (OLD.state='ended')<=>(NEW.state='ended') AND (OLD.send_state='REJECTED')<=>(NEW.send_state='REJECTED') AND OLD.cost<=>NEW.cost) THEN
  CALL voice_counter_delta(OLD.tenant,OLD.line,OLD.created,OLD.ended,OLD.state,OLD.send_state,OLD.cost,-1);
  CALL voice_counter_delta(NEW.tenant,NEW.line,NEW.created,NEW.ended,NEW.state,NEW.send_state,NEW.cost,1);
  CALL dial_fact_change('voice',SHA2(NEW.uuid,256),JSON_OBJECT('uuid',OLD.uuid,'tenant',OLD.tenant,'line',OLD.line,'created',OLD.created,'ended',OLD.ended,'state',OLD.state,'send_state',OLD.send_state,'cost',OLD.cost),JSON_OBJECT('uuid',NEW.uuid,'tenant',NEW.tenant,'line',NEW.line,'created',NEW.created,'ended',NEW.ended,'state',NEW.state,'send_state',NEW.send_state,'cost',NEW.cost));
 END IF;
END$$
CREATE TRIGGER voice_counter_delete BEFORE DELETE ON voice_attempts FOR EACH ROW
BEGIN
 IF OLD.state!='ended' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='cannot delete an active voice reservation';END IF;
 CALL voice_counter_delta(OLD.tenant,OLD.line,OLD.created,OLD.ended,OLD.state,OLD.send_state,OLD.cost,-1);
 CALL dial_fact_change('voice',SHA2(OLD.uuid,256),JSON_OBJECT('uuid',OLD.uuid,'tenant',OLD.tenant,'line',OLD.line,'created',OLD.created,'ended',OLD.ended,'state',OLD.state,'send_state',OLD.send_state,'cost',OLD.cost),NULL);
END$$
DELIMITER ;
