CREATE TYPE consentstate AS ENUM ('CONSENTED', 'NOT_CONSENTED', 'REVOKED', 'UNKNOWN');
CREATE TYPE callmode AS ENUM ('HUMAN_ONLY', 'AI_ONLY', 'AI_HANDOFF', 'AI_WITH_SMS', 'MIXED_HUMAN_FIRST');
CREATE TYPE callstatus AS ENUM ('CREATED', 'QUEUED', 'DIALING', 'ANSWERED', 'IN_AI', 'WAITING_HUMAN', 'HANDOFF_TRANSFERRING', 'IN_HUMAN', 'COMPLETED', 'FAILED', 'NO_ANSWER', 'BUSY', 'VOICEMAIL');
CREATE TYPE realtimestate AS ENUM ('CREATED', 'LISTENING', 'THINKING', 'SPEAKING', 'INTERRUPTED', 'CLOSED');
CREATE TYPE taskstate AS ENUM ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DEAD');
CREATE TYPE handoffstate AS ENUM ('WAITING', 'ACCEPTING', 'ACCEPTED', 'REJECTED', 'COMPLETED', 'EXPIRED');

CREATE TABLE tenant (
	id SERIAL NOT NULL,
	name VARCHAR NOT NULL,
	code VARCHAR NOT NULL,
	enabled BOOLEAN NOT NULL,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id)
)

;
CREATE UNIQUE INDEX ix_tenant_code ON tenant (code);

CREATE TABLE gatewaynode (
	id VARCHAR(64) NOT NULL,
	endpoint VARCHAR(512) NOT NULL,
	capacity INTEGER NOT NULL,
	ready BOOLEAN NOT NULL,
	checked_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	next_dial_at TIMESTAMP WITHOUT TIME ZONE,
	PRIMARY KEY (id)
)

;

CREATE TABLE callbackinboxpartition (
	id SERIAL NOT NULL,
	pending_count INTEGER NOT NULL,
	pending_bytes INTEGER NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT ck_callback_capacity CHECK (pending_count >= 0 AND pending_bytes >= 0)
)

;

CREATE TABLE callbackinbox (
	id BIGSERIAL NOT NULL,
	receipt_key VARCHAR(64) NOT NULL,
	partition_id INTEGER NOT NULL,
	call_id UUID NOT NULL,
	kind VARCHAR(32) NOT NULL,
	body_json VARCHAR NOT NULL,
	body_digest VARCHAR(64) NOT NULL,
	body_bytes INTEGER NOT NULL,
	state VARCHAR(16) NOT NULL,
	received_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	available_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	completed_at TIMESTAMP WITHOUT TIME ZONE,
	attempts INTEGER NOT NULL,
	error_type VARCHAR(128) NOT NULL,
	PRIMARY KEY (id),
	UNIQUE (receipt_key)
)

;
CREATE INDEX ix_callback_age ON callbackinbox (state, received_at);
CREATE INDEX ix_callback_cleanup ON callbackinbox (state, completed_at);
CREATE INDEX ix_callback_call_order ON callbackinbox (call_id, state, id);
CREATE INDEX ix_callback_ready ON callbackinbox (partition_id, state, id);

CREATE TABLE callbackinboxworker (
	id VARCHAR(64) NOT NULL,
	heartbeat_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	processed BIGINT NOT NULL,
	max_latency_ms FLOAT NOT NULL,
	PRIMARY KEY (id)
)

;

CREATE TABLE "user" (
	id SERIAL NOT NULL,
	tenant_id INTEGER NOT NULL,
	username VARCHAR NOT NULL,
	password_hash VARCHAR NOT NULL,
	full_name VARCHAR NOT NULL,
	phone VARCHAR,
	role VARCHAR NOT NULL,
	is_supervisor BOOLEAN NOT NULL,
	agent_status VARCHAR(32) NOT NULL,
	last_seen_at TIMESTAMP WITHOUT TIME ZONE,
	failed_login_attempts INTEGER NOT NULL,
	locked_until TIMESTAMP WITHOUT TIME ZONE,
	token_version INTEGER NOT NULL,
	last_login_at TIMESTAMP WITHOUT TIME ZONE,
	enabled BOOLEAN NOT NULL,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	FOREIGN KEY(tenant_id) REFERENCES tenant (id)
)

