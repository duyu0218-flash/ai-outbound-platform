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

assert_ok() {
  local code="$1"
  local name="$2"
  if [ "$code" -lt 200 ] || [ "$code" -ge 300 ]; then
    echo "[FAIL] $name => HTTP $code" >&2
    exit 1
  fi
  echo "[OK] $name => HTTP $code"
}

login_admin() {
  local token
  token=$(curl -sS -X POST "$BASE_URL/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" | jq -r '.access_token')
  if [ -z "$token" ] || [ "$token" = "null" ]; then
    echo "登录失败" >&2
    exit 1
  fi
  echo "$token"
}

http_status() {
  local method="$1"
  local url="$2"
  local data="$3"
  local auth="${4:-}"
  if [ "$method" = "GET" ]; then
    curl -sS -o /tmp/smoke_resp.json -w '%{http_code}' -X "$method" \
      -H "x-api-key: $API_KEY" \
      -H "x-tenant-id: $TENANT_ID" \
      ${auth:+-H "Authorization: Bearer $auth"} \
      "$url"
    return
  fi

  curl -sS -o /tmp/smoke_resp.json -w '%{http_code}' -X "$method" \
    -H "x-api-key: $API_KEY" \
    -H "x-tenant-id: $TENANT_ID" \
    -H 'Content-Type: application/json' \
    ${auth:+-H "Authorization: Bearer $auth"} \
    -d "$data" \
    "$url"
}

need_json

ADMIN_TOKEN="$(login_admin)"

echo "== 1) 健康检查 =="
CODE=$(http_status GET "$BASE_URL/health" "")
assert_ok "$CODE" "GET /health"


echo "== 2) 基础鉴权 =="
CODE=$(http_status GET "$BASE_URL/api/v1/auth/me" "" "$ADMIN_TOKEN")
assert_ok "$CODE" "GET /api/v1/auth/me"


echo "== 3) 新建联系人 =="
PHONE="1$(date +%s | tr -d '\n')"
BODY=$(cat <<JSON
{
  "phone": "$PHONE",
  "name": "smoke-contact",
  "tags": "demo",
  "consent_state": "consented",
  "dnc": false,
  "timezone": "Asia/Shanghai"
}
JSON
)
CODE=$(http_status POST "$BASE_URL/api/v1/contacts" "$BODY")
assert_ok "$CODE" "POST /api/v1/contacts"
CONTACT_ID=$(jq -r '.id' /tmp/smoke_resp.json)
if [ -z "$CONTACT_ID" ] || [ "$CONTACT_ID" = "null" ]; then
  echo "联系人 ID 获取失败" >&2
  exit 1
fi


echo "== 4) 新建话术模板 =="
BODY=$(cat <<JSON
{
  "name": "smoke-template",
  "content": "您好，这里是演示话术，请问我可以为您提供什么帮助？",
  "category": "demo",
  "description": "smoke run",
  "tags": "smoke"
}
JSON
)
CODE=$(http_status POST "$BASE_URL/api/v1/script-templates" "$BODY")
assert_ok "$CODE" "POST /api/v1/script-templates"
TEMPLATE_ID=$(jq -r '.id' /tmp/smoke_resp.json)


echo "== 5) 基于模板创建活动 =="
BODY=$(cat <<JSON
{
  "name": "smoke-campaign",
  "script_template_id": $TEMPLATE_ID,
  "mode": "ai_handoff",
  "concurrency": 5,
  "retry_limit": 1,
  "retry_interval_sec": 30,
  "attempt_interval_sec": 1200,
  "recording_enabled": true,
  "hangup_sms_enabled": true,
  "contact_ids": [$CONTACT_ID]
}
JSON
)
CODE=$(http_status POST "$BASE_URL/api/v1/campaigns" "$BODY")
assert_ok "$CODE" "POST /api/v1/campaigns"
CAMPAIGN_ID=$(jq -r '.id' /tmp/smoke_resp.json)


echo "== 6) 启动活动（1 条）=="
CODE=$(curl -sS -o /tmp/smoke_resp.json -w '%{http_code}' -X POST \
  -H "x-api-key: $API_KEY" \
  -H "x-tenant-id: $TENANT_ID" \
  "$BASE_URL/api/v1/campaigns/$CAMPAIGN_ID/start?max_dials=1&async_dial=false")
assert_ok "$CODE" "POST /api/v1/campaigns/{id}/start"
CALL_IDS_COUNT=$(jq -r '.auto_dial_count // 0' /tmp/smoke_resp.json)
echo "created call count: $CALL_IDS_COUNT"


echo "== 7) 查询通话列表 =="
CODE=$(http_status GET "$BASE_URL/api/v1/calls?page=1&size=20" "")
assert_ok "$CODE" "GET /api/v1/calls"
CALL_ID=$(jq -r 'if length>0 then .[0].id else empty end' /tmp/smoke_resp.json)
if [ -z "$CALL_ID" ] || [ "$CALL_ID" = "null" ]; then
  echo "未拿到通话 ID（可继续检查 /api/v1/calls 返回）" >&2
else
  echo "sample call_id: $CALL_ID"
  echo "== 8) 查询事件 =="
  CODE=$(http_status GET "$BASE_URL/api/v1/calls/$CALL_ID/events?page=1&size=10" "")
  assert_ok "$CODE" "GET /api/v1/calls/{id}/events"
fi

echo "SMOKE 完成"
