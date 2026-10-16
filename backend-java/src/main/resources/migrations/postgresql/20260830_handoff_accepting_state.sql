-- Add the transient claim state used to serialize agent handoff acceptance.
-- Existing PostgreSQL installations created by SQLModel use a native enum.

ALTER TYPE handoffstate ADD VALUE IF NOT EXISTS 'ACCEPTING';
