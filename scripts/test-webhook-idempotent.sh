#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8000}"
API_KEY="${API_KEY:-dev-api-key}"
TENANT_ID="${TENANT_ID:-1}"
CALL_ID="${CALL_ID:-}"
TELEPHONY_WEBHOOK_TOKEN="${TELEPHONY_WEBHOOK_TOKEN:-}"

if [ -z "$CALL_ID" ]; then
  echo "请先设置 CALL_ID（例如：CALL_ID=<uuid> bash scripts/test-webhook-idempotent.sh）" >&2
  exit 1
fi

need_json() {
  if ! command -v jq >/dev/null 2>&1; then
    echo "请先安装 jq：brew install jq" >&2
    exit 1
  fi
}

need_json

echo "[1/4] 发送第一条重复事件（含固定 event_id）"
curl -sS -X POST "${BASE_URL}/api/v1/webhooks/telephony/status" \
  -H "Content-Type: application/json" \
  -H "x-webhook-token: ${TELEPHONY_WEBHOOK_TOKEN}" \
  -d "{\"call_id\":\"${CALL_ID}\",\"kind\":\"status\",\"payload\":{\"status\":\"answered\",\"event_id\":\"smoke-dup-test\"}}" >/dev/null

echo "[2/4] 再发送一条相同事件"
curl -sS -X POST "${BASE_URL}/api/v1/webhooks/telephony/status" \
  -H "Content-Type: application/json" \
  -H "x-webhook-token: ${TELEPHONY_WEBHOOK_TOKEN}" \
  -d "{\"call_id\":\"${CALL_ID}\",\"kind\":\"status\",\"payload\":{\"status\":\"answered\",\"event_id\":\"smoke-dup-test\"}}" >/dev/null

echo "[3/4] 校验事件去重统计"
STAT=$(curl -sS -G "${BASE_URL}/api/v1/calls/${CALL_ID}/webhook-stats" \
  -H "x-api-key: ${API_KEY}" \
  -H "x-tenant-id: ${TENANT_ID}")
echo "$STAT" | jq -r '.duplicate_estimate'
DUP=$(echo "$STAT" | jq -r '.duplicate_estimate')
if [ "$DUP" -lt 1 ]; then
  echo "webhook 去重计数不符合预期（duplicate_estimate=$DUP）" >&2
  exit 1
fi

echo "[4/4] 校验去重记录条数"
RECORDS=$(curl -sS -G "${BASE_URL}/api/v1/calls/${CALL_ID}/webhook-events?page=1&size=20" \
  -H "x-api-key: ${API_KEY}" \
  -H "x-tenant-id: ${TENANT_ID}")
COUNT=$(echo "$RECORDS" | jq 'length')
if [ "$COUNT" -lt 1 ]; then
  echo "webhook 去重记录未写入（count=$COUNT）" >&2
  exit 1
fi

echo "PASS: duplicate_estimate=$DUP, records=$COUNT"
