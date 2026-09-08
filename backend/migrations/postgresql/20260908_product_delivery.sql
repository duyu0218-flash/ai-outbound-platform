-- Additive product delivery schema. Run before deploying the matching application.

BEGIN;


CREATE TABLE IF NOT EXISTS scenarioversion (
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

CREATE INDEX IF NOT EXISTS ix_scenarioversion_campaign_id ON scenarioversion (campaign_id);

CREATE INDEX IF NOT EXISTS ix_scenarioversion_tenant_id ON scenarioversion (tenant_id);


CREATE TABLE IF NOT EXISTS conversationstate (
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

CREATE INDEX IF NOT EXISTS ix_conversationstate_call_id ON conversationstate (call_id);

CREATE INDEX IF NOT EXISTS ix_conversationstate_deadline ON conversationstate (deadline);

CREATE INDEX IF NOT EXISTS ix_conversationstate_tenant_id ON conversationstate (tenant_id);


CREATE TABLE IF NOT EXISTS phonesuppression (
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

CREATE INDEX IF NOT EXISTS ix_phonesuppression_phone ON phonesuppression (phone);

CREATE INDEX IF NOT EXISTS ix_phonesuppression_tenant_id ON phonesuppression (tenant_id);


CREATE TABLE IF NOT EXISTS callbackappointment (
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

CREATE INDEX IF NOT EXISTS ix_callbackappointment_scheduled_at ON callbackappointment (scheduled_at);

CREATE INDEX IF NOT EXISTS ix_callbackappointment_source_call_id ON callbackappointment (source_call_id);

CREATE INDEX IF NOT EXISTS ix_callbackappointment_state ON callbackappointment (state);

CREATE INDEX IF NOT EXISTS ix_callbackappointment_tenant_id ON callbackappointment (tenant_id);


CREATE TABLE IF NOT EXISTS productworkitem (
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

CREATE INDEX IF NOT EXISTS ix_productworkitem_kind ON productworkitem (kind);

CREATE INDEX IF NOT EXISTS ix_productworkitem_state ON productworkitem (state);

CREATE INDEX IF NOT EXISTS ix_productworkitem_tenant_id ON productworkitem (tenant_id);

ALTER TABLE knowledgeitem ADD COLUMN IF NOT EXISTS source VARCHAR(2000) NOT NULL DEFAULT '';

ALTER TABLE knowledgeitem ADD COLUMN IF NOT EXISTS valid_from TIMESTAMP;

ALTER TABLE knowledgeitem ADD COLUMN IF NOT EXISTS valid_until TIMESTAMP;

ALTER TABLE knowledgeitem ADD COLUMN IF NOT EXISTS campaign_id INTEGER;

COMMIT;
