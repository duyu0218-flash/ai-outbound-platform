#!/usr/bin/env bash
set -euo pipefail

checksum() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

: "${BACKUP_ARCHIVE:?Set BACKUP_ARCHIVE to a .dump file}"

if [[ ! -f "${BACKUP_ARCHIVE}" || ! -f "${BACKUP_ARCHIVE}.sha256" ]]; then
  printf '%s\n' "backup archive or checksum file is missing" >&2
  exit 1
fi

read -r expected_checksum _ <"${BACKUP_ARCHIVE}.sha256"
actual_checksum="$(checksum "${BACKUP_ARCHIVE}")"
if [[ -z "${expected_checksum}" || "${expected_checksum}" != "${actual_checksum}" ]]; then
  printf '%s\n' "backup checksum mismatch" >&2
  exit 1
fi
pg_restore --list "${BACKUP_ARCHIVE}" >/dev/null
printf '%s\n' "backup verified: ${BACKUP_ARCHIVE}"
