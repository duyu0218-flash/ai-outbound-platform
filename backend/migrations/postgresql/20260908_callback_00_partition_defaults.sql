-- Run before the inbox migration when bootstrap has already created this table.
-- ORM defaults are client-side; the seed INSERT needs database defaults too.
-- Keep the original inbox migration unchanged for databases that recorded its checksum.
ALTER TABLE IF EXISTS callbackinboxpartition
    ALTER COLUMN pending_count SET DEFAULT 0,
    ALTER COLUMN pending_bytes SET DEFAULT 0;
