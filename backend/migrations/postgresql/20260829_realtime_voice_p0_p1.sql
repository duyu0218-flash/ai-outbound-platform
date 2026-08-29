-- P0/P1 realtime voice, handoff, recording, quality and knowledge modules.
-- Back up the database and stop application writes before applying.

BEGIN;

CREATE TABLE IF NOT EXISTS realtimesession (
    id SERIAL PRIMARY KEY,
    tenant_id INTEGER NOT NULL REFERENCES tenant(id),
    call_session_id UUID NOT NULL REFERENCES callsession(id),
    provider_session_id VARCHAR(255),
    state VARCHAR(32) NOT NULL DEFAULT 'CREATED',
    codec VARCHAR(32) NOT NULL DEFAULT 'pcm_s16le',
    sample_rate INTEGER NOT NULL DEFAULT 16000,
    channel_count INTEGER NOT NULL DEFAULT 1,
    turn_sequence INTEGER NOT NULL DEFAULT 0,
    playback_id VARCHAR(255),
    started_at TIMESTAMP,
    ended_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_realtime_call UNIQUE (call_session_id)
);

CREATE TABLE IF NOT EXISTS speechturn (
    id SERIAL PRIMARY KEY,
    tenant_id INTEGER NOT NULL REFERENCES tenant(id),
    call_session_id UUID NOT NULL REFERENCES callsession(id),
    provider_event_key VARCHAR(128) NOT NULL,
    turn_index INTEGER NOT NULL DEFAULT 0,
    speaker_role VARCHAR(32) NOT NULL DEFAULT 'customer',
    channel_id VARCHAR(64) NOT NULL DEFAULT 'inbound',
    transcript TEXT NOT NULL DEFAULT '',
    normalized_transcript TEXT NOT NULL DEFAULT '',
    is_final BOOLEAN NOT NULL DEFAULT FALSE,
    confidence DOUBLE PRECISION,
    start_ms INTEGER,
    end_ms INTEGER,
    asr_provider VARCHAR(100) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_speechturn_call_event UNIQUE (call_session_id, provider_event_key)
);

CREATE TABLE IF NOT EXISTS callmetric (
    id SERIAL PRIMARY KEY,
    tenant_id INTEGER NOT NULL REFERENCES tenant(id),
    call_session_id UUID NOT NULL REFERENCES callsession(id),
    stage VARCHAR(64) NOT NULL,
    provider VARCHAR(100) NOT NULL DEFAULT '',
    duration_ms INTEGER,
    success BOOLEAN NOT NULL DEFAULT TRUE,
    error_code VARCHAR(100),
    detail VARCHAR(2000) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS recordingasset (
    id SERIAL PRIMARY KEY,
    tenant_id INTEGER NOT NULL REFERENCES tenant(id),
    call_session_id UUID NOT NULL REFERENCES callsession(id),
    provider_recording_id VARCHAR(255),
    provider_url VARCHAR(2000) NOT NULL DEFAULT '',
    storage_uri VARCHAR(2000) NOT NULL DEFAULT '',
    state VARCHAR(32) NOT NULL DEFAULT 'available',
    duration_sec INTEGER,
    media_format VARCHAR(32) NOT NULL DEFAULT '',
    channel_count INTEGER NOT NULL DEFAULT 1,
    checksum_sha256 VARCHAR(64),
    retention_until TIMESTAMP,
    deleted_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS callanalysis (
    id SERIAL PRIMARY KEY,
    tenant_id INTEGER NOT NULL REFERENCES tenant(id),
    call_session_id UUID NOT NULL REFERENCES callsession(id),
    result_code VARCHAR(64) NOT NULL DEFAULT 'unknown',
    sentiment VARCHAR(32) NOT NULL DEFAULT 'neutral',
    intent VARCHAR(100) NOT NULL DEFAULT 'unknown',
    summary VARCHAR(10000) NOT NULL DEFAULT '',
    qa_score INTEGER NOT NULL DEFAULT 0,
    qa_flags_json TEXT NOT NULL DEFAULT '[]',
    structured_json TEXT NOT NULL DEFAULT '{}',
    review_state VARCHAR(32) NOT NULL DEFAULT 'auto',
    reviewed_by INTEGER REFERENCES "user"(id),
    reviewed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_callanalysis_call UNIQUE (call_session_id)
);

CREATE TABLE IF NOT EXISTS knowledgeitem (
    id SERIAL PRIMARY KEY,
    tenant_id INTEGER NOT NULL REFERENCES tenant(id),
    title VARCHAR(300) NOT NULL,
    content TEXT NOT NULL,
    category VARCHAR(100) NOT NULL DEFAULT 'default',
    keywords VARCHAR(2000) NOT NULL DEFAULT '',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    version INTEGER NOT NULL DEFAULT 1,
    created_by INTEGER REFERENCES "user"(id),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS handoffrequest (
    id SERIAL PRIMARY KEY,
    tenant_id INTEGER NOT NULL REFERENCES tenant(id),
    call_session_id UUID NOT NULL REFERENCES callsession(id),
    assigned_agent_id INTEGER REFERENCES "user"(id),
    state VARCHAR(32) NOT NULL DEFAULT 'WAITING',
    reason VARCHAR(500) NOT NULL DEFAULT '',
    target_group VARCHAR(200) NOT NULL DEFAULT '',
    requested_at TIMESTAMP NOT NULL,
    responded_at TIMESTAMP,
    completed_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_realtimesession_call ON realtimesession (call_session_id);
CREATE INDEX IF NOT EXISTS ix_speechturn_call_created ON speechturn (call_session_id, created_at);
CREATE INDEX IF NOT EXISTS ix_callmetric_call_created ON callmetric (call_session_id, created_at);
CREATE INDEX IF NOT EXISTS ix_recordingasset_call ON recordingasset (call_session_id);
CREATE INDEX IF NOT EXISTS ix_callanalysis_tenant_result ON callanalysis (tenant_id, result_code);
CREATE INDEX IF NOT EXISTS ix_knowledgeitem_tenant_category ON knowledgeitem (tenant_id, category);
CREATE INDEX IF NOT EXISTS ix_handoffrequest_call_state ON handoffrequest (call_session_id, state);

COMMIT;
