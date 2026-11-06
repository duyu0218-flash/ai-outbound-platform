-- Synchronous, transaction-bound facts: no cache publication window in dial admission.
-- Deploy with the old application stopped. Flyway backfills before enabling the delta triggers.
CREATE TABLE ops_call_statistics (
 call_id CHAR(36) NOT NULL PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 campaign_id BIGINT NULL,
 contact_id BIGINT NULL,
 attempts BIGINT NOT NULL DEFAULT 0,
 answered_attempts BIGINT NOT NULL DEFAULT 0,
 seconds DOUBLE NOT NULL DEFAULT 0,
 analyzed BOOLEAN NOT NULL DEFAULT FALSE,
 terminal BOOLEAN NOT NULL DEFAULT FALSE,
 finished BOOLEAN NOT NULL DEFAULT FALSE,
 interested BOOLEAN NOT NULL DEFAULT FALSE,
 successful BOOLEAN NOT NULL DEFAULT FALSE,
 handoffs BIGINT NOT NULL DEFAULT 0,
 taken_handoffs BIGINT NOT NULL DEFAULT 0,
 started_at DATETIME(3) NULL,
 ended_at DATETIME(3) NULL,
 KEY ops_stats_contact(tenant_id,contact_id),
 KEY ops_stats_campaign_start(tenant_id,campaign_id,started_at),
 KEY ops_stats_campaign_end(tenant_id,campaign_id,ended_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE ops_campaign_statistics (
 tenant_id BIGINT NOT NULL,
 campaign_id BIGINT NOT NULL,
 sessions BIGINT NOT NULL DEFAULT 0,
 dialed BIGINT NOT NULL DEFAULT 0,
 answered BIGINT NOT NULL DEFAULT 0,
 connected_sessions BIGINT NOT NULL DEFAULT 0,
 finished BIGINT NOT NULL DEFAULT 0,
 interested BIGINT NOT NULL DEFAULT 0,
 successful BIGINT NOT NULL DEFAULT 0,
 seconds DOUBLE NOT NULL DEFAULT 0,
 entered BIGINT NOT NULL DEFAULT 0,
 taken BIGINT NOT NULL DEFAULT 0,
 analyzed_total BIGINT NOT NULL DEFAULT 0,
 analyzed_attempts BIGINT NOT NULL DEFAULT 0,
 analyzed_answered BIGINT NOT NULL DEFAULT 0,
 analyzed_connected BIGINT NOT NULL DEFAULT 0,
 analyzed_interested BIGINT NOT NULL DEFAULT 0,
 analyzed_successful BIGINT NOT NULL DEFAULT 0,
 dialed_contacts BIGINT NOT NULL DEFAULT 0,
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(tenant_id,campaign_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE ops_campaign_contact_statistics (
 tenant_id BIGINT NOT NULL,
 campaign_id BIGINT NOT NULL,
 contact_id BIGINT NOT NULL,
 dialed_sessions BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,campaign_id,contact_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

INSERT INTO ops_call_statistics(call_id,tenant_id,campaign_id,contact_id,attempts,answered_attempts,seconds,analyzed,terminal,finished,interested,successful,handoffs,taken_handoffs,started_at,ended_at)
SELECT c.id,c.tenant_id,c.campaign_id,c.contact_id,c.attempts,COALESCE(u.answered,0),COALESCE(u.seconds,0),a.id IS NOT NULL,
 c.status IN ('COMPLETED','FAILED','BUSY','NO_ANSWER','CANCELED'),c.status IN ('COMPLETED','FAILED','NO_ANSWER','BUSY','VOICEMAIL'),
 COALESCE(a.result_code IN ('qualified_lead','interested','callback_confirmed'),0),COALESCE(a.result_code='qualified_lead',0),COALESCE(h.entered,0),COALESCE(h.taken,0),c.started_at,c.finished_at
FROM callsession c
LEFT JOIN (SELECT tenant_id,call_session_id,SUM(answered_at IS NOT NULL) AS answered,SUM(COALESCE(telephony_seconds,0)) AS seconds FROM callusage GROUP BY tenant_id,call_session_id) u ON u.tenant_id=c.tenant_id AND u.call_session_id=c.id
LEFT JOIN callanalysis a ON a.tenant_id=c.tenant_id AND a.call_session_id=c.id
LEFT JOIN (SELECT tenant_id,call_session_id,COUNT(*) AS entered,SUM(state IN ('ACCEPTED','COMPLETED')) AS taken FROM handoffrequest GROUP BY tenant_id,call_session_id) h ON h.tenant_id=c.tenant_id AND h.call_session_id=c.id;

INSERT INTO ops_campaign_statistics(tenant_id,campaign_id,sessions,dialed,answered,connected_sessions,finished,interested,successful,seconds,entered,taken,analyzed_total,analyzed_attempts,analyzed_answered,analyzed_connected,analyzed_interested,analyzed_successful)
SELECT s.tenant_id,s.campaign_id,SUM(1),SUM(s.attempts),SUM(s.answered_attempts),SUM((s.answered_attempts>0)),SUM(s.finished),SUM(s.interested),SUM(s.successful),SUM(s.seconds),SUM((s.handoffs>0)),SUM((s.taken_handoffs>0)),SUM((s.terminal AND s.analyzed)),SUM(IF((s.terminal AND s.analyzed),s.attempts,0)),SUM(IF((s.terminal AND s.analyzed),s.answered_attempts,0)),SUM(IF((s.terminal AND s.analyzed) AND s.answered_attempts>0,1,0)),SUM(IF((s.terminal AND s.analyzed),s.interested,0)),SUM(IF((s.terminal AND s.analyzed),s.successful,0)) FROM ops_call_statistics s WHERE s.campaign_id IS NOT NULL GROUP BY s.tenant_id,s.campaign_id;
INSERT INTO ops_campaign_contact_statistics(tenant_id,campaign_id,contact_id,dialed_sessions)
SELECT tenant_id,campaign_id,contact_id,COUNT(*) FROM ops_call_statistics WHERE campaign_id IS NOT NULL AND contact_id IS NOT NULL AND attempts>0 GROUP BY tenant_id,campaign_id,contact_id;
UPDATE ops_campaign_statistics s SET dialed_contacts=(SELECT COUNT(*) FROM ops_campaign_contact_statistics c WHERE c.tenant_id=s.tenant_id AND c.campaign_id=s.campaign_id AND c.dialed_sessions>0);

CREATE TABLE ops_dial_attempt_fact (
 event_id BIGINT NOT NULL PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 phone LONGTEXT NOT NULL,
 phone_digest BINARY(32) GENERATED ALWAYS AS (UNHEX(SHA2(phone,256))) STORED,
 call_id CHAR(36) NOT NULL,
 attempt BIGINT NULL,
 claimed_at DATETIME(3) NOT NULL,
 answered BOOLEAN NOT NULL DEFAULT FALSE,
 KEY ops_attempt_phone_time(tenant_id,phone_digest,claimed_at),
 KEY ops_attempt_call(call_id,tenant_id,attempt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
INSERT INTO ops_dial_attempt_fact(event_id,tenant_id,phone,call_id,attempt,claimed_at,answered)
SELECT e.id,c.tenant_id,c.phone,c.id,CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(e.payload),e.payload,'{}'),'$.attempt')) REGEXP '^[0-9]{1,9}$' THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(e.payload),e.payload,'{}'),'$.attempt')) AS UNSIGNED) ELSE NULL END,e.created_at,
 EXISTS(SELECT 1 FROM callusage u WHERE u.tenant_id=c.tenant_id AND u.call_session_id=c.id AND u.attempt=JSON_EXTRACT(IF(JSON_VALID(e.payload),e.payload,'{}'),'$.attempt') AND u.answered_at IS NOT NULL)
