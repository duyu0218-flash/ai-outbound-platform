-- Translated from the read-only PostgreSQL schema; application defaults stay in POJOs.
-- Database default: utf8mb4_0900_ai_ci. Column binary collations retain source string equality.

CREATE TABLE `tenant` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `name` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `code` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `enabled` BOOLEAN NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `uq_tenant_translated_1` UNIQUE (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `gatewaynode` (
  `id` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `endpoint` VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `capacity` INTEGER NOT NULL,
  `ready` BOOLEAN NOT NULL,
  `checked_at` DATETIME(3) NOT NULL,
  `next_dial_at` DATETIME(3),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `callbackinboxpartition` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `pending_count` INTEGER NOT NULL,
  `pending_bytes` INTEGER NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `ck_callback_capacity` CHECK (`pending_count` >= 0 AND `pending_bytes` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `callbackinbox` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `receipt_key` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `partition_id` INTEGER NOT NULL,
  `call_id` CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `kind` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `body_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `body_digest` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `body_bytes` INTEGER NOT NULL,
  `state` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `received_at` DATETIME(3) NOT NULL,
  `available_at` DATETIME(3) NOT NULL,
  `completed_at` DATETIME(3),
  `attempts` INTEGER NOT NULL,
  `error_type` VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `uq_callbackinbox_translated_1` UNIQUE (`receipt_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_callback_age` ON `callbackinbox` (`state`, `received_at`);

CREATE INDEX `ix_callback_cleanup` ON `callbackinbox` (`state`, `completed_at`);

CREATE INDEX `ix_callback_call_order` ON `callbackinbox` (`call_id`, `state`, `id`);

CREATE INDEX `ix_callback_ready` ON `callbackinbox` (`partition_id`, `state`, `id`);

CREATE TABLE `callbackinboxworker` (
  `id` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `heartbeat_at` DATETIME(3) NOT NULL,
  `processed` BIGINT NOT NULL,
  `max_latency_ms` DOUBLE NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sys_user` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `tenant_id` INTEGER NOT NULL,
  `username` VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `password_hash` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `full_name` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `phone` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `role` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `is_supervisor` BOOLEAN NOT NULL,
  `agent_status` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `last_seen_at` DATETIME(3),
  `failed_login_attempts` INTEGER NOT NULL,
  `locked_until` DATETIME(3),
  `token_version` INTEGER NOT NULL,
  `last_login_at` DATETIME(3),
  `enabled` BOOLEAN NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `uq_user_translated_1` UNIQUE (`username`),
  CONSTRAINT `fk_user_tenant_id` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_user_tenant_id` ON `sys_user` (`tenant_id`);

CREATE TABLE `contact` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `tenant_id` INTEGER NOT NULL,
  `phone` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `name` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `tags` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `consent_state` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  CONSTRAINT `ck_contact_consent_state` CHECK (`consent_state` IN ('CONSENTED','NOT_CONSENTED','REVOKED','UNKNOWN')),
  `consented_at` DATETIME(3),
  `consented_by` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `dnc` BOOLEAN NOT NULL,
  `dnc_reason` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `timezone` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `uq_contact_translated_1` UNIQUE (`tenant_id`, `phone`),
  CONSTRAINT `fk_contact_tenant_id` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_contact_tenant_id` ON `contact` (`tenant_id`);

CREATE INDEX `ix_contact_phone` ON `contact` (`phone`);

CREATE TABLE `phonesuppression` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `tenant_id` INTEGER NOT NULL,
  `phone` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `reason` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `source_call_id` CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `uq_phonesuppression_translated_1` UNIQUE (`tenant_id`, `phone`),
  CONSTRAINT `fk_phonesuppression_tenant_id` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_phonesuppression_phone` ON `phonesuppression` (`phone`);

CREATE INDEX `ix_phonesuppression_tenant_id` ON `phonesuppression` (`tenant_id`);

CREATE TABLE `contactimportjob` (
  `id` CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `tenant_id` INTEGER NOT NULL,
  `request_key` VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `state` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `result_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `last_error` VARCHAR(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `uq_contactimportjob_translated_1` UNIQUE (`tenant_id`, `request_key`),
  CONSTRAINT `fk_contactimportjob_tenant_id` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_contactimportjob_tenant_id` ON `contactimportjob` (`tenant_id`);

CREATE INDEX `ix_contactimportjob_request_key` ON `contactimportjob` (`request_key`);

CREATE INDEX `ix_contactimportjob_state` ON `contactimportjob` (`state`);

CREATE TABLE `taskoutbox` (
  `id` CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `tenant_id` INTEGER NOT NULL,
  `task_type` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `aggregate_id` VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `idempotency_key` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `payload_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `state` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  CONSTRAINT `ck_taskoutbox_state` CHECK (`state` IN ('PENDING','PROCESSING','COMPLETED','FAILED','DEAD')),
  `attempts` INTEGER NOT NULL,
  `max_attempts` INTEGER NOT NULL,
  `available_at` DATETIME(3) NOT NULL,
  `locked_at` DATETIME(3),
  `lease_token` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `last_error` VARCHAR(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `uq_taskoutbox_translated_1` UNIQUE (`idempotency_key`),
  CONSTRAINT `fk_taskoutbox_tenant_id` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_taskoutbox_task_type` ON `taskoutbox` (`task_type`);

CREATE INDEX `ix_taskoutbox_state` ON `taskoutbox` (`state`);

CREATE INDEX `ix_taskoutbox_tenant_id` ON `taskoutbox` (`tenant_id`);

CREATE INDEX `ix_taskoutbox_aggregate_id` ON `taskoutbox` (`aggregate_id`);

CREATE INDEX `ix_taskoutbox_available_at` ON `taskoutbox` (`available_at`);

CREATE TABLE `taskreceipt` (
  `id` CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `tenant_id` INTEGER NOT NULL,
  `task_type` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `aggregate_id` VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `idempotency_key` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `completed_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `uq_taskreceipt_translated_1` UNIQUE (`idempotency_key`),
  CONSTRAINT `fk_taskreceipt_tenant_id` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_taskreceipt_tenant_id` ON `taskreceipt` (`tenant_id`);

CREATE TABLE `telephonyline` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `tenant_id` INTEGER NOT NULL,
  `name` VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `provider` VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `gateway_url` VARCHAR(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `caller_id` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `max_concurrency` INTEGER NOT NULL,
  `priority` INTEGER NOT NULL,
  `weight` INTEGER NOT NULL,
  `credential_ref` VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `enabled` BOOLEAN NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `uq_telephonyline_translated_1` UNIQUE (`tenant_id`, `name`),
  CONSTRAINT `fk_telephonyline_tenant_id` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_telephonyline_tenant_id` ON `telephonyline` (`tenant_id`);

CREATE INDEX `ix_telephonyline_name` ON `telephonyline` (`name`);

CREATE TABLE `scripttemplate` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `tenant_id` INTEGER NOT NULL,
  `name` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `content` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `category` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `description` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `tags` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `is_active` BOOLEAN NOT NULL,
  `version` INTEGER NOT NULL,
  `created_by` INTEGER,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_scripttemplate_tenant_id` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`),
  CONSTRAINT `fk_scripttemplate_created_by` FOREIGN KEY (`created_by`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_scripttemplate_tenant_id` ON `scripttemplate` (`tenant_id`);

CREATE TABLE `adminsetting` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `tenant_id` INTEGER NOT NULL,
  `section` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `data_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `updated_by` INTEGER,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `uq_adminsetting_translated_1` UNIQUE (`tenant_id`, `section`),
  CONSTRAINT `fk_adminsetting_tenant_id` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`),
  CONSTRAINT `fk_adminsetting_updated_by` FOREIGN KEY (`updated_by`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_adminsetting_tenant_id` ON `adminsetting` (`tenant_id`);

CREATE INDEX `ix_adminsetting_section` ON `adminsetting` (`section`);

CREATE TABLE `auditlog` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `tenant_id` INTEGER NOT NULL,
  `actor_user_id` INTEGER,
  `actor_username` VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `action` VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `resource_type` VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `resource_id` VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `detail` VARCHAR(4000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_auditlog_tenant_id` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`),
  CONSTRAINT `fk_auditlog_actor_user_id` FOREIGN KEY (`actor_user_id`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_auditlog_tenant_id` ON `auditlog` (`tenant_id`);

CREATE INDEX `ix_auditlog_actor_username` ON `auditlog` (`actor_username`);

CREATE INDEX `ix_auditlog_created_at` ON `auditlog` (`created_at`);

CREATE INDEX `ix_auditlog_action` ON `auditlog` (`action`);

CREATE INDEX `ix_auditlog_resource_type` ON `auditlog` (`resource_type`);

CREATE TABLE `scriptflowversion` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `tenant_id` INTEGER NOT NULL,
  `script_template_id` INTEGER NOT NULL,
  `version` INTEGER NOT NULL,
  `name` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `description` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `status` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `graph_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `created_by` INTEGER,
  `published_at` DATETIME(3),
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `uq_scriptflowversion_translated_1` UNIQUE (`script_template_id`, `version`),
  CONSTRAINT `fk_scriptflowversion_tenant_id` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`),
  CONSTRAINT `fk_scriptflowversion_script_template_id` FOREIGN KEY (`script_template_id`) REFERENCES `scripttemplate` (`id`),
  CONSTRAINT `fk_scriptflowversion_created_by` FOREIGN KEY (`created_by`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_scriptflowversion_status` ON `scriptflowversion` (`status`);

CREATE INDEX `ix_scriptflowversion_script_template_id` ON `scriptflowversion` (`script_template_id`);

CREATE INDEX `ix_scriptflowversion_tenant_id` ON `scriptflowversion` (`tenant_id`);

CREATE TABLE `campaign` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `tenant_id` INTEGER NOT NULL,
  `name` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `script` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `script_template_id` INTEGER,
  `script_flow_version_id` INTEGER,
  `mode` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  CONSTRAINT `ck_campaign_mode` CHECK (`mode` IN ('HUMAN_ONLY','AI_ONLY','AI_HANDOFF','AI_WITH_SMS','MIXED_HUMAN_FIRST')),
  `concurrency` INTEGER NOT NULL,
  `retry_limit` INTEGER NOT NULL,
  `retry_interval_sec` INTEGER NOT NULL,
  `attempt_interval_sec` INTEGER NOT NULL,
  `recording_enabled` BOOLEAN NOT NULL,
  `hangup_sms_enabled` BOOLEAN NOT NULL,
  `voice_ai_pipeline` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `created_by` INTEGER,
  `status` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `dispatch_enabled` BOOLEAN NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_campaign_tenant_id` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`),
  CONSTRAINT `fk_campaign_script_template_id` FOREIGN KEY (`script_template_id`) REFERENCES `scripttemplate` (`id`),
  CONSTRAINT `fk_campaign_script_flow_version_id` FOREIGN KEY (`script_flow_version_id`) REFERENCES `scriptflowversion` (`id`),
  CONSTRAINT `fk_campaign_created_by` FOREIGN KEY (`created_by`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_campaign_dispatch_enabled` ON `campaign` (`dispatch_enabled`);

CREATE INDEX `ix_campaign_tenant_id` ON `campaign` (`tenant_id`);

CREATE TABLE `scenarioversion` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `tenant_id` INTEGER NOT NULL,
  `campaign_id` INTEGER,
  `policy_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `published_by` INTEGER,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_scenarioversion_tenant_id` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`),
  CONSTRAINT `fk_scenarioversion_campaign_id` FOREIGN KEY (`campaign_id`) REFERENCES `campaign` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_scenarioversion_tenant_id` ON `scenarioversion` (`tenant_id`);

CREATE INDEX `ix_scenarioversion_campaign_id` ON `scenarioversion` (`campaign_id`);

CREATE TABLE `campaigncontact` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `campaign_id` INTEGER NOT NULL,
  `contact_id` INTEGER NOT NULL,
  `contact_order` INTEGER NOT NULL,
  `is_active` BOOLEAN NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_campaigncontact_campaign_id` FOREIGN KEY (`campaign_id`) REFERENCES `campaign` (`id`),
  CONSTRAINT `fk_campaigncontact_contact_id` FOREIGN KEY (`contact_id`) REFERENCES `contact` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_campaigncontact_campaign_id` ON `campaigncontact` (`campaign_id`);

CREATE INDEX `ix_campaigncontact_contact_id` ON `campaigncontact` (`contact_id`);

CREATE TABLE `callsession` (
  `id` CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `tenant_id` INTEGER NOT NULL,
  `campaign_id` INTEGER,
  `script_flow_version_id` INTEGER,
  `flow_node_key` VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `contact_id` INTEGER,
  `campaign_contact_key` VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `phone` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `mode` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  CONSTRAINT `ck_callsession_mode` CHECK (`mode` IN ('HUMAN_ONLY','AI_ONLY','AI_HANDOFF','AI_WITH_SMS','MIXED_HUMAN_FIRST')),
  `status` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  CONSTRAINT `ck_callsession_status` CHECK (`status` IN ('CREATED','QUEUED','DIALING','ANSWERED','IN_AI','WAITING_HUMAN','HANDOFF_TRANSFERRING','IN_HUMAN','COMPLETED','FAILED','NO_ANSWER','BUSY','VOICEMAIL')),
  `attempts` INTEGER NOT NULL,
  `max_attempts` INTEGER NOT NULL,
  `last_error` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `human_agent_id` INTEGER,
  `handoff_reason` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `recording_url` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `ai_session_id` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `telephony_call_id` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `telephony_line_id` INTEGER,
  `gateway_node_id` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `gateway_endpoint` VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `conversation_id` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `voice_ai_pipeline` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `last_transcript` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `summary` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `started_at` DATETIME(3),
  `finished_at` DATETIME(3),
  `next_attempt_at` DATETIME(3),
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `uq_callsession_translated_1` UNIQUE (`campaign_contact_key`),
  CONSTRAINT `fk_callsession_tenant_id` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`),
  CONSTRAINT `fk_callsession_campaign_id` FOREIGN KEY (`campaign_id`) REFERENCES `campaign` (`id`),
  CONSTRAINT `fk_callsession_script_flow_version_id` FOREIGN KEY (`script_flow_version_id`) REFERENCES `scriptflowversion` (`id`),
  CONSTRAINT `fk_callsession_contact_id` FOREIGN KEY (`contact_id`) REFERENCES `contact` (`id`),
  CONSTRAINT `fk_callsession_human_agent_id` FOREIGN KEY (`human_agent_id`) REFERENCES `sys_user` (`id`),
  CONSTRAINT `fk_callsession_telephony_line_id` FOREIGN KEY (`telephony_line_id`) REFERENCES `telephonyline` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_callsession_gateway_node_id` ON `callsession` (`gateway_node_id`);

CREATE INDEX `ix_callsession_tenant_id` ON `callsession` (`tenant_id`);

CREATE INDEX `ix_callsession_updated_at` ON `callsession` (`updated_at`);

CREATE INDEX `ix_callsession_telephony_line_id` ON `callsession` (`telephony_line_id`);

CREATE INDEX `ix_callsession_status` ON `callsession` (`status`);

CREATE INDEX `ix_callsession_next_attempt_at` ON `callsession` (`next_attempt_at`);

CREATE TABLE `knowledgeitem` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `tenant_id` INTEGER NOT NULL,
  `title` VARCHAR(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `content` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  CONSTRAINT `ck_knowledgeitem_content_length` CHECK (CHAR_LENGTH(`content`) <= 50000),
  `category` VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `keywords` VARCHAR(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `source` VARCHAR(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `valid_from` DATETIME(3),
  `valid_until` DATETIME(3),
  `campaign_id` INTEGER,
  `is_active` BOOLEAN NOT NULL,
  `version` INTEGER NOT NULL,
  `created_by` INTEGER,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_knowledgeitem_tenant_id` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`),
  CONSTRAINT `fk_knowledgeitem_campaign_id` FOREIGN KEY (`campaign_id`) REFERENCES `campaign` (`id`),
  CONSTRAINT `fk_knowledgeitem_created_by` FOREIGN KEY (`created_by`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_knowledgeitem_campaign_id` ON `knowledgeitem` (`campaign_id`);

CREATE INDEX `ix_knowledgeitem_category` ON `knowledgeitem` (`category`);

CREATE INDEX `ix_knowledgeitem_tenant_id` ON `knowledgeitem` (`tenant_id`);

CREATE TABLE `conversationstate` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `tenant_id` INTEGER NOT NULL,
  `call_id` CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `attempt` INTEGER NOT NULL,
  `policy_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `policy_version_id` INTEGER,
  `data_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `generation` INTEGER NOT NULL,
  `deadline` DATETIME(3),
  `timer_kind` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `uq_conversationstate_translated_1` UNIQUE (`call_id`, `attempt`),
  CONSTRAINT `fk_conversationstate_tenant_id` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`),
  CONSTRAINT `fk_conversationstate_call_id` FOREIGN KEY (`call_id`) REFERENCES `callsession` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_conversationstate_call_id` ON `conversationstate` (`call_id`);

CREATE INDEX `ix_conversationstate_deadline` ON `conversationstate` (`deadline`);

CREATE INDEX `ix_conversationstate_tenant_id` ON `conversationstate` (`tenant_id`);

CREATE TABLE `callbackappointment` (
  `id` CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `tenant_id` INTEGER NOT NULL,
  `source_call_id` CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `request_key` VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `scheduled_at` DATETIME(3) NOT NULL,
  `state` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `revision` INTEGER NOT NULL,
  `dial_call_id` CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `uq_callbackappointment_translated_1` UNIQUE (`request_key`),
  CONSTRAINT `fk_callbackappointment_tenant_id` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`),
  CONSTRAINT `fk_callbackappointment_source_call_id` FOREIGN KEY (`source_call_id`) REFERENCES `callsession` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_callbackappointment_scheduled_at` ON `callbackappointment` (`scheduled_at`);

CREATE INDEX `ix_callbackappointment_source_call_id` ON `callbackappointment` (`source_call_id`);

CREATE INDEX `ix_callbackappointment_state` ON `callbackappointment` (`state`(191));

CREATE INDEX `ix_callbackappointment_tenant_id` ON `callbackappointment` (`tenant_id`);

CREATE TABLE `productworkitem` (
  `id` CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `tenant_id` INTEGER NOT NULL,
  `event_key` VARCHAR(250) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `call_id` CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `kind` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `state` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `phone` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `detail_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `assigned_to` INTEGER,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `uq_productworkitem_translated_1` UNIQUE (`event_key`),
  CONSTRAINT `fk_productworkitem_tenant_id` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`),
  CONSTRAINT `fk_productworkitem_call_id` FOREIGN KEY (`call_id`) REFERENCES `callsession` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_productworkitem_tenant_id` ON `productworkitem` (`tenant_id`);

CREATE INDEX `ix_productworkitem_kind` ON `productworkitem` (`kind`(191));

CREATE INDEX `ix_productworkitem_state` ON `productworkitem` (`state`(191));

CREATE TABLE `callevent` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `call_session_id` CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `event_type` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `source` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `payload` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_callevent_call_session_id` FOREIGN KEY (`call_session_id`) REFERENCES `callsession` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_callevent_event_type` ON `callevent` (`event_type`);

CREATE INDEX `ix_callevent_call_session_id` ON `callevent` (`call_session_id`);

CREATE TABLE `realtimesession` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `tenant_id` INTEGER NOT NULL,
  `call_session_id` CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `provider_session_id` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `attempt` INTEGER NOT NULL,
  `state` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  CONSTRAINT `ck_realtimesession_state` CHECK (`state` IN ('CREATED','LISTENING','THINKING','SPEAKING','INTERRUPTED','CLOSED')),
  `codec` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `sample_rate` INTEGER NOT NULL,
  `channel_count` INTEGER NOT NULL,
  `last_event_sequence` INTEGER,
  `turn_sequence` INTEGER NOT NULL,
  `playback_id` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `started_at` DATETIME(3),
  `ended_at` DATETIME(3),
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `uq_realtimesession_translated_1` UNIQUE (`call_session_id`),
  CONSTRAINT `fk_realtimesession_tenant_id` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`),
  CONSTRAINT `fk_realtimesession_call_session_id` FOREIGN KEY (`call_session_id`) REFERENCES `callsession` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_realtimesession_provider_session_id` ON `realtimesession` (`provider_session_id`);

CREATE INDEX `ix_realtimesession_attempt` ON `realtimesession` (`attempt`);

CREATE INDEX `ix_realtimesession_call_session_id` ON `realtimesession` (`call_session_id`);

CREATE INDEX `ix_realtimesession_tenant_id` ON `realtimesession` (`tenant_id`);

CREATE INDEX `ix_realtimesession_state` ON `realtimesession` (`state`);

CREATE TABLE `speechturn` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `tenant_id` INTEGER NOT NULL,
  `call_session_id` CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `provider_event_key` VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `turn_index` INTEGER NOT NULL,
  `attempt` INTEGER NOT NULL,
  `speaker_role` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `channel_id` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `transcript` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  CONSTRAINT `ck_speechturn_transcript_length` CHECK (CHAR_LENGTH(`transcript`) <= 100000),
  `normalized_transcript` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  CONSTRAINT `ck_speechturn_normalized_transcript_length` CHECK (CHAR_LENGTH(`normalized_transcript`) <= 100000),
  `is_final` BOOLEAN NOT NULL,
  `confidence` DOUBLE,
  `start_ms` INTEGER,
  `end_ms` INTEGER,
  `asr_provider` VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `uq_speechturn_translated_1` UNIQUE (`call_session_id`, `provider_event_key`),
  CONSTRAINT `fk_speechturn_tenant_id` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`),
  CONSTRAINT `fk_speechturn_call_session_id` FOREIGN KEY (`call_session_id`) REFERENCES `callsession` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_speechturn_created_at` ON `speechturn` (`created_at`);

CREATE INDEX `ix_speechturn_call_session_id` ON `speechturn` (`call_session_id`);

CREATE INDEX `ix_speechturn_tenant_id` ON `speechturn` (`tenant_id`);

CREATE INDEX `ix_speechturn_turn_index` ON `speechturn` (`turn_index`);

CREATE INDEX `ix_speechturn_attempt` ON `speechturn` (`attempt`);

CREATE TABLE `callmetric` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `tenant_id` INTEGER NOT NULL,
  `call_session_id` CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `stage` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `provider` VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `duration_ms` INTEGER,
  `success` BOOLEAN NOT NULL,
  `error_code` VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `detail` VARCHAR(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_callmetric_tenant_id` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`),
  CONSTRAINT `fk_callmetric_call_session_id` FOREIGN KEY (`call_session_id`) REFERENCES `callsession` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_callmetric_call_session_id` ON `callmetric` (`call_session_id`);

CREATE INDEX `ix_callmetric_stage` ON `callmetric` (`stage`);

CREATE INDEX `ix_callmetric_tenant_id` ON `callmetric` (`tenant_id`);

CREATE INDEX `ix_callmetric_created_at` ON `callmetric` (`created_at`);

CREATE TABLE `recordingasset` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `tenant_id` INTEGER NOT NULL,
  `call_session_id` CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `attempt` INTEGER NOT NULL,
  `provider_recording_id` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `provider_url` VARCHAR(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `storage_uri` VARCHAR(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `state` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `duration_sec` INTEGER,
  `media_format` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `channel_count` INTEGER NOT NULL,
  `checksum_sha256` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `retention_until` DATETIME(3),
  `deleted_at` DATETIME(3),
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_recordingasset_tenant_id` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`),
  CONSTRAINT `fk_recordingasset_call_session_id` FOREIGN KEY (`call_session_id`) REFERENCES `callsession` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_recordingasset_call_session_id` ON `recordingasset` (`call_session_id`);

CREATE INDEX `ix_recordingasset_created_at` ON `recordingasset` (`created_at`);

CREATE INDEX `ix_recordingasset_tenant_id` ON `recordingasset` (`tenant_id`);

CREATE INDEX `ix_recordingasset_state` ON `recordingasset` (`state`);

CREATE INDEX `ix_recordingasset_provider_recording_id` ON `recordingasset` (`provider_recording_id`);

CREATE TABLE `callanalysis` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `tenant_id` INTEGER NOT NULL,
  `call_session_id` CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `result_code` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `sentiment` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `intent` VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `summary` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  CONSTRAINT `ck_callanalysis_summary_length` CHECK (CHAR_LENGTH(`summary`) <= 10000),
  `qa_score` INTEGER NOT NULL,
  `qa_flags_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `structured_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `automatic_result_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `needs_review` BOOLEAN NOT NULL,
  `review_state` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `reviewed_by` INTEGER,
  `reviewed_at` DATETIME(3),
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `uq_callanalysis_translated_1` UNIQUE (`call_session_id`),
  CONSTRAINT `fk_callanalysis_tenant_id` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`),
  CONSTRAINT `fk_callanalysis_call_session_id` FOREIGN KEY (`call_session_id`) REFERENCES `callsession` (`id`),
  CONSTRAINT `fk_callanalysis_reviewed_by` FOREIGN KEY (`reviewed_by`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_callanalysis_call_session_id` ON `callanalysis` (`call_session_id`);

CREATE INDEX `ix_callanalysis_tenant_id` ON `callanalysis` (`tenant_id`);

CREATE INDEX `ix_callanalysis_result_code` ON `callanalysis` (`result_code`);

CREATE TABLE `handoffrequest` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `tenant_id` INTEGER NOT NULL,
  `call_session_id` CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `assigned_agent_id` INTEGER,
  `state` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  CONSTRAINT `ck_handoffrequest_state` CHECK (`state` IN ('WAITING','ACCEPTING','ACCEPTED','REJECTED','COMPLETED','EXPIRED')),
  `reason` VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `target_group` VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `requested_at` DATETIME(3) NOT NULL,
  `responded_at` DATETIME(3),
  `completed_at` DATETIME(3),
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_handoffrequest_tenant_id` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`),
  CONSTRAINT `fk_handoffrequest_call_session_id` FOREIGN KEY (`call_session_id`) REFERENCES `callsession` (`id`),
  CONSTRAINT `fk_handoffrequest_assigned_agent_id` FOREIGN KEY (`assigned_agent_id`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_handoffrequest_tenant_id` ON `handoffrequest` (`tenant_id`);

CREATE INDEX `ix_handoffrequest_state` ON `handoffrequest` (`state`);

CREATE INDEX `ix_handoffrequest_call_session_id` ON `handoffrequest` (`call_session_id`);

CREATE INDEX `ix_handoffrequest_assigned_agent_id` ON `handoffrequest` (`assigned_agent_id`);

CREATE TABLE `webhookeventingest` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `call_session_id` CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `event_type` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `source` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `provider_event_key` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `repeat_count` INTEGER NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `uq_webhookeventingest_translated_1` UNIQUE (`provider_event_key`),
  CONSTRAINT `fk_webhookeventingest_call_session_id` FOREIGN KEY (`call_session_id`) REFERENCES `callsession` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_webhookeventingest_call_session_id` ON `webhookeventingest` (`call_session_id`);

CREATE INDEX `ix_webhookeventingest_source` ON `webhookeventingest` (`source`(191));

CREATE INDEX `ix_webhookeventingest_event_type` ON `webhookeventingest` (`event_type`(191));

CREATE TABLE `smslog` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `tenant_id` INTEGER NOT NULL,
  `call_session_id` CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `to_phone` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `template_code` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `content` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `state` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `provider_message_id` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `provider_error` VARCHAR(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,
  `sent_at` DATETIME(3),
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_smslog_tenant_id` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`),
  CONSTRAINT `fk_smslog_call_session_id` FOREIGN KEY (`call_session_id`) REFERENCES `callsession` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_smslog_to_phone` ON `smslog` (`to_phone`(191));

CREATE INDEX `ix_smslog_provider_message_id` ON `smslog` (`provider_message_id`);

CREATE INDEX `ix_smslog_tenant_id` ON `smslog` (`tenant_id`);

CREATE TABLE `callusage` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `tenant_id` INTEGER NOT NULL,
  `call_session_id` CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `attempt` INTEGER NOT NULL,
  `answered_at` DATETIME(3),
  `ended_at` DATETIME(3),
  `ai_ended_at` DATETIME(3),
  `telephony_seconds` DOUBLE,
  `ai_seconds` DOUBLE,
  `duration_source` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `uq_callusage_translated_1` UNIQUE (`call_session_id`, `attempt`),
  CONSTRAINT `fk_callusage_tenant_id` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`),
  CONSTRAINT `fk_callusage_call_session_id` FOREIGN KEY (`call_session_id`) REFERENCES `callsession` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `ix_callusage_tenant_id` ON `callusage` (`tenant_id`);

CREATE INDEX `ix_callusage_call_session_id` ON `callusage` (`call_session_id`);
CREATE INDEX `ix_callsession_gateway_capacity` ON `callsession` (`gateway_node_id`, `status`);

-- Source non-unique partial index predicate: state IN ('PENDING','FAILED','PROCESSING'); MySQL stores the full index.
CREATE INDEX `ix_taskoutbox_active_stream` ON `taskoutbox` (`aggregate_id`, `task_type`, `created_at`, `id`);

-- Source non-unique partial index predicate: state IN ('PENDING','FAILED'); MySQL stores the full index.
CREATE INDEX `ix_taskoutbox_ready_tenant` ON `taskoutbox` (`task_type`, `tenant_id`, `available_at`, `id`);

-- Source non-unique partial index predicate: state = 'COMPLETED'; MySQL stores the full index.
CREATE INDEX `ix_taskoutbox_archive` ON `taskoutbox` (`updated_at`, `id`);
CREATE INDEX `ix_taskoutbox_ready_type` ON `taskoutbox` (`task_type`, `state`, `available_at`);
CREATE INDEX `ix_taskoutbox_stream_order` ON `taskoutbox` (`aggregate_id`, `task_type`, `state`, `created_at`, `id`);
CREATE INDEX `ix_speechturn_call_attempt` ON `speechturn` (`call_session_id`, `attempt`, `created_at`);
