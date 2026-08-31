#!/usr/bin/env bash
set -euo pipefail

: "${RESTORE_DATABASE_URL:?Set RESTORE_DATABASE_URL to a disposable PostgreSQL database}"
: "${RESTORE_ARCHIVE:?Set RESTORE_ARCHIVE to a verified .dump file}"
: "${RESTORE_CONFIRM:?Set RESTORE_CONFIRM=RESTORE_TO_DISPOSABLE_DATABASE}"

if [[ "${RESTORE_CONFIRM}" != "RESTORE_TO_DISPOSABLE_DATABASE" ]]; then
  printf '%s\n' "restore confirmation value is invalid" >&2
  exit 1
fi

database_url_without_query="${RESTORE_DATABASE_URL%%\?*}"
database_name="${database_url_without_query##*/}"
if [[ "${database_name}" != restore_* ]]; then
  printf '%s\n' "restore drill target database name must start with restore_" >&2
  exit 1
fi

BACKUP_ARCHIVE="${RESTORE_ARCHIVE}" "$(dirname "$0")/verify-postgres-backup.sh"
pg_restore \
  --dbname="${RESTORE_DATABASE_URL}" \
  --clean \
  --if-exists \
  --exit-on-error \
  --no-owner \
  --no-acl \
  "${RESTORE_ARCHIVE}"
printf '%s\n' "restore drill completed: ${database_name}"
