-- Additive business drafts, immutable publications, task snapshots and import batches.
CREATE TABLE platform_document (
 tenant_id BIGINT NOT NULL,
 kind VARCHAR(24) NOT NULL,
 object_id VARCHAR(64) NOT NULL,
 name VARCHAR(160) NOT NULL,
 parent_id VARCHAR(64) NOT NULL DEFAULT '',
 state VARCHAR(24) NOT NULL DEFAULT 'draft',
 revision INT NOT NULL DEFAULT 1,
 published_version INT NOT NULL DEFAULT 0,
 draft_json LONGTEXT NOT NULL,
 published_json LONGTEXT NULL,
 updated_by BIGINT NULL,
 updated_at DATETIME(3) NOT NULL,
 PRIMARY KEY (tenant_id,kind,object_id),
 INDEX platform_document_parent (tenant_id,kind,parent_id,state)
);
CREATE TABLE platform_publication (
 tenant_id BIGINT NOT NULL,
 kind VARCHAR(24) NOT NULL,
 object_id VARCHAR(64) NOT NULL,
 version INT NOT NULL,
 content_json LONGTEXT NOT NULL,
 published_by BIGINT NULL,
 published_at DATETIME(3) NOT NULL,
 PRIMARY KEY (tenant_id,kind,object_id,version)
);
CREATE TABLE platform_campaign_snapshot (
 tenant_id BIGINT NOT NULL,
 campaign_id BIGINT NOT NULL,
 snapshot_json LONGTEXT NOT NULL,
 created_at DATETIME(3) NOT NULL,
 PRIMARY KEY (tenant_id,campaign_id)
);
CREATE TABLE platform_contact_batch (
 tenant_id BIGINT NOT NULL,
 id VARCHAR(64) NOT NULL,
 request_key VARCHAR(128) NOT NULL,
 name VARCHAR(200) NOT NULL,
 file_sha CHAR(64) NOT NULL,
 result_json LONGTEXT NOT NULL,
 created_by BIGINT NULL,
 created_at DATETIME(3) NOT NULL,
 PRIMARY KEY (tenant_id,id),
 UNIQUE KEY platform_contact_import_request (tenant_id,request_key)
);
CREATE TABLE platform_contact_batch_member (
 tenant_id BIGINT NOT NULL,
 batch_id VARCHAR(64) NOT NULL,
 contact_id BIGINT NOT NULL,
 PRIMARY KEY (tenant_id,batch_id,contact_id),
 INDEX platform_contact_member (tenant_id,contact_id)
);

CREATE TABLE platform_contact_upload (
 tenant_id BIGINT NOT NULL,id VARCHAR(64) NOT NULL,request_key VARCHAR(128) NOT NULL,
 filename VARCHAR(240) NOT NULL,file_sha CHAR(64) NOT NULL,file_bytes LONGBLOB NULL,
 upsert_rows BOOLEAN NOT NULL DEFAULT FALSE,mapping_json TEXT NOT NULL,actor_json TEXT NOT NULL,
 state VARCHAR(24) NOT NULL DEFAULT 'queued',result_json LONGTEXT NULL,error TEXT NULL,
 created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,id),UNIQUE KEY uq_upload_request(tenant_id,request_key)
);

CREATE TABLE platform_contact_batch_error (
 tenant_id BIGINT NOT NULL,batch_id VARCHAR(64) NOT NULL,row_seq INT NOT NULL,message TEXT NOT NULL,
 PRIMARY KEY(tenant_id,batch_id,row_seq)
);
