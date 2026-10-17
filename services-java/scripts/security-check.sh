#!/usr/bin/env bash
set -euo pipefail
module_dir="$(cd "$(dirname "$0")/.." && pwd)"
repo_dir="$(cd "$module_dir/.." && pwd)"
# Download the pinned official scanner with checksum verification.
node "$repo_dir/tools/install-osv.mjs"
bash "$module_dir/scripts/build-java17.sh" -B -Psecurity -DskipTests package cyclonedx:makeAggregateBom spotbugs:spotbugs "$@"
rm -f "$module_dir/target/osv.json"
status=0
"$repo_dir/.tools/osv-scanner" scan source --lockfile "$module_dir/target/java-dependencies.cdx.json" \
  --all-packages --format json --output-file "$module_dir/target/osv.json" || status=$?
# 1 means vulnerabilities were found; all other failures remain fatal.
if [[ "$status" != 0 && "$status" != 1 ]]; then exit "$status"; fi
node "$repo_dir/tools/security-check.mjs" services-java
