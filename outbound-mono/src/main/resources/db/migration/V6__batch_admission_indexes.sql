-- Cover admission counters without reading every call/event payload as the pool grows.
-- Existing columns, uniqueness and transaction isolation remain unchanged.
CREATE INDEX ix_call_admission_counts ON callsession(tenant_id,status,telephony_line_id,campaign_id);
CREATE INDEX ix_call_daily_attempts ON callsession(tenant_id,started_at,attempts);
CREATE INDEX ix_event_attempt_daily ON callevent(event_type,created_at,call_session_id);
CREATE INDEX ix_callback_pending_global ON callbackinbox(state,id);
