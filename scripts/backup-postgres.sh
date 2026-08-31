#!/usr/bin/env bash
set -euo pipefail

checksum() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

: "${BACKUP_DATABASE_URL:?Set BACKUP_DATABASE_URL to a PostgreSQL connection URL}"
: "${BACKUP_DIR:?Set BACKUP_DIR to an explicit backup directory}"

mkdir -p "${BACKUP_DIR}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
archive="${BACKUP_DIR%/}/ai-outbound-${timestamp}.dump"
temporary="${archive}.partial"
trap 'rm -f "${temporary}"' EXIT

pg_dump \
  --dbname="${BACKUP_DATABASE_URL}" \
  --format=custom \
  --compress=9 \
  --no-owner \
  --no-acl \
  --file="${temporary}"

pg_restore --list "${temporary}" >/dev/null
mv "${temporary}" "${archive}"
printf '%s  %s\n' "$(checksum "${archive}")" "$(basename "${archive}")" >"${archive}.sha256"
trap - EXIT
printf '%s\n' "${archive}"