;
CREATE UNIQUE INDEX ix_user_username ON "user" (username);
CREATE INDEX ix_user_tenant_id ON "user" (tenant_id);

CREATE TABLE contact (
	id SERIAL NOT NULL,
	tenant_id INTEGER NOT NULL,
	phone VARCHAR NOT NULL,
	name VARCHAR,
	tags VARCHAR NOT NULL,
	consent_state consentstate NOT NULL,
	consented_at TIMESTAMP WITHOUT TIME ZONE,
	consented_by VARCHAR,
	dnc BOOLEAN NOT NULL,
	dnc_reason VARCHAR,
	timezone VARCHAR,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT uq_contact_tenant_phone UNIQUE (tenant_id, phone),
	FOREIGN KEY(tenant_id) REFERENCES tenant (id)
)

;
CREATE INDEX ix_contact_tenant_id ON contact (tenant_id);
CREATE INDEX ix_contact_phone ON contact (phone);

CREATE TABLE phonesuppression (
	id SERIAL NOT NULL,
	tenant_id INTEGER NOT NULL,
	phone VARCHAR NOT NULL,
	reason VARCHAR NOT NULL,
	source_call_id UUID,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT uq_phone_suppression UNIQUE (tenant_id, phone),
	FOREIGN KEY(tenant_id) REFERENCES tenant (id)
)

;
CREATE INDEX ix_phonesuppression_phone ON phonesuppression (phone);
CREATE INDEX ix_phonesuppression_tenant_id ON phonesuppression (tenant_id);

CREATE TABLE contactimportjob (
	id UUID NOT NULL,
	tenant_id INTEGER NOT NULL,
	request_key VARCHAR(128) NOT NULL,
	state VARCHAR(32) NOT NULL,
	result_json VARCHAR NOT NULL,
	last_error VARCHAR(2000) NOT NULL,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT uq_contact_import_tenant_key UNIQUE (tenant_id, request_key),
	FOREIGN KEY(tenant_id) REFERENCES tenant (id)
)

;
CREATE INDEX ix_contactimportjob_tenant_id ON contactimportjob (tenant_id);
CREATE INDEX ix_contactimportjob_request_key ON contactimportjob (request_key);
CREATE INDEX ix_contactimportjob_state ON contactimportjob (state);

CREATE TABLE taskoutbox (
	id UUID NOT NULL,
	tenant_id INTEGER NOT NULL,
	task_type VARCHAR(64) NOT NULL,
	aggregate_id VARCHAR(128) NOT NULL,
	idempotency_key VARCHAR(255) NOT NULL,
	payload_json VARCHAR NOT NULL,
	state taskstate NOT NULL,
	attempts INTEGER NOT NULL,
	max_attempts INTEGER NOT NULL,
	available_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	locked_at TIMESTAMP WITHOUT TIME ZONE,
	lease_token VARCHAR(64),
	last_error VARCHAR(2000) NOT NULL,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT uq_taskoutbox_idempotency UNIQUE (idempotency_key),
	FOREIGN KEY(tenant_id) REFERENCES tenant (id)
)

;
CREATE INDEX ix_taskoutbox_task_type ON taskoutbox (task_type);
CREATE INDEX ix_taskoutbox_state ON taskoutbox (state);
CREATE INDEX ix_taskoutbox_tenant_id ON taskoutbox (tenant_id);
CREATE INDEX ix_taskoutbox_aggregate_id ON taskoutbox (aggregate_id);
CREATE INDEX ix_taskoutbox_available_at ON taskoutbox (available_at);

