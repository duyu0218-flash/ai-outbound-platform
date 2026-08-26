#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8000}"
API_KEY="${API_KEY:-dev-api-key}"
TENANT_ID="${TENANT_ID:-1}"
ADMIN_USER="${DEMO_ADMIN_USERNAME:-admin}"
ADMIN_PASS="${DEMO_ADMIN_PASSWORD:-12345678}"

need_json() {
  if ! command -v jq >/dev/null 2>&1; then
    echo "请先安装 jq：brew install jq" >&2
    exit 1
  fi
}

need_json

token=$(curl -sS -X POST "$BASE_URL/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" \
  | jq -r '.access_token')
if [ -z "$token" ] || [ "$token" = "null" ]; then
  echo "Admin 登录失败" >&2
  exit 1
fi

PHONE="138000$(date +%H%M%S)"
CONTACT_ID=$(curl -sS -X POST "$BASE_URL/api/v1/contacts" \
  -H "x-api-key: ${API_KEY}" \
  -H "x-tenant-id: ${TENANT_ID}" \
  -H "Content-Type: application/json" \
  -d "{\"phone\":\"${PHONE}\",\"name\":\"验收联系人\",\"consent_state\":\"consented\",\"dnc\":false}" \
  | jq -r '.id')

if [ -z "$CONTACT_ID" ] || [ "$CONTACT_ID" = "null" ]; then
  echo "创建联系人失败" >&2
  exit 1
fi

TEMPLATE_ID=$(curl -sS -X POST "$BASE_URL/api/v1/script-templates" \
  -H "x-api-key: ${API_KEY}" \
  -H "x-tenant-id: ${TENANT_ID}" \
  -H "Content-Type: application/json" \
  -d '{"name":"验收启动模板","content":"您好，这是验收流程校验","category":"check","is_active":true}' \
  | jq -r '.id')
if [ -z "$TEMPLATE_ID" ] || [ "$TEMPLATE_ID" = "null" ]; then
  echo "创建话术模板失败" >&2
  exit 1
fi

CAMPAIGN_ID=$(curl -sS -X POST "$BASE_URL/api/v1/campaigns" \
  -H "x-api-key: ${API_KEY}" \
  -H "x-tenant-id: ${TENANT_ID}" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"验收活动-同步\",\"mode\":\"ai_handoff\",\"script_template_id\":${TEMPLATE_ID},\"contact_ids\":[${CONTACT_ID}]}" \
  | jq -r '.id')
if [ -z "$CAMPAIGN_ID" ] || [ "$CAMPAIGN_ID" = "null" ]; then
  echo "创建活动失败" >&2
  exit 1
fi

echo "[1/3] 验证同步启动（auto_dial=true, async_dial=false）"
SYNC_RESULT=$(curl -sS -X POST "$BASE_URL/api/v1/campaigns/$CAMPAIGN_ID/start?max_dials=1&async_dial=false&auto_dial=true" \
  -H "x-api-key: ${API_KEY}" \
  -H "x-tenant-id: ${TENANT_ID}")
SYNC_MODE=$(echo "$SYNC_RESULT" | jq -r '.dispatch_mode // ""')
SYNC_STATUS=$(echo "$SYNC_RESULT" | jq -r '.dispatch_result.status // ""')
SYNC_SUCCEEDED=$(echo "$SYNC_RESULT" | jq -r '.dispatch_result.succeeded // 0')
SYNC_CODE=$(echo "$SYNC_RESULT" | jq -r '.result_code // ""')
SYNC_ERROR_CODES=$(echo "$SYNC_RESULT" | jq -r '.error_codes // []')
if [ "$SYNC_MODE" != "sync" ]; then
  echo "同步启动失败，dispatch_mode=$SYNC_MODE" >&2
  echo "$SYNC_RESULT"
  exit 1
fi
if [[ -z "$SYNC_CODE" ]]; then
  echo "同步启动未返回 result_code" >&2
  echo "$SYNC_RESULT"
  exit 1
