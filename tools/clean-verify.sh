#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
for executable in python python3 pip pip3; do
  if command -v "$executable" >/dev/null 2>&1; then
    echo 'Clean acceptance environment contains a forbidden interpreter.' >&2
    exit 1
  fi
done
node tools/check-toolchain.mjs --require-clean-env
node tools/source-inventory.mjs
node --test --test-reporter=junit --test-reporter-destination=artifacts/no-python/tool-tests.xml tools/tests/*.test.mjs
bash backend-java/scripts/build-java17.sh -B verify
bash services-java/scripts/build-java17.sh -B verify
(cd frontend && pnpm build && pnpm test:coverage)
node tools/verify-contracts.mjs
node tools/media-process-smoke.mjs
if [[ "${1:-}" == '--browser' ]]; then
  JAVA_SERVICES_BROWSER=1 JAVA_ALL_BROWSER=1 node tools/stack-smoke.mjs
  node tools/core-browser.mjs --variant queued
  node tools/core-browser.mjs --variant prepared
  node tools/core-browser.mjs --variant dispatch
else
  node tools/stack-smoke.mjs
fi

if [[ "${CLEAN_SECURITY:-0}" == 1 ]]; then
  bash backend-java/scripts/security-check.sh -ntp
  bash services-java/scripts/security-check.sh -ntp
fi