CREATE TABLE taskreceipt (
	id UUID NOT NULL,
	tenant_id INTEGER NOT NULL,
	task_type VARCHAR(64) NOT NULL,
	aggregate_id VARCHAR(128) NOT NULL,
	idempotency_key VARCHAR(255) NOT NULL,
	completed_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	FOREIGN KEY(tenant_id) REFERENCES tenant (id),
	UNIQUE (idempotency_key)
)

;
CREATE INDEX ix_taskreceipt_tenant_id ON taskreceipt (tenant_id);

CREATE TABLE telephonyline (
	id SERIAL NOT NULL,
	tenant_id INTEGER NOT NULL,
	name VARCHAR(200) NOT NULL,
	provider VARCHAR(100) NOT NULL,
	gateway_url VARCHAR(1000) NOT NULL,
	caller_id VARCHAR(64) NOT NULL,
	max_concurrency INTEGER NOT NULL,
	priority INTEGER NOT NULL,
	weight INTEGER NOT NULL,
	credential_ref VARCHAR(128) NOT NULL,
	enabled BOOLEAN NOT NULL,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT uq_telephonyline_tenant_name UNIQUE (tenant_id, name),
	FOREIGN KEY(tenant_id) REFERENCES tenant (id)
)

;
CREATE INDEX ix_telephonyline_tenant_id ON telephonyline (tenant_id);
CREATE INDEX ix_telephonyline_name ON telephonyline (name);

CREATE TABLE scripttemplate (
	id SERIAL NOT NULL,
	tenant_id INTEGER NOT NULL,
	name VARCHAR NOT NULL,
	content VARCHAR NOT NULL,
	category VARCHAR NOT NULL,
	description VARCHAR NOT NULL,
	tags VARCHAR NOT NULL,
	is_active BOOLEAN NOT NULL,
	version INTEGER NOT NULL,
	created_by INTEGER,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	FOREIGN KEY(tenant_id) REFERENCES tenant (id),
	FOREIGN KEY(created_by) REFERENCES "user" (id)
)

;
CREATE INDEX ix_scripttemplate_tenant_id ON scripttemplate (tenant_id);

CREATE TABLE adminsetting (
	id SERIAL NOT NULL,
	tenant_id INTEGER NOT NULL,
	section VARCHAR(64) NOT NULL,
	data_json VARCHAR NOT NULL,
	updated_by INTEGER,
	updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT uq_adminsetting_tenant_section UNIQUE (tenant_id, section),
	FOREIGN KEY(tenant_id) REFERENCES tenant (id),
	FOREIGN KEY(updated_by) REFERENCES "user" (id)
)

;
CREATE INDEX ix_adminsetting_tenant_id ON adminsetting (tenant_id);
CREATE INDEX ix_adminsetting_section ON adminsetting (section);

CREATE TABLE auditlog (
	id SERIAL NOT NULL,
	tenant_id INTEGER NOT NULL,
	actor_user_id INTEGER,
	actor_username VARCHAR(200) NOT NULL,
	action VARCHAR(100) NOT NULL,
	resource_type VARCHAR(100) NOT NULL,
	resource_id VARCHAR(200),
	detail VARCHAR(4000) NOT NULL,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	FOREIGN KEY(tenant_id) REFERENCES tenant (id),
	FOREIGN KEY(actor_user_id) REFERENCES "user" (id)
)

;
CREATE INDEX ix_auditlog_tenant_id ON auditlog (tenant_id);
CREATE INDEX ix_auditlog_actor_username ON auditlog (actor_username);
CREATE INDEX ix_auditlog_created_at ON auditlog (created_at);
CREATE INDEX ix_auditlog_action ON auditlog (action);
CREATE INDEX ix_auditlog_resource_type ON auditlog (resource_type);

CREATE TABLE scriptflowversion (
	id SERIAL NOT NULL,
	tenant_id INTEGER NOT NULL,
	script_template_id INTEGER NOT NULL,
	version INTEGER NOT NULL,
	name VARCHAR NOT NULL,
	description VARCHAR NOT NULL,
	status VARCHAR(32) NOT NULL,
	graph_json VARCHAR NOT NULL,
	created_by INTEGER,
	published_at TIMESTAMP WITHOUT TIME ZONE,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT uq_script_flow_template_version UNIQUE (script_template_id, version),
	FOREIGN KEY(tenant_id) REFERENCES tenant (id),
	FOREIGN KEY(script_template_id) REFERENCES scripttemplate (id),
	FOREIGN KEY(created_by) REFERENCES "user" (id)
)