fi
if [ -n "$SYNC_STATUS" ] && [ "$SYNC_STATUS" != "completed" ]; then
  echo "同步启动结果异常，dispatch_result.status=$SYNC_STATUS" >&2
  echo "$SYNC_RESULT"
  exit 1
fi
if [ "$SYNC_SUCCEEDED" -lt 1 ]; then
  echo "同步启动成功数异常，succeeded=$SYNC_SUCCEEDED" >&2
  echo "$SYNC_RESULT"
  exit 1
fi

echo "[2/3] 新建并发活动验证异步启动（async_dial=true）"
CAMPAIGN_ASYNC_ID=$(curl -sS -X POST "$BASE_URL/api/v1/campaigns" \
  -H "x-api-key: ${API_KEY}" \
  -H "x-tenant-id: ${TENANT_ID}" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"验收活动-异步\",\"mode\":\"ai_handoff\",\"script_template_id\":${TEMPLATE_ID},\"contact_ids\":[${CONTACT_ID}]}" \
  | jq -r '.id')
if [ -z "$CAMPAIGN_ASYNC_ID" ] || [ "$CAMPAIGN_ASYNC_ID" = "null" ]; then
  echo "创建活动失败" >&2
  exit 1
fi

ASYNC_RESULT=$(curl -sS -X POST "$BASE_URL/api/v1/campaigns/$CAMPAIGN_ASYNC_ID/start?max_dials=1&async_dial=true&auto_dial=true" \
  -H "x-api-key: ${API_KEY}" \
  -H "x-tenant-id: ${TENANT_ID}")
ASYNC_MODE=$(echo "$ASYNC_RESULT" | jq -r '.dispatch_mode // ""')
ASYNC_STATUS=$(echo "$ASYNC_RESULT" | jq -r '.dispatch_result.status // ""')
ASYNC_CODE=$(echo "$ASYNC_RESULT" | jq -r '.result_code // ""')
ASYNC_ERROR_CODES=$(echo "$ASYNC_RESULT" | jq -r '.error_codes // []')
if [ "$ASYNC_MODE" != "async" ]; then
  echo "异步启动失败，dispatch_mode=$ASYNC_MODE" >&2
  echo "$ASYNC_RESULT"
  exit 1
fi
if [ "$ASYNC_STATUS" != "queued" ]; then
  echo "异步启动状态异常，dispatch_result.status=$ASYNC_STATUS" >&2
  echo "$ASYNC_RESULT"
  exit 1
fi
if [[ -z "$ASYNC_CODE" ]]; then
  echo "异步启动未返回 result_code" >&2
  echo "$ASYNC_RESULT"
  exit 1
fi

echo "[3/3] 验证 max_dials 限制生效"
CALL_COUNT_SYNC=$(curl -sS -G "$BASE_URL/api/v1/calls?page=1&size=20" \
  -H "x-api-key: ${API_KEY}" \
  -H "x-tenant-id: ${TENANT_ID}" \
  -d "campaign_id=$CAMPAIGN_ID" \
  | jq 'if type=="array" then length else 0 end')

CALL_COUNT_ASYNC=$(curl -sS -G "$BASE_URL/api/v1/calls?page=1&size=20" \
  -H "x-api-key: ${API_KEY}" \
  -H "x-tenant-id: ${TENANT_ID}" \
  -d "campaign_id=$CAMPAIGN_ASYNC_ID" \
  | jq 'if type=="array" then length else 0 end')

if [ "$CALL_COUNT_SYNC" -gt 1 ] || [ "$CALL_COUNT_ASYNC" -gt 1 ]; then
  echo "max_dials 上限疑似未严格限制（sync=$CALL_COUNT_SYNC, async=$CALL_COUNT_ASYNC）" >&2
  exit 1
fi

echo "PASS: campaign start sync/async and max_dials basic checks"
echo "SYNC_RESULT: result_code=${SYNC_CODE}, error_codes=${SYNC_ERROR_CODES}"
echo "ASYNC_RESULT: result_code=${ASYNC_CODE}, error_codes=${ASYNC_ERROR_CODES}"
