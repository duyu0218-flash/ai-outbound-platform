#!/bin/sh
# Run only against an isolated development instance configured with mock telephony,
# JAVA_DISPATCH_ENABLED=true and JAVA_WORKERS_ENABLED=true. Requires curl and jq.
set -eu
: "${MONO_RETRY_URL:?Set the URL of your development mock application}"
: "${MONO_RETRY_USER:?Set a development admin username}"
: "${MONO_RETRY_PASSWORD:?Set its password}"
retry_origin=${MONO_RETRY_URL%/}
retry_token=$(curl --fail-with-body -sS "$retry_origin/api/v1/auth/login" -H 'Content-Type: application/json' --data "$(jq -cn --arg u "$MONO_RETRY_USER" --arg p "$MONO_RETRY_PASSWORD" '{username:$u,password:$p}')" | jq -er .access_token)
api() { retry_method=$1; retry_path=$2; shift 2; curl --fail-with-body -sS -X "$retry_method" "$retry_origin$retry_path" -H "Authorization: Bearer $retry_token" -H 'Content-Type: application/json' "$@"; }
# A dedicated mock tenant may use all hours so the reproduction is time independent.
api PUT /api/v1/admin/settings/compliance --data '{"data":{"allowed_start_hour":0,"allowed_end_hour":0,"max_attempts_per_day":3,"min_attempt_interval_sec":0}}' >/dev/null
retry_phone="19$(date +%s)"
retry_contact=$(api POST /api/v1/contacts --data "$(jq -cn --arg p "$retry_phone" '{phone:$p,name:"Retry HTTP reproduction",consent_state:"consented"}')" | jq -er .id)
retry_call=$(api POST /api/v1/calls --data "$(jq -cn --argjson c "$retry_contact" '{contact_id:$c,mode:"human_only",max_attempts:3}')")
retry_id=$(printf '%s' "$retry_call" | jq -er .id)
printf '%s\n' "$retry_call" | jq -c '{step:"create",status,attempts,max_attempts}'
wait_completed() {
    retry_poll=0
    while [ "$retry_poll" -lt 100 ]; do
        retry_call=$(api GET "/api/v1/calls/$retry_id")
        retry_state=$(printf '%s' "$retry_call" | jq -er .status)
        if [ "$retry_state" = completed ]; then printf '%s\n' "$retry_call" | jq -c '{step:"terminal",status,attempts,max_attempts}'; return; fi
        retry_poll=$((retry_poll + 1)); sleep 0.2
    done
    printf '%s\n' "$retry_call" >&2; echo 'mock call did not complete' >&2; return 1
}
wait_completed
for retry_expected in 2 3; do
    retry_call=$(api POST "/api/v1/calls/$retry_id/retry")
    printf '%s\n' "$retry_call" | jq -e --argjson n "$retry_expected" '.attempts==$n and .attempts<=.max_attempts and (.status=="queued" or .status=="dialing")' >/dev/null
    printf '%s\n' "$retry_call" | jq -c '{step:"retry HTTP 200",status,attempts,max_attempts}'
    api GET "/api/v1/calls/$retry_id" | jq -ce --argjson n "$retry_expected" 'select(.attempts==$n)|{step:"GET after retry",status,attempts,max_attempts}'
    wait_completed
done
retry_tmp=$(mktemp)
trap 'rm -f "$retry_tmp"' EXIT HUP INT TERM
retry_code=$(curl -sS -o "$retry_tmp" -w '%{http_code}' -X POST "$retry_origin/api/v1/calls/$retry_id/retry" -H "Authorization: Bearer $retry_token")
[ "$retry_code" = 409 ]
jq -e '.detail=="reach max attempts"' "$retry_tmp" >/dev/null
printf 'exhausted retry HTTP %s: ' "$retry_code"; cat "$retry_tmp"; printf '\n'
api GET "/api/v1/calls/$retry_id/events?size=100" | jq -ce '[.[]|select(.event_type=="dial_attempt_claimed")]|select(length==3)|{dial_attempt_claimed:length}'