;
CREATE INDEX ix_scriptflowversion_status ON scriptflowversion (status);
CREATE INDEX ix_scriptflowversion_script_template_id ON scriptflowversion (script_template_id);
CREATE INDEX ix_scriptflowversion_tenant_id ON scriptflowversion (tenant_id);

CREATE TABLE campaign (
	id SERIAL NOT NULL,
	tenant_id INTEGER NOT NULL,
	name VARCHAR NOT NULL,
	script VARCHAR NOT NULL,
	script_template_id INTEGER,
	script_flow_version_id INTEGER,
	mode callmode NOT NULL,
	concurrency INTEGER NOT NULL,
	retry_limit INTEGER NOT NULL,
	retry_interval_sec INTEGER NOT NULL,
	attempt_interval_sec INTEGER NOT NULL,
	recording_enabled BOOLEAN NOT NULL,
	hangup_sms_enabled BOOLEAN NOT NULL,
	voice_ai_pipeline VARCHAR(16) NOT NULL,
	created_by INTEGER,
	status VARCHAR NOT NULL,
	dispatch_enabled BOOLEAN NOT NULL,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	FOREIGN KEY(tenant_id) REFERENCES tenant (id),
	FOREIGN KEY(script_template_id) REFERENCES scripttemplate (id),
	FOREIGN KEY(script_flow_version_id) REFERENCES scriptflowversion (id),
	FOREIGN KEY(created_by) REFERENCES "user" (id)
)

;
CREATE INDEX ix_campaign_dispatch_enabled ON campaign (dispatch_enabled);
CREATE INDEX ix_campaign_tenant_id ON campaign (tenant_id);

CREATE TABLE scenarioversion (
	id SERIAL NOT NULL,
	tenant_id INTEGER NOT NULL,
	campaign_id INTEGER,
	policy_json VARCHAR NOT NULL,
	published_by INTEGER,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	FOREIGN KEY(tenant_id) REFERENCES tenant (id),
	FOREIGN KEY(campaign_id) REFERENCES campaign (id)
)

;
CREATE INDEX ix_scenarioversion_tenant_id ON scenarioversion (tenant_id);
CREATE INDEX ix_scenarioversion_campaign_id ON scenarioversion (campaign_id);

CREATE TABLE campaigncontact (
	id SERIAL NOT NULL,
	campaign_id INTEGER NOT NULL,
	contact_id INTEGER NOT NULL,
	contact_order INTEGER NOT NULL,
	is_active BOOLEAN NOT NULL,
	PRIMARY KEY (id),
	FOREIGN KEY(campaign_id) REFERENCES campaign (id),
	FOREIGN KEY(contact_id) REFERENCES contact (id)
)

;
CREATE INDEX ix_campaigncontact_campaign_id ON campaigncontact (campaign_id);
CREATE INDEX ix_campaigncontact_contact_id ON campaigncontact (contact_id);

