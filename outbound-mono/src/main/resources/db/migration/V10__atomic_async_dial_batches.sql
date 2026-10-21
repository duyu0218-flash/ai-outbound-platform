CREATE TABLE dial_async_batch (
 tenant_id BIGINT NOT NULL,
 idempotency_key VARCHAR(128) NOT NULL,
 id CHAR(36) NOT NULL,
 actor VARCHAR(64) NOT NULL,
 request_digest CHAR(64) NOT NULL,
 response_json LONGTEXT,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(tenant_id,idempotency_key),
 UNIQUE KEY dial_async_id(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