FROM callevent e JOIN callsession c ON c.id=e.call_session_id WHERE e.event_type='dial_attempt_claimed';

DELIMITER $$
CREATE PROCEDURE ops_campaign_contact_delta(IN tid BIGINT,IN gid BIGINT,IN contact BIGINT,IN delta BIGINT)
BEGIN
 DECLARE before_count BIGINT;
 IF gid IS NOT NULL AND contact IS NOT NULL AND delta<>0 THEN
  INSERT IGNORE INTO ops_campaign_contact_statistics(tenant_id,campaign_id,contact_id) VALUES(tid,gid,contact);
  SELECT dialed_sessions INTO before_count FROM ops_campaign_contact_statistics WHERE tenant_id=tid AND campaign_id=gid AND contact_id=contact FOR UPDATE;
  UPDATE ops_campaign_contact_statistics SET dialed_sessions=dialed_sessions+delta WHERE tenant_id=tid AND campaign_id=gid AND contact_id=contact;
  UPDATE ops_campaign_statistics SET dialed_contacts=dialed_contacts+IF(before_count+delta>0,1,0)-IF(before_count>0,1,0) WHERE tenant_id=tid AND campaign_id=gid;
 END IF;
END$$

CREATE TRIGGER ops_stats_insert AFTER INSERT ON ops_call_statistics FOR EACH ROW
BEGIN
 IF NEW.campaign_id IS NOT NULL THEN
  INSERT INTO ops_campaign_statistics(tenant_id,campaign_id,sessions,dialed,answered,connected_sessions,finished,interested,successful,seconds,entered,taken,analyzed_total,analyzed_attempts,analyzed_answered,analyzed_connected,analyzed_interested,analyzed_successful) VALUES(NEW.tenant_id,NEW.campaign_id,1*(1),1*(NEW.attempts),1*(NEW.answered_attempts),1*((NEW.answered_attempts>0)),1*(NEW.finished),1*(NEW.interested),1*(NEW.successful),1*(NEW.seconds),1*((NEW.handoffs>0)),1*((NEW.taken_handoffs>0)),1*((NEW.terminal AND NEW.analyzed)),1*(IF((NEW.terminal AND NEW.analyzed),NEW.attempts,0)),1*(IF((NEW.terminal AND NEW.analyzed),NEW.answered_attempts,0)),1*(IF((NEW.terminal AND NEW.analyzed) AND NEW.answered_attempts>0,1,0)),1*(IF((NEW.terminal AND NEW.analyzed),NEW.interested,0)),1*(IF((NEW.terminal AND NEW.analyzed),NEW.successful,0)))
  ON DUPLICATE KEY UPDATE sessions=sessions+VALUES(sessions),dialed=dialed+VALUES(dialed),answered=answered+VALUES(answered),connected_sessions=connected_sessions+VALUES(connected_sessions),finished=finished+VALUES(finished),interested=interested+VALUES(interested),successful=successful+VALUES(successful),seconds=seconds+VALUES(seconds),entered=entered+VALUES(entered),taken=taken+VALUES(taken),analyzed_total=analyzed_total+VALUES(analyzed_total),analyzed_attempts=analyzed_attempts+VALUES(analyzed_attempts),analyzed_answered=analyzed_answered+VALUES(analyzed_answered),analyzed_connected=analyzed_connected+VALUES(analyzed_connected),analyzed_interested=analyzed_interested+VALUES(analyzed_interested),analyzed_successful=analyzed_successful+VALUES(analyzed_successful),updated_at=UTC_TIMESTAMP(3);
  CALL ops_campaign_contact_delta(NEW.tenant_id,NEW.campaign_id,NEW.contact_id,1*IF(NEW.attempts>0,1,0));
 END IF;