CREATE TABLE callsession (
	id UUID NOT NULL,
	tenant_id INTEGER NOT NULL,
	campaign_id INTEGER,
	script_flow_version_id INTEGER,
	flow_node_key VARCHAR(128),
	contact_id INTEGER,
	campaign_contact_key VARCHAR(128),
	phone VARCHAR NOT NULL,
	mode callmode NOT NULL,
	status callstatus NOT NULL,
	attempts INTEGER NOT NULL,
	max_attempts INTEGER NOT NULL,
	last_error VARCHAR,
	human_agent_id INTEGER,
	handoff_reason VARCHAR,
	recording_url VARCHAR,
	ai_session_id VARCHAR,
	telephony_call_id VARCHAR,
	telephony_line_id INTEGER,
	gateway_node_id VARCHAR(64),
	gateway_endpoint VARCHAR(512),
	conversation_id VARCHAR,
	voice_ai_pipeline VARCHAR(16) NOT NULL,
	last_transcript VARCHAR,
	summary VARCHAR,
	started_at TIMESTAMP WITHOUT TIME ZONE,
	finished_at TIMESTAMP WITHOUT TIME ZONE,
	next_attempt_at TIMESTAMP WITHOUT TIME ZONE,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	FOREIGN KEY(tenant_id) REFERENCES tenant (id),
	FOREIGN KEY(campaign_id) REFERENCES campaign (id),
	FOREIGN KEY(script_flow_version_id) REFERENCES scriptflowversion (id),
	FOREIGN KEY(contact_id) REFERENCES contact (id),
	UNIQUE (campaign_contact_key),
	FOREIGN KEY(human_agent_id) REFERENCES "user" (id),
	FOREIGN KEY(telephony_line_id) REFERENCES telephonyline (id)
)

;
CREATE INDEX ix_callsession_gateway_node_id ON callsession (gateway_node_id);
CREATE INDEX ix_callsession_tenant_id ON callsession (tenant_id);
CREATE INDEX ix_callsession_updated_at ON callsession (updated_at);
CREATE INDEX ix_callsession_telephony_line_id ON callsession (telephony_line_id);
CREATE INDEX ix_callsession_status ON callsession (status);
CREATE INDEX ix_callsession_next_attempt_at ON callsession (next_attempt_at);

CREATE TABLE knowledgeitem (
	id SERIAL NOT NULL,
	tenant_id INTEGER NOT NULL,
	title VARCHAR(300) NOT NULL,
	content VARCHAR(50000) NOT NULL,
	category VARCHAR(100) NOT NULL,
	keywords VARCHAR(2000) NOT NULL,
	source VARCHAR(2000) NOT NULL,
	valid_from TIMESTAMP WITHOUT TIME ZONE,
	valid_until TIMESTAMP WITHOUT TIME ZONE,
	campaign_id INTEGER,
	is_active BOOLEAN NOT NULL,
	version INTEGER NOT NULL,
	created_by INTEGER,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	FOREIGN KEY(tenant_id) REFERENCES tenant (id),
	FOREIGN KEY(campaign_id) REFERENCES campaign (id),
	FOREIGN KEY(created_by) REFERENCES "user" (id)
)

;
CREATE INDEX ix_knowledgeitem_campaign_id ON knowledgeitem (campaign_id);
CREATE INDEX ix_knowledgeitem_category ON knowledgeitem (category);
CREATE INDEX ix_knowledgeitem_tenant_id ON knowledgeitem (tenant_id);

CREATE TABLE conversationstate (
	id SERIAL NOT NULL,
	tenant_id INTEGER NOT NULL,
	call_id UUID NOT NULL,
	attempt INTEGER NOT NULL,
	policy_json VARCHAR NOT NULL,
	policy_version_id INTEGER,
	data_json VARCHAR NOT NULL,
	generation INTEGER NOT NULL,
	deadline TIMESTAMP WITHOUT TIME ZONE,
	timer_kind VARCHAR NOT NULL,
	updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT uq_conversation_attempt UNIQUE (call_id, attempt),
	FOREIGN KEY(tenant_id) REFERENCES tenant (id),
	FOREIGN KEY(call_id) REFERENCES callsession (id)
)

;
CREATE INDEX ix_conversationstate_call_id ON conversationstate (call_id);
CREATE INDEX ix_conversationstate_deadline ON conversationstate (deadline);
CREATE INDEX ix_conversationstate_tenant_id ON conversationstate (tenant_id);

CREATE TABLE callbackappointment (
	id UUID NOT NULL,
	tenant_id INTEGER NOT NULL,
	source_call_id UUID NOT NULL,
	request_key VARCHAR(200) NOT NULL,
	scheduled_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	state VARCHAR NOT NULL,
	revision INTEGER NOT NULL,
	dial_call_id UUID,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	FOREIGN KEY(tenant_id) REFERENCES tenant (id),
	FOREIGN KEY(source_call_id) REFERENCES callsession (id),
	UNIQUE (request_key)
)

