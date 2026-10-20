-- Admission checks exact phone after narrowing by the indexed prefix. No unique
-- key or existing column is changed; duplicate history remains available.
CREATE INDEX ix_callsession_phone_active ON callsession(tenant_id,phone(32),status);
CREATE INDEX ix_callsession_agent_active ON callsession(tenant_id,human_agent_id,status);
CREATE INDEX ix_handoff_waiting ON handoffrequest(tenant_id,state,requested_at,id);