END$$

CREATE TRIGGER ops_stats_delete AFTER DELETE ON ops_call_statistics FOR EACH ROW
BEGIN
 IF OLD.campaign_id IS NOT NULL THEN
  INSERT INTO ops_campaign_statistics(tenant_id,campaign_id,sessions,dialed,answered,connected_sessions,finished,interested,successful,seconds,entered,taken,analyzed_total,analyzed_attempts,analyzed_answered,analyzed_connected,analyzed_interested,analyzed_successful) VALUES(OLD.tenant_id,OLD.campaign_id,-1*(1),-1*(OLD.attempts),-1*(OLD.answered_attempts),-1*((OLD.answered_attempts>0)),-1*(OLD.finished),-1*(OLD.interested),-1*(OLD.successful),-1*(OLD.seconds),-1*((OLD.handoffs>0)),-1*((OLD.taken_handoffs>0)),-1*((OLD.terminal AND OLD.analyzed)),-1*(IF((OLD.terminal AND OLD.analyzed),OLD.attempts,0)),-1*(IF((OLD.terminal AND OLD.analyzed),OLD.answered_attempts,0)),-1*(IF((OLD.terminal AND OLD.analyzed) AND OLD.answered_attempts>0,1,0)),-1*(IF((OLD.terminal AND OLD.analyzed),OLD.interested,0)),-1*(IF((OLD.terminal AND OLD.analyzed),OLD.successful,0)))
  ON DUPLICATE KEY UPDATE sessions=sessions+VALUES(sessions),dialed=dialed+VALUES(dialed),answered=answered+VALUES(answered),connected_sessions=connected_sessions+VALUES(connected_sessions),finished=finished+VALUES(finished),interested=interested+VALUES(interested),successful=successful+VALUES(successful),seconds=seconds+VALUES(seconds),entered=entered+VALUES(entered),taken=taken+VALUES(taken),analyzed_total=analyzed_total+VALUES(analyzed_total),analyzed_attempts=analyzed_attempts+VALUES(analyzed_attempts),analyzed_answered=analyzed_answered+VALUES(analyzed_answered),analyzed_connected=analyzed_connected+VALUES(analyzed_connected),analyzed_interested=analyzed_interested+VALUES(analyzed_interested),analyzed_successful=analyzed_successful+VALUES(analyzed_successful),updated_at=UTC_TIMESTAMP(3);
  CALL ops_campaign_contact_delta(OLD.tenant_id,OLD.campaign_id,OLD.contact_id,-1*IF(OLD.attempts>0,1,0));
 END IF;