;
CREATE INDEX ix_callbackappointment_scheduled_at ON callbackappointment (scheduled_at);
CREATE INDEX ix_callbackappointment_source_call_id ON callbackappointment (source_call_id);
CREATE INDEX ix_callbackappointment_state ON callbackappointment (state);
CREATE INDEX ix_callbackappointment_tenant_id ON callbackappointment (tenant_id);

CREATE TABLE productworkitem (
	id UUID NOT NULL,
	tenant_id INTEGER NOT NULL,
	event_key VARCHAR(250) NOT NULL,
	call_id UUID,
	kind VARCHAR NOT NULL,
	state VARCHAR NOT NULL,
	phone VARCHAR NOT NULL,
	detail_json VARCHAR NOT NULL,
	assigned_to INTEGER,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	FOREIGN KEY(tenant_id) REFERENCES tenant (id),
	UNIQUE (event_key),
	FOREIGN KEY(call_id) REFERENCES callsession (id)
)

;
CREATE INDEX ix_productworkitem_tenant_id ON productworkitem (tenant_id);
CREATE INDEX ix_productworkitem_kind ON productworkitem (kind);
CREATE INDEX ix_productworkitem_state ON productworkitem (state);

CREATE TABLE callevent (
	id SERIAL NOT NULL,
	call_session_id UUID NOT NULL,
	event_type VARCHAR(64) NOT NULL,
	source VARCHAR NOT NULL,
	payload VARCHAR NOT NULL,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	FOREIGN KEY(call_session_id) REFERENCES callsession (id)
)

;
CREATE INDEX ix_callevent_event_type ON callevent (event_type);
CREATE INDEX ix_callevent_call_session_id ON callevent (call_session_id);

CREATE TABLE realtimesession (
	id SERIAL NOT NULL,
	tenant_id INTEGER NOT NULL,
	call_session_id UUID NOT NULL,
	provider_session_id VARCHAR(255),
	attempt INTEGER NOT NULL,
	state realtimestate NOT NULL,
	codec VARCHAR(32) NOT NULL,
	sample_rate INTEGER NOT NULL,
	channel_count INTEGER NOT NULL,
	last_event_sequence INTEGER,
	turn_sequence INTEGER NOT NULL,
	playback_id VARCHAR(255),
	started_at TIMESTAMP WITHOUT TIME ZONE,
	ended_at TIMESTAMP WITHOUT TIME ZONE,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT uq_realtime_call UNIQUE (call_session_id),
	FOREIGN KEY(tenant_id) REFERENCES tenant (id),
	FOREIGN KEY(call_session_id) REFERENCES callsession (id)
)

;
CREATE INDEX ix_realtimesession_provider_session_id ON realtimesession (provider_session_id);
CREATE INDEX ix_realtimesession_attempt ON realtimesession (attempt);
CREATE INDEX ix_realtimesession_call_session_id ON realtimesession (call_session_id);
CREATE INDEX ix_realtimesession_tenant_id ON realtimesession (tenant_id);
CREATE INDEX ix_realtimesession_state ON realtimesession (state);

CREATE TABLE speechturn (
	id SERIAL NOT NULL,
	tenant_id INTEGER NOT NULL,
	call_session_id UUID NOT NULL,
	provider_event_key VARCHAR(128) NOT NULL,
	turn_index INTEGER NOT NULL,
	attempt INTEGER NOT NULL,
	speaker_role VARCHAR(32) NOT NULL,
	channel_id VARCHAR(64) NOT NULL,
	transcript VARCHAR(100000) NOT NULL,
	normalized_transcript VARCHAR(100000) NOT NULL,
	is_final BOOLEAN NOT NULL,
	confidence FLOAT,
	start_ms INTEGER,
	end_ms INTEGER,
	asr_provider VARCHAR(100) NOT NULL,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT uq_speechturn_call_event UNIQUE (call_session_id, provider_event_key),
	FOREIGN KEY(tenant_id) REFERENCES tenant (id),
	FOREIGN KEY(call_session_id) REFERENCES callsession (id)
)

