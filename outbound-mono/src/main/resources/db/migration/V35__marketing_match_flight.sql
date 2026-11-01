-- Durable single-flight per tenant + partner + product + phone, shared by all workers/jobs.
-- A pending/uncertain/failed claim is NEVER reclaimed by elapsed time. Only a completed
-- success/no-data result may expire; otherwise reconcile an existing result, never resend.
CREATE TABLE mk_match_flight (
 tenant_id BIGINT NOT NULL,
 partner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 product VARCHAR(64) NOT NULL,
 phone_hash CHAR(64) CHARACTER SET ascii NOT NULL,
 owner_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(16) NOT NULL,
 expires_at DATETIME(6) NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,partner_id,product,phone_hash),
 INDEX mk_match_flight_owner(owner_request_id)
);
ALTER TABLE mk_match_request
 ADD COLUMN shared_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD INDEX mk_match_request_shared(shared_request_id,state);

-- Adopt unresolved historical charged calls, including failed responses (billing unknown).
-- A newly created job cannot bypass a pre-upgrade uncertain call.
INSERT INTO mk_match_flight(tenant_id,partner_id,product,phone_hash,owner_request_id,state)
 SELECT tenant_id,partner_id,product,phone_hash,id,
        IF(state='pending','pending','uncertain')
 FROM (
  SELECT r.tenant_id,j.partner_id,j.product,r.phone_hash,r.id,r.state,
   ROW_NUMBER() OVER (PARTITION BY r.tenant_id,j.partner_id,j.product,r.phone_hash
    ORDER BY IF(r.state IN ('pending','uncertain'),0,1),r.sent_at DESC,r.id) AS n
  FROM mk_match_request r JOIN mk_match_job j ON j.tenant_id=r.tenant_id AND j.id=r.job_id
  WHERE j.adapter IN ('ant_das','mock') AND r.charged=TRUE
    AND r.state IN ('pending','uncertain','failed')
 ) prior_calls WHERE n=1;