END$$

CREATE TRIGGER ops_stats_update AFTER UPDATE ON ops_call_statistics FOR EACH ROW
BEGIN
 IF OLD.tenant_id=NEW.tenant_id AND OLD.campaign_id<=>NEW.campaign_id THEN
  IF NEW.campaign_id IS NOT NULL THEN
   -- Status/clock changes which leave every aggregate unchanged must not take the
   -- shared campaign row lock. Contact moves retain campaign-before-contact lock order.
   -- Extrema are read from the indexed call facts.
   IF NOT(OLD.contact_id<=>NEW.contact_id AND OLD.attempts<=>NEW.attempts AND OLD.answered_attempts<=>NEW.answered_attempts AND OLD.finished<=>NEW.finished AND OLD.interested<=>NEW.interested AND OLD.successful<=>NEW.successful AND OLD.seconds<=>NEW.seconds AND (OLD.handoffs>0)=(NEW.handoffs>0) AND (OLD.taken_handoffs>0)=(NEW.taken_handoffs>0) AND (OLD.terminal AND OLD.analyzed)=(NEW.terminal AND NEW.analyzed)) THEN
   UPDATE ops_campaign_statistics SET sessions=sessions+(1)-(1),dialed=dialed+(NEW.attempts)-(OLD.attempts),answered=answered+(NEW.answered_attempts)-(OLD.answered_attempts),connected_sessions=connected_sessions+((NEW.answered_attempts>0))-((OLD.answered_attempts>0)),finished=finished+(NEW.finished)-(OLD.finished),interested=interested+(NEW.interested)-(OLD.interested),successful=successful+(NEW.successful)-(OLD.successful),seconds=seconds+(NEW.seconds)-(OLD.seconds),entered=entered+((NEW.handoffs>0))-((OLD.handoffs>0)),taken=taken+((NEW.taken_handoffs>0))-((OLD.taken_handoffs>0)),analyzed_total=analyzed_total+((NEW.terminal AND NEW.analyzed))-((OLD.terminal AND OLD.analyzed)),analyzed_attempts=analyzed_attempts+(IF((NEW.terminal AND NEW.analyzed),NEW.attempts,0))-(IF((OLD.terminal AND OLD.analyzed),OLD.attempts,0)),analyzed_answered=analyzed_answered+(IF((NEW.terminal AND NEW.analyzed),NEW.answered_attempts,0))-(IF((OLD.terminal AND OLD.analyzed),OLD.answered_attempts,0)),analyzed_connected=analyzed_connected+(IF((NEW.terminal AND NEW.analyzed) AND NEW.answered_attempts>0,1,0))-(IF((OLD.terminal AND OLD.analyzed) AND OLD.answered_attempts>0,1,0)),analyzed_interested=analyzed_interested+(IF((NEW.terminal AND NEW.analyzed),NEW.interested,0))-(IF((OLD.terminal AND OLD.analyzed),OLD.interested,0)),analyzed_successful=analyzed_successful+(IF((NEW.terminal AND NEW.analyzed),NEW.successful,0))-(IF((OLD.terminal AND OLD.analyzed),OLD.successful,0)),updated_at=UTC_TIMESTAMP(3) WHERE tenant_id=NEW.tenant_id AND campaign_id=NEW.campaign_id;
   END IF;
   IF OLD.contact_id<=>NEW.contact_id THEN
    CALL ops_campaign_contact_delta(NEW.tenant_id,NEW.campaign_id,NEW.contact_id,IF(NEW.attempts>0,1,0)-IF(OLD.attempts>0,1,0));
   ELSE
    CALL ops_campaign_contact_delta(OLD.tenant_id,OLD.campaign_id,OLD.contact_id,-IF(OLD.attempts>0,1,0));
    CALL ops_campaign_contact_delta(NEW.tenant_id,NEW.campaign_id,NEW.contact_id,IF(NEW.attempts>0,1,0));
   END IF;
  END IF;
 ELSE

  IF OLD.campaign_id IS NOT NULL THEN
   INSERT INTO ops_campaign_statistics(tenant_id,campaign_id,sessions,dialed,answered,connected_sessions,finished,interested,successful,seconds,entered,taken,analyzed_total,analyzed_attempts,analyzed_answered,analyzed_connected,analyzed_interested,analyzed_successful) VALUES(OLD.tenant_id,OLD.campaign_id,-1*(1),-1*(OLD.attempts),-1*(OLD.answered_attempts),-1*((OLD.answered_attempts>0)),-1*(OLD.finished),-1*(OLD.interested),-1*(OLD.successful),-1*(OLD.seconds),-1*((OLD.handoffs>0)),-1*((OLD.taken_handoffs>0)),-1*((OLD.terminal AND OLD.analyzed)),-1*(IF((OLD.terminal AND OLD.analyzed),OLD.attempts,0)),-1*(IF((OLD.terminal AND OLD.analyzed),OLD.answered_attempts,0)),-1*(IF((OLD.terminal AND OLD.analyzed) AND OLD.answered_attempts>0,1,0)),-1*(IF((OLD.terminal AND OLD.analyzed),OLD.interested,0)),-1*(IF((OLD.terminal AND OLD.analyzed),OLD.successful,0)))
   ON DUPLICATE KEY UPDATE sessions=sessions+VALUES(sessions),dialed=dialed+VALUES(dialed),answered=answered+VALUES(answered),connected_sessions=connected_sessions+VALUES(connected_sessions),finished=finished+VALUES(finished),interested=interested+VALUES(interested),successful=successful+VALUES(successful),seconds=seconds+VALUES(seconds),entered=entered+VALUES(entered),taken=taken+VALUES(taken),analyzed_total=analyzed_total+VALUES(analyzed_total),analyzed_attempts=analyzed_attempts+VALUES(analyzed_attempts),analyzed_answered=analyzed_answered+VALUES(analyzed_answered),analyzed_connected=analyzed_connected+VALUES(analyzed_connected),analyzed_interested=analyzed_interested+VALUES(analyzed_interested),analyzed_successful=analyzed_successful+VALUES(analyzed_successful),updated_at=UTC_TIMESTAMP(3);
   CALL ops_campaign_contact_delta(OLD.tenant_id,OLD.campaign_id,OLD.contact_id,-1*IF(OLD.attempts>0,1,0));
  END IF;

  IF NEW.campaign_id IS NOT NULL THEN
   INSERT INTO ops_campaign_statistics(tenant_id,campaign_id,sessions,dialed,answered,connected_sessions,finished,interested,successful,seconds,entered,taken,analyzed_total,analyzed_attempts,analyzed_answered,analyzed_connected,analyzed_interested,analyzed_successful) VALUES(NEW.tenant_id,NEW.campaign_id,1*(1),1*(NEW.attempts),1*(NEW.answered_attempts),1*((NEW.answered_attempts>0)),1*(NEW.finished),1*(NEW.interested),1*(NEW.successful),1*(NEW.seconds),1*((NEW.handoffs>0)),1*((NEW.taken_handoffs>0)),1*((NEW.terminal AND NEW.analyzed)),1*(IF((NEW.terminal AND NEW.analyzed),NEW.attempts,0)),1*(IF((NEW.terminal AND NEW.analyzed),NEW.answered_attempts,0)),1*(IF((NEW.terminal AND NEW.analyzed) AND NEW.answered_attempts>0,1,0)),1*(IF((NEW.terminal AND NEW.analyzed),NEW.interested,0)),1*(IF((NEW.terminal AND NEW.analyzed),NEW.successful,0)))
   ON DUPLICATE KEY UPDATE sessions=sessions+VALUES(sessions),dialed=dialed+VALUES(dialed),answered=answered+VALUES(answered),connected_sessions=connected_sessions+VALUES(connected_sessions),finished=finished+VALUES(finished),interested=interested+VALUES(interested),successful=successful+VALUES(successful),seconds=seconds+VALUES(seconds),entered=entered+VALUES(entered),taken=taken+VALUES(taken),analyzed_total=analyzed_total+VALUES(analyzed_total),analyzed_attempts=analyzed_attempts+VALUES(analyzed_attempts),analyzed_answered=analyzed_answered+VALUES(analyzed_answered),analyzed_connected=analyzed_connected+VALUES(analyzed_connected),analyzed_interested=analyzed_interested+VALUES(analyzed_interested),analyzed_successful=analyzed_successful+VALUES(analyzed_successful),updated_at=UTC_TIMESTAMP(3);
   CALL ops_campaign_contact_delta(NEW.tenant_id,NEW.campaign_id,NEW.contact_id,1*IF(NEW.attempts>0,1,0));
  END IF;

 END IF;