;
CREATE INDEX ix_speechturn_created_at ON speechturn (created_at);
CREATE INDEX ix_speechturn_call_session_id ON speechturn (call_session_id);
CREATE INDEX ix_speechturn_tenant_id ON speechturn (tenant_id);
CREATE INDEX ix_speechturn_turn_index ON speechturn (turn_index);
CREATE INDEX ix_speechturn_attempt ON speechturn (attempt);

CREATE TABLE callmetric (
	id SERIAL NOT NULL,
	tenant_id INTEGER NOT NULL,
	call_session_id UUID NOT NULL,
	stage VARCHAR(64) NOT NULL,
	provider VARCHAR(100) NOT NULL,
	duration_ms INTEGER,
	success BOOLEAN NOT NULL,
	error_code VARCHAR(100),
	detail VARCHAR(2000) NOT NULL,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	FOREIGN KEY(tenant_id) REFERENCES tenant (id),
	FOREIGN KEY(call_session_id) REFERENCES callsession (id)
)

;
CREATE INDEX ix_callmetric_call_session_id ON callmetric (call_session_id);
CREATE INDEX ix_callmetric_stage ON callmetric (stage);
CREATE INDEX ix_callmetric_tenant_id ON callmetric (tenant_id);
CREATE INDEX ix_callmetric_created_at ON callmetric (created_at);

CREATE TABLE recordingasset (
	id SERIAL NOT NULL,
	tenant_id INTEGER NOT NULL,
	call_session_id UUID NOT NULL,
	attempt INTEGER NOT NULL,
	provider_recording_id VARCHAR(255),
	provider_url VARCHAR(2000) NOT NULL,
	storage_uri VARCHAR(2000) NOT NULL,
	state VARCHAR(32) NOT NULL,
	duration_sec INTEGER,
	media_format VARCHAR(32) NOT NULL,
	channel_count INTEGER NOT NULL,
	checksum_sha256 VARCHAR(64),
	retention_until TIMESTAMP WITHOUT TIME ZONE,
	deleted_at TIMESTAMP WITHOUT TIME ZONE,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	FOREIGN KEY(tenant_id) REFERENCES tenant (id),
	FOREIGN KEY(call_session_id) REFERENCES callsession (id)
)

;
CREATE INDEX ix_recordingasset_call_session_id ON recordingasset (call_session_id);
CREATE INDEX ix_recordingasset_created_at ON recordingasset (created_at);
CREATE INDEX ix_recordingasset_tenant_id ON recordingasset (tenant_id);
CREATE INDEX ix_recordingasset_state ON recordingasset (state);
CREATE INDEX ix_recordingasset_provider_recording_id ON recordingasset (provider_recording_id);

CREATE TABLE callanalysis (
	id SERIAL NOT NULL,
	tenant_id INTEGER NOT NULL,
	call_session_id UUID NOT NULL,
	result_code VARCHAR(64) NOT NULL,
	sentiment VARCHAR(32) NOT NULL,
	intent VARCHAR(100) NOT NULL,
	summary VARCHAR(10000) NOT NULL,
	qa_score INTEGER NOT NULL,
	qa_flags_json VARCHAR NOT NULL,
	structured_json VARCHAR NOT NULL,
	automatic_result_json VARCHAR NOT NULL,
	needs_review BOOLEAN NOT NULL,
	review_state VARCHAR(32) NOT NULL,
	reviewed_by INTEGER,
	reviewed_at TIMESTAMP WITHOUT TIME ZONE,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT uq_callanalysis_call UNIQUE (call_session_id),
	FOREIGN KEY(tenant_id) REFERENCES tenant (id),
	FOREIGN KEY(call_session_id) REFERENCES callsession (id),
	FOREIGN KEY(reviewed_by) REFERENCES "user" (id)
)

