#!/usr/bin/env bash
set -euo pipefail
module_dir="$(cd "$(dirname "$0")/.." && pwd)"
repo_dir="$(cd "$module_dir/.." && pwd)"
# Pinned official release and checksums; no unverified downloaded executable.
python3 - "$repo_dir" <<'PY'
import hashlib, pathlib, platform, sys, urllib.request
assets = {
    ('Linux', 'x86_64'): ('linux_amd64', '160c6253e2922e9e0a6a9771c5c4c40a4b3c6090b34a3cdd5e80c96cc2e4218b'),
    ('Darwin', 'arm64'): ('darwin_arm64', 'd4926ef8da134332543be276dff157e4c170f99ad91fc48e89448a8816bdf70b'),
}
asset, digest = assets[(platform.system(), platform.machine())]
binary = pathlib.Path(sys.argv[1]) / '.tools/osv-scanner'
binary.parent.mkdir(exist_ok=True)
if not binary.exists() or hashlib.sha256(binary.read_bytes()).hexdigest() != digest:
    temporary = binary.with_suffix('.download')
    urllib.request.urlretrieve('https://github.com/google/osv-scanner/releases/download/v2.7.0/osv-scanner_' + asset, temporary)
    if hashlib.sha256(temporary.read_bytes()).hexdigest() != digest:
        raise SystemExit('OSV Scanner checksum mismatch')
    temporary.replace(binary)
binary.chmod(0o755)
PY
bash "$module_dir/scripts/build-java17.sh" -B -Psecurity -DskipTests package cyclonedx:makeAggregateBom spotbugs:spotbugs "$@"
rm -f "$module_dir/target/osv.json"
status=0
"$repo_dir/.tools/osv-scanner" scan source --lockfile "$module_dir/target/java-dependencies.cdx.json" \
  --all-packages --format json --output-file "$module_dir/target/osv.json" || status=$?
# 1 means vulnerabilities were found; all other failures remain fatal.
if [[ "$status" != 0 && "$status" != 1 ]]; then exit "$status"; fi
python3 "$module_dir/scripts/security_check.py"