END$$

CREATE TRIGGER ops_stats_call_insert AFTER INSERT ON callsession FOR EACH ROW
BEGIN
 INSERT INTO ops_call_statistics(call_id,tenant_id,campaign_id,contact_id,attempts,terminal,finished,started_at,ended_at)
 VALUES(NEW.id,NEW.tenant_id,NEW.campaign_id,NEW.contact_id,NEW.attempts,NEW.status IN ('COMPLETED','FAILED','BUSY','NO_ANSWER','CANCELED'),NEW.status IN ('COMPLETED','FAILED','NO_ANSWER','BUSY','VOICEMAIL'),NEW.started_at,NEW.finished_at);
END$$
CREATE TRIGGER ops_stats_call_update AFTER UPDATE ON callsession FOR EACH ROW
BEGIN
 IF NOT(OLD.tenant_id<=>NEW.tenant_id AND OLD.campaign_id<=>NEW.campaign_id AND OLD.contact_id<=>NEW.contact_id AND OLD.attempts<=>NEW.attempts AND OLD.status<=>NEW.status AND OLD.started_at<=>NEW.started_at AND OLD.finished_at<=>NEW.finished_at) THEN
  UPDATE ops_call_statistics SET tenant_id=NEW.tenant_id,campaign_id=NEW.campaign_id,contact_id=NEW.contact_id,attempts=NEW.attempts,terminal=NEW.status IN ('COMPLETED','FAILED','BUSY','NO_ANSWER','CANCELED'),finished=NEW.status IN ('COMPLETED','FAILED','NO_ANSWER','BUSY','VOICEMAIL'),started_at=NEW.started_at,ended_at=NEW.finished_at WHERE call_id=NEW.id;
 END IF;
 IF OLD.tenant_id<>NEW.tenant_id THEN
  UPDATE ops_call_statistics SET
   answered_attempts=(SELECT COUNT(*) FROM callusage u WHERE u.call_session_id=NEW.id AND u.tenant_id=NEW.tenant_id AND u.answered_at IS NOT NULL),
   seconds=(SELECT COALESCE(SUM(u.telephony_seconds),0) FROM callusage u WHERE u.call_session_id=NEW.id AND u.tenant_id=NEW.tenant_id),
   analyzed=EXISTS(SELECT 1 FROM callanalysis a WHERE a.call_session_id=NEW.id AND a.tenant_id=NEW.tenant_id),
   interested=EXISTS(SELECT 1 FROM callanalysis a WHERE a.call_session_id=NEW.id AND a.tenant_id=NEW.tenant_id AND a.result_code IN ('qualified_lead','interested','callback_confirmed')),
   successful=EXISTS(SELECT 1 FROM callanalysis a WHERE a.call_session_id=NEW.id AND a.tenant_id=NEW.tenant_id AND a.result_code='qualified_lead'),
   handoffs=(SELECT COUNT(*) FROM handoffrequest h WHERE h.call_session_id=NEW.id AND h.tenant_id=NEW.tenant_id),
   taken_handoffs=(SELECT COUNT(*) FROM handoffrequest h WHERE h.call_session_id=NEW.id AND h.tenant_id=NEW.tenant_id AND h.state IN ('ACCEPTED','COMPLETED')) WHERE call_id=NEW.id;
 END IF;
 IF NOT(OLD.tenant_id<=>NEW.tenant_id AND OLD.phone<=>NEW.phone) THEN
  UPDATE ops_dial_attempt_fact f SET tenant_id=NEW.tenant_id,phone=NEW.phone,answered=EXISTS(SELECT 1 FROM callusage u WHERE u.tenant_id=NEW.tenant_id AND u.call_session_id=NEW.id AND u.attempt=f.attempt AND u.answered_at IS NOT NULL) WHERE f.call_id=NEW.id;
 END IF;
