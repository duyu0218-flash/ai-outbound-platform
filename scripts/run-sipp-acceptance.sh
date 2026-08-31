#!/usr/bin/env sh
set -eu

if [ "${SIPP_CONFIRM_RUN:-}" != "RUN_CONTROLLED_SIPP_TEST" ]; then
  echo "Refusing to send SIP traffic. Set SIPP_CONFIRM_RUN=RUN_CONTROLLED_SIPP_TEST after approving the target." >&2
  exit 2
fi

required_values="SIPP_TARGET SIPP_LOCAL_IP SIPP_SERVICE"
for required_name in $required_values; do
  eval "required_value=\${$required_name:-}"
  if [ -z "$required_value" ]; then
    echo "$required_name is required" >&2
    exit 2
  fi
done

case "$SIPP_TARGET" in ''|-*|*[!A-Za-z0-9_.-]*) echo "SIPP_TARGET must be an IPv4 address or DNS name" >&2; exit 2 ;; esac
case "$SIPP_LOCAL_IP" in ''|*[!0-9.]*) echo "SIPP_LOCAL_IP must be a literal IPv4 address" >&2; exit 2 ;; esac
case "$SIPP_SERVICE" in ''|-*|*[!A-Za-z0-9_.+-]*) echo "SIPP_SERVICE contains unsupported characters" >&2; exit 2 ;; esac

scenario_name="${SIPP_SCENARIO:-options}"
case "$scenario_name" in
  options) scenario_file=/scenarios/options.xml ;;
  uac-basic) scenario_file=/scenarios/uac-basic.xml ;;
  *) echo "SIPP_SCENARIO must be options or uac-basic" >&2; exit 2 ;;
esac

target_port="${SIPP_TARGET_PORT:-5060}"
local_port="${SIPP_LOCAL_PORT:-5061}"
call_rate="${SIPP_CALL_RATE:-1}"
concurrency="${SIPP_CONCURRENCY:-1}"
max_calls="${SIPP_MAX_CALLS:-1}"
transport="${SIPP_TRANSPORT:-udp}"

for numeric_value in "$target_port" "$local_port" "$call_rate" "$concurrency" "$max_calls"; do
  case "$numeric_value" in
    ''|*[!0-9]*) echo "SIPp numeric parameters must be positive integers" >&2; exit 2 ;;
  esac
  if [ "$numeric_value" -lt 1 ]; then
    echo "SIPp numeric parameters must be positive integers" >&2
    exit 2
  fi
done

case "$transport" in
  udp) transport_mode=u1 ;;
  tcp) transport_mode=t1 ;;
  *) echo "SIPP_TRANSPORT must be udp or tcp; TLS requires a site-specific certificate profile" >&2; exit 2 ;;
esac

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
run_id=$(date -u +%Y%m%dT%H%M%SZ)
artifact_dir="$repo_dir/artifacts/sipp/$run_id"
mkdir -p "$artifact_dir"

set -- \
  "$SIPP_TARGET:$target_port" \
  -sf "$scenario_file" \
  -s "$SIPP_SERVICE" \
  -i "$SIPP_LOCAL_IP" \
  -p "$local_port" \
  -t "$transport_mode" \
  -r "$call_rate" \
  -l "$concurrency" \
  -m "$max_calls" \
  -trace_stat \
  -stf "/artifacts/$run_id/statistics.csv" \
  -trace_err \
  -error_file "/artifacts/$run_id/errors.log" \
  -timeout "${SIPP_TIMEOUT_MS:-30000}" \
  -timeout_error

if [ "${SIPP_RTP_ECHO:-false}" = "true" ]; then
  set -- "$@" -rtp_echo
fi

cd "$repo_dir"
docker compose -f docker-compose.sipp.yml run --rm --user "$(id -u):$(id -g)" sipp "$@"
echo "SIPp acceptance artifacts: $artifact_dir"