;
CREATE INDEX ix_callanalysis_call_session_id ON callanalysis (call_session_id);
CREATE INDEX ix_callanalysis_tenant_id ON callanalysis (tenant_id);
CREATE INDEX ix_callanalysis_result_code ON callanalysis (result_code);

CREATE TABLE handoffrequest (
	id SERIAL NOT NULL,
	tenant_id INTEGER NOT NULL,
	call_session_id UUID NOT NULL,
	assigned_agent_id INTEGER,
	state handoffstate NOT NULL,
	reason VARCHAR(500) NOT NULL,
	target_group VARCHAR(200) NOT NULL,
	requested_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	responded_at TIMESTAMP WITHOUT TIME ZONE,
	completed_at TIMESTAMP WITHOUT TIME ZONE,
	updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	FOREIGN KEY(tenant_id) REFERENCES tenant (id),
	FOREIGN KEY(call_session_id) REFERENCES callsession (id),
	FOREIGN KEY(assigned_agent_id) REFERENCES "user" (id)
)

;
CREATE INDEX ix_handoffrequest_tenant_id ON handoffrequest (tenant_id);
CREATE INDEX ix_handoffrequest_state ON handoffrequest (state);
CREATE INDEX ix_handoffrequest_call_session_id ON handoffrequest (call_session_id);
CREATE INDEX ix_handoffrequest_assigned_agent_id ON handoffrequest (assigned_agent_id);

CREATE TABLE webhookeventingest (
	id SERIAL NOT NULL,
	call_session_id UUID NOT NULL,
	event_type VARCHAR NOT NULL,
	source VARCHAR NOT NULL,
	provider_event_key VARCHAR NOT NULL,
	repeat_count INTEGER NOT NULL,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	FOREIGN KEY(call_session_id) REFERENCES callsession (id)
)

;
CREATE INDEX ix_webhookeventingest_call_session_id ON webhookeventingest (call_session_id);
CREATE INDEX ix_webhookeventingest_source ON webhookeventingest (source);
CREATE UNIQUE INDEX ix_webhookeventingest_provider_event_key ON webhookeventingest (provider_event_key);
CREATE INDEX ix_webhookeventingest_event_type ON webhookeventingest (event_type);

CREATE TABLE smslog (
	id SERIAL NOT NULL,
	tenant_id INTEGER NOT NULL,
	call_session_id UUID,
	to_phone VARCHAR NOT NULL,
	template_code VARCHAR,
	content VARCHAR NOT NULL,
	state VARCHAR NOT NULL,
	provider_message_id VARCHAR(255),
	provider_error VARCHAR(2000),
	sent_at TIMESTAMP WITHOUT TIME ZONE,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	FOREIGN KEY(tenant_id) REFERENCES tenant (id),
	FOREIGN KEY(call_session_id) REFERENCES callsession (id)
)

;
CREATE INDEX ix_smslog_to_phone ON smslog (to_phone);
CREATE INDEX ix_smslog_provider_message_id ON smslog (provider_message_id);
CREATE INDEX ix_smslog_tenant_id ON smslog (tenant_id);

CREATE TABLE callusage (
	id SERIAL NOT NULL,
	tenant_id INTEGER NOT NULL,
	call_session_id UUID NOT NULL,
	attempt INTEGER NOT NULL,
	answered_at TIMESTAMP WITHOUT TIME ZONE,
	ended_at TIMESTAMP WITHOUT TIME ZONE,
	ai_ended_at TIMESTAMP WITHOUT TIME ZONE,
	telephony_seconds FLOAT,
	ai_seconds FLOAT,
	duration_source VARCHAR NOT NULL,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT uq_callusage_attempt UNIQUE (call_session_id, attempt),
	FOREIGN KEY(tenant_id) REFERENCES tenant (id),
	FOREIGN KEY(call_session_id) REFERENCES callsession (id)
)

;
CREATE INDEX ix_callusage_tenant_id ON callusage (tenant_id);
CREATE INDEX ix_callusage_call_session_id ON callusage (call_session_id);
