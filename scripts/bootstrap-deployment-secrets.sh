#!/usr/bin/env sh
set -eu

secret_dir="${1:-.secrets}"
umask 077
mkdir -p "$secret_dir"

create_secret() {
  target_path="$1"
  if [ -s "$target_path" ]; then
    return
  fi
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 32 >"$target_path"
  else
    od -An -N32 -tx1 /dev/urandom | tr -d ' \n' >"$target_path"
  fi
  chmod 600 "$target_path"
}

create_secret "$secret_dir/metrics_token"
create_secret "$secret_dir/grafana_admin_password"

echo "Deployment secrets are ready in $secret_dir"
echo "Use docker-compose.observability.yml; do not commit or copy these values into documentation."
