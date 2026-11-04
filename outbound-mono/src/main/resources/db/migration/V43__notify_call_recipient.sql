ALTER TABLE msg_call_intent ADD COLUMN recipient_kind VARCHAR(16) NOT NULL DEFAULT 'customer',
 DROP INDEX msg_call_attempt, ADD UNIQUE KEY msg_call_attempt(tenant_id,call_id,attempt,recipient_kind);