END$$
CREATE TRIGGER ops_stats_call_delete BEFORE DELETE ON callsession FOR EACH ROW
BEGIN
 DELETE FROM ops_dial_attempt_fact WHERE call_id=OLD.id;
 DELETE FROM ops_call_statistics WHERE call_id=OLD.id;
END$$

CREATE TRIGGER ops_stats_usage_insert AFTER INSERT ON callusage FOR EACH ROW
BEGIN
 UPDATE ops_call_statistics SET answered_attempts=answered_attempts+IF(NEW.answered_at IS NOT NULL,1,0),seconds=seconds+COALESCE(NEW.telephony_seconds,0) WHERE call_id=NEW.call_session_id AND tenant_id=NEW.tenant_id;
 UPDATE ops_dial_attempt_fact SET answered=NEW.answered_at IS NOT NULL WHERE call_id=NEW.call_session_id AND tenant_id=NEW.tenant_id AND attempt=NEW.attempt;
END$$

CREATE TRIGGER ops_stats_usage_update AFTER UPDATE ON callusage FOR EACH ROW
BEGIN
 IF OLD.call_session_id=NEW.call_session_id AND OLD.tenant_id=NEW.tenant_id THEN
  IF NOT(OLD.answered_at<=>NEW.answered_at AND OLD.telephony_seconds<=>NEW.telephony_seconds) THEN
   UPDATE ops_call_statistics SET answered_attempts=answered_attempts+IF(NEW.answered_at IS NOT NULL,1,0)-IF(OLD.answered_at IS NOT NULL,1,0),seconds=seconds+COALESCE(NEW.telephony_seconds,0)-COALESCE(OLD.telephony_seconds,0) WHERE call_id=NEW.call_session_id AND tenant_id=NEW.tenant_id;
  END IF;
 ELSE
  UPDATE ops_call_statistics SET answered_attempts=answered_attempts-IF(OLD.answered_at IS NOT NULL,1,0),seconds=seconds-COALESCE(OLD.telephony_seconds,0) WHERE call_id=OLD.call_session_id AND tenant_id=OLD.tenant_id;
  UPDATE ops_call_statistics SET answered_attempts=answered_attempts+IF(NEW.answered_at IS NOT NULL,1,0),seconds=seconds+COALESCE(NEW.telephony_seconds,0) WHERE call_id=NEW.call_session_id AND tenant_id=NEW.tenant_id;
 END IF;
 IF NOT(OLD.call_session_id<=>NEW.call_session_id AND OLD.tenant_id<=>NEW.tenant_id AND OLD.attempt<=>NEW.attempt AND (OLD.answered_at IS NULL)=(NEW.answered_at IS NULL)) THEN
  UPDATE ops_dial_attempt_fact SET answered=FALSE WHERE call_id=OLD.call_session_id AND tenant_id=OLD.tenant_id AND attempt=OLD.attempt;
  UPDATE ops_dial_attempt_fact SET answered=NEW.answered_at IS NOT NULL WHERE call_id=NEW.call_session_id AND tenant_id=NEW.tenant_id AND attempt=NEW.attempt;
 END IF;
