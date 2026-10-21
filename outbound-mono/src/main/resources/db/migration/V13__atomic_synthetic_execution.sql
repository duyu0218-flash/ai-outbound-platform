DELIMITER $$
CREATE PROCEDURE voice_mock_accept(IN provider_id VARCHAR(128),IN evidence LONGTEXT,IN accepted_time DOUBLE,IN duration_sec DOUBLE)
BEGIN
 UPDATE voice_attempts SET result=evidence,send_evidence=evidence,send_state='PBX_STARTED',state='active',observed=1
 WHERE uuid=provider_id AND send_state='DISPATCH_INTENT' AND state!='ended';
 IF ROW_COUNT()<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic execution lost its original send intent';END IF;
 INSERT INTO voice_mock_schedule(uuid,phase,due,end_at) VALUES(provider_id,'ANSWERED',accepted_time+1,accepted_time+duration_sec);
END$$
DELIMITER ;
