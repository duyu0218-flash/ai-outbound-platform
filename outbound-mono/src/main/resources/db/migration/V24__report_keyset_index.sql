-- Support tenant-scoped time windows and stable bounded report pages.
-- No business data or existing columns are changed.
ALTER TABLE callsession ADD INDEX ix_call_report_page (tenant_id, created_at, id),
    ALGORITHM=INPLACE, LOCK=NONE;