END$$

CREATE TRIGGER ops_stats_usage_delete AFTER DELETE ON callusage FOR EACH ROW
BEGIN
 UPDATE ops_call_statistics SET answered_attempts=answered_attempts-IF(OLD.answered_at IS NOT NULL,1,0),seconds=seconds-COALESCE(OLD.telephony_seconds,0) WHERE call_id=OLD.call_session_id AND tenant_id=OLD.tenant_id;
 UPDATE ops_dial_attempt_fact SET answered=FALSE WHERE call_id=OLD.call_session_id AND tenant_id=OLD.tenant_id AND attempt=OLD.attempt;
END$$

CREATE TRIGGER ops_stats_analysis_insert AFTER INSERT ON callanalysis FOR EACH ROW
BEGIN
 UPDATE ops_call_statistics SET analyzed=TRUE,interested=NEW.result_code IN ('qualified_lead','interested','callback_confirmed'),successful=NEW.result_code='qualified_lead' WHERE call_id=NEW.call_session_id AND tenant_id=NEW.tenant_id;
END$$

CREATE TRIGGER ops_stats_analysis_update AFTER UPDATE ON callanalysis FOR EACH ROW
BEGIN
 IF OLD.call_session_id=NEW.call_session_id AND OLD.tenant_id=NEW.tenant_id THEN
  IF OLD.result_code<>NEW.result_code THEN
   UPDATE ops_call_statistics SET analyzed=TRUE,interested=NEW.result_code IN ('qualified_lead','interested','callback_confirmed'),successful=NEW.result_code='qualified_lead' WHERE call_id=NEW.call_session_id AND tenant_id=NEW.tenant_id;
  END IF;
 ELSE
  UPDATE ops_call_statistics SET analyzed=FALSE,interested=FALSE,successful=FALSE WHERE call_id=OLD.call_session_id AND tenant_id=OLD.tenant_id;
  UPDATE ops_call_statistics SET analyzed=TRUE,interested=NEW.result_code IN ('qualified_lead','interested','callback_confirmed'),successful=NEW.result_code='qualified_lead' WHERE call_id=NEW.call_session_id AND tenant_id=NEW.tenant_id;
 END IF;
END$$

CREATE TRIGGER ops_stats_analysis_delete AFTER DELETE ON callanalysis FOR EACH ROW
BEGIN
 UPDATE ops_call_statistics SET analyzed=FALSE,interested=FALSE,successful=FALSE WHERE call_id=OLD.call_session_id AND tenant_id=OLD.tenant_id;
