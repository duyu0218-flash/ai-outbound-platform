-- Cover queue selection by tenant/type/state. Existing schema/constraints stay unchanged.
CREATE INDEX ix_taskoutbox_ready_scope ON taskoutbox (tenant_id, task_type, state, available_at, id);
CREATE INDEX ix_taskoutbox_stream_scope ON taskoutbox (tenant_id, aggregate_id, task_type, state, created_at, id);
CREATE INDEX ix_callbackinbox_expiry ON callbackinbox (state, completed_at, id);
