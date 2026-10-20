-- Independent voice/model transactions. Epoch REAL values remain DOUBLE; there were no TEXT time columns.
-- enqueue_order represents the former implicit insertion ordinal used for exact FIFO ties.
CREATE TABLE voice_attempts (
  call_id VARCHAR(128) NOT NULL,
  attempt BIGINT NOT NULL,
  tenant BIGINT NOT NULL,
  `line` VARCHAR(255) NOT NULL,
  uuid VARCHAR(128) NOT NULL,
  digest VARCHAR(128) NOT NULL,
  payload LONGTEXT NOT NULL,
  created DOUBLE NOT NULL,
  deadline DOUBLE NOT NULL,
  cost BIGINT NOT NULL,
  rate BIGINT NOT NULL,
  multiplier BIGINT NOT NULL,
  state VARCHAR(32) NOT NULL DEFAULT 'pending',
  result LONGTEXT,
  ended DOUBLE,
  observed BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(call_id,attempt),
  UNIQUE KEY attempts_uuid(uuid),
  KEY attempts_scope(tenant,`line`,created),
  KEY attempts_ended(state,ended),
  KEY attempts_created(created)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE voice_flags (
  `key` VARCHAR(255) NOT NULL PRIMARY KEY,
  value LONGTEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE voice_nonces (
  nonce VARCHAR(128) NOT NULL PRIMARY KEY,
  expires DOUBLE NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE voice_outbox (
  id VARCHAR(128) NOT NULL PRIMARY KEY,
  url LONGTEXT NOT NULL,
  body LONGBLOB NOT NULL,
  created DOUBLE NOT NULL,
  due DOUBLE NOT NULL,
  failures BIGINT NOT NULL DEFAULT 0,
  stream_key VARCHAR(128) NOT NULL DEFAULT '',
  enqueue_order BIGINT NOT NULL AUTO_INCREMENT,
  UNIQUE KEY outbox_implicit_order(enqueue_order),
  KEY outbox_stream_order(stream_key,created),
  KEY outbox_created_order(created)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE voice_audit (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  at DOUBLE NOT NULL,
  action VARCHAR(128) NOT NULL,
  detail LONGTEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE voice_action_commands (
  id VARCHAR(255) NOT NULL PRIMARY KEY,
  digest VARCHAR(128) NOT NULL,
  result LONGTEXT,
  call_id VARCHAR(128) NOT NULL DEFAULT '',
  attempt BIGINT NOT NULL DEFAULT 0,
  KEY voice_commands_call(call_id,attempt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE voice_permits (
  id VARCHAR(255) NOT NULL PRIMARY KEY,
  scope VARCHAR(512) NOT NULL,
  kind VARCHAR(32) NOT NULL,
  call_id VARCHAR(128) NOT NULL,
  attempt BIGINT NOT NULL,
  owner VARCHAR(255) NOT NULL,
  state VARCHAR(32) NOT NULL,
  created DOUBLE NOT NULL,
  KEY voice_permits_scope(scope,state),
  KEY voice_permits_retention(state,created)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE voice_media_owners (
  call_id VARCHAR(128) NOT NULL PRIMARY KEY,
  worker_id VARCHAR(255) NOT NULL,
  epoch VARCHAR(128) NOT NULL,
  session_json LONGTEXT NOT NULL,
  state VARCHAR(32) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE voice_model_budgets (
  scope VARCHAR(512) NOT NULL PRIMARY KEY,
  config VARCHAR(128) NOT NULL,
  blocked_until DOUBLE NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE voice_model_usage (
  scope VARCHAR(512) NOT NULL,
  second BIGINT NOT NULL,
  requests BIGINT NOT NULL,
  tokens BIGINT NOT NULL,
  PRIMARY KEY(scope,second)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

INSERT INTO voice_flags (`key`,value) VALUES ('__voice_writer__','0'),('__model_writer__','0');
