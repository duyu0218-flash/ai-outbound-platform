BEGIN TRANSACTION;
CREATE TABLE attempts (
                    call_id TEXT NOT NULL, attempt INTEGER NOT NULL, tenant INTEGER NOT NULL,
                    line TEXT NOT NULL, uuid TEXT NOT NULL UNIQUE, digest TEXT NOT NULL,
                    payload TEXT NOT NULL, created REAL NOT NULL, deadline REAL NOT NULL,
                    cost INTEGER NOT NULL, rate INTEGER NOT NULL, multiplier INTEGER NOT NULL,
                    state TEXT NOT NULL DEFAULT 'pending', result TEXT, ended REAL,
                    observed INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(call_id, attempt));
INSERT INTO "attempts" VALUES('legacy-python-中文',1,1,'0','3cca92dd-41e6-4c55-8e3f-00808470e883','142e5bb6f54f7944e725b2ca9b502372608b45bd47b5141efa1f7f56e9b60801','{"call_id":"legacy-python-中文","metadata":{"attempt":1,"recording_enabled":false,"recording_notice_text":"合成迁移录音告知","tenant_id":1},"phone":"+8613900139000","webhook_url":"http://127.0.0.1:18000/api/v1/webhooks/telephony/status"}',1792205946.0640671,1792206061.0640671,20,10,2,'pending',NULL,NULL,0);
CREATE TABLE audit (
                    id INTEGER PRIMARY KEY, at REAL NOT NULL, action TEXT NOT NULL, detail TEXT NOT NULL);
CREATE TABLE flags (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE nonces (nonce TEXT PRIMARY KEY, expires REAL NOT NULL);
CREATE TABLE outbox (
                    id TEXT PRIMARY KEY, url TEXT NOT NULL, body BLOB NOT NULL,
                    created REAL NOT NULL, due REAL NOT NULL, failures INTEGER NOT NULL DEFAULT 0, stream_key TEXT NOT NULL DEFAULT '');
INSERT INTO "outbox" VALUES('debadd5f66b991a14e7a47554619d4904b969bb9b77c6ebcfaa55abc270f0e3b','http://127.0.0.1:18000/api/v1/webhooks/telephony/status',X'7B2263616C6C5F6964223A226C65676163792D707974686F6E2DE4B8ADE69687222C2273657175656E6365223A317D',1792205946.072366,1792205946.072366,0,'legacy-python-中文');
CREATE TABLE voice_action_commands (
        id TEXT PRIMARY KEY, digest TEXT NOT NULL, result TEXT,
        call_id TEXT NOT NULL DEFAULT '', attempt INTEGER NOT NULL DEFAULT 0);
INSERT INTO "voice_action_commands" VALUES('legacy-unknown-command','1ae3c9a27ede1f15af55a358c2bf29a33adf62edca7c66baf032b794b9cbe499',NULL,'legacy-python-中文',1);
CREATE TABLE voice_permits (
        id TEXT PRIMARY KEY, scope TEXT NOT NULL, kind TEXT NOT NULL,
        call_id TEXT NOT NULL, attempt INTEGER NOT NULL, owner TEXT NOT NULL,
        state TEXT NOT NULL, created REAL NOT NULL);
INSERT INTO "voice_permits" VALUES('reserve:legacy-python-中文:1','synthetic/migration/local/asr','reserve','legacy-python-中文',1,'controller','active',1792205946.070384);
INSERT INTO "voice_permits" VALUES('legacy-unknown-asr','synthetic/migration/local/asr','asr','legacy-python-中文',1,'legacy-owner','unknown',1792205946.0714061);
CREATE INDEX attempts_scope ON attempts(tenant,line,created);
CREATE INDEX attempts_ended ON attempts(state,ended);
CREATE INDEX attempts_created ON attempts(created);
CREATE INDEX outbox_stream_order ON outbox(stream_key,created);
CREATE INDEX outbox_created_order ON outbox(created);
CREATE INDEX voice_permits_scope ON voice_permits(scope,state);
CREATE INDEX voice_permits_retention ON voice_permits(state,created);
CREATE INDEX voice_commands_call ON voice_action_commands(call_id,attempt);
COMMIT;