END$$

CREATE TRIGGER ops_stats_handoff_insert AFTER INSERT ON handoffrequest FOR EACH ROW
BEGIN
 UPDATE ops_call_statistics SET handoffs=handoffs+(1),taken_handoffs=taken_handoffs+(1)*IF(NEW.state IN ('ACCEPTED','COMPLETED'),1,0) WHERE call_id=NEW.call_session_id AND tenant_id=NEW.tenant_id;
END$$

CREATE TRIGGER ops_stats_handoff_update AFTER UPDATE ON handoffrequest FOR EACH ROW
BEGIN
 IF OLD.call_session_id=NEW.call_session_id AND OLD.tenant_id=NEW.tenant_id THEN
  IF (OLD.state IN ('ACCEPTED','COMPLETED'))<>(NEW.state IN ('ACCEPTED','COMPLETED')) THEN
   UPDATE ops_call_statistics SET taken_handoffs=taken_handoffs+IF(NEW.state IN ('ACCEPTED','COMPLETED'),1,0)-IF(OLD.state IN ('ACCEPTED','COMPLETED'),1,0) WHERE call_id=NEW.call_session_id AND tenant_id=NEW.tenant_id;
  END IF;
 ELSE
  UPDATE ops_call_statistics SET handoffs=handoffs+(-1),taken_handoffs=taken_handoffs+(-1)*IF(OLD.state IN ('ACCEPTED','COMPLETED'),1,0) WHERE call_id=OLD.call_session_id AND tenant_id=OLD.tenant_id;
  UPDATE ops_call_statistics SET handoffs=handoffs+(1),taken_handoffs=taken_handoffs+(1)*IF(NEW.state IN ('ACCEPTED','COMPLETED'),1,0) WHERE call_id=NEW.call_session_id AND tenant_id=NEW.tenant_id;
 END IF;
END$$

CREATE TRIGGER ops_stats_handoff_delete AFTER DELETE ON handoffrequest FOR EACH ROW
BEGIN
 UPDATE ops_call_statistics SET handoffs=handoffs+(-1),taken_handoffs=taken_handoffs+(-1)*IF(OLD.state IN ('ACCEPTED','COMPLETED'),1,0) WHERE call_id=OLD.call_session_id AND tenant_id=OLD.tenant_id;
END$$

CREATE PROCEDURE ops_attempt_fact_put(IN eid BIGINT,IN cid CHAR(36),IN event_kind VARCHAR(64),IN stamp DATETIME(3),IN data LONGTEXT)
BEGIN
 IF event_kind='dial_attempt_claimed' THEN
  INSERT INTO ops_dial_attempt_fact(event_id,tenant_id,phone,call_id,attempt,claimed_at,answered)
  SELECT eid,c.tenant_id,c.phone,c.id,CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(data),data,'{}'),'$.attempt')) REGEXP '^[0-9]{1,9}$' THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(data),data,'{}'),'$.attempt')) AS UNSIGNED) ELSE NULL END,stamp,
   EXISTS(SELECT 1 FROM callusage u WHERE u.tenant_id=c.tenant_id AND u.call_session_id=c.id AND u.attempt=JSON_EXTRACT(IF(JSON_VALID(data),data,'{}'),'$.attempt') AND u.answered_at IS NOT NULL)
  FROM callsession c WHERE c.id=cid;
 END IF;
END$$
CREATE TRIGGER ops_attempt_event_insert AFTER INSERT ON callevent FOR EACH ROW
BEGIN
 CALL ops_attempt_fact_put(NEW.id,NEW.call_session_id,NEW.event_type,NEW.created_at,NEW.payload);
END$$
CREATE TRIGGER ops_attempt_event_update AFTER UPDATE ON callevent FOR EACH ROW
BEGIN
 IF OLD.event_type='dial_attempt_claimed' OR NEW.event_type='dial_attempt_claimed' THEN
  DELETE FROM ops_dial_attempt_fact WHERE event_id=OLD.id;
  CALL ops_attempt_fact_put(NEW.id,NEW.call_session_id,NEW.event_type,NEW.created_at,NEW.payload);
 END IF;
END$$
CREATE TRIGGER ops_attempt_event_delete AFTER DELETE ON callevent FOR EACH ROW
BEGIN
 IF OLD.event_type='dial_attempt_claimed' THEN DELETE FROM ops_dial_attempt_fact WHERE event_id=OLD.id; END IF;
END$$
DELIMITER ;
