-- Contact integrity upgrade for the commercial acceptance release.
-- Run after taking a backup. The migration intentionally aborts when existing
-- duplicate tenant/phone rows are present so operators can merge them without
-- silently losing campaign or call history.

BEGIN;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM contact
        GROUP BY tenant_id, phone
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'duplicate contact phone rows exist; merge them before applying uq_contact_tenant_phone';
    END IF;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_contact_tenant_phone'
    ) THEN
        ALTER TABLE contact
            ADD CONSTRAINT uq_contact_tenant_phone UNIQUE (tenant_id, phone);
    END IF;
END
$$;

COMMIT;
