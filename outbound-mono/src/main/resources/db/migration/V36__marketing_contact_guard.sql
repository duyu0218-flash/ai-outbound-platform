-- One cross-channel admission owner per tenant/phone. The guard is kept until
-- the caller's transaction commits or rolls back, including journey node state.
CREATE TABLE mk_contact_guard (
 tenant_id BIGINT NOT NULL,
 phone_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY (tenant_id, phone_hash)
);
