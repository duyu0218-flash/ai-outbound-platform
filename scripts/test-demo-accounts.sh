#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8000}"
ADMIN_USER="${DEMO_ADMIN_USERNAME:-admin}"
ADMIN_PASS="${DEMO_ADMIN_PASSWORD:-12345678}"
AGENT_USER="${DEMO_AGENT_USERNAME:-1001@test}"
AGENT_PASS="${DEMO_AGENT_PASSWORD:-12345678}"

need_json() {
  if ! command -v jq >/dev/null 2>&1; then
    echo "请先安装 jq：brew install jq" >&2
    exit 1
  fi
}

login() {
  local user="$1"
  local pass="$2"
  local key="$3"
  local token
  token=$(curl -sS -X POST "$BASE_URL/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$user\",\"password\":\"$pass\"}" | jq -r '.access_token')
  if [ -z "$token" ] || [ "$token" = "null" ]; then
    echo "[$key] 登录失败" >&2
    exit 1
  fi
  echo "$token"
}

need_json

echo "== Health Check =="
curl -sS "$BASE_URL/health" | jq .

echo "== Admin 登录与鉴权 =="
ADMIN_TOKEN=$(login "$ADMIN_USER" "$ADMIN_PASS" "admin")

echo "admin access token: ${ADMIN_TOKEN:0:20}..."
curl -sS -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/api/v1/auth/me" | jq .
curl -sS -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/api/v1/admin/dashboard" | jq .

echo "== Agent 登录与鉴权 =="
AGENT_TOKEN=$(login "$AGENT_USER" "$AGENT_PASS" "agent")

echo "agent access token: ${AGENT_TOKEN:0:20}..."
curl -sS -H "Authorization: Bearer $AGENT_TOKEN" "$BASE_URL/api/v1/auth/me" | jq .
curl -sS -H "Authorization: Bearer $AGENT_TOKEN" "$BASE_URL/api/v1/agent/dashboard" | jq .

# 验证角色隔离（可选）：座席请求管理员接口应返回 403
agent_admin_status=$(curl -sS -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer $AGENT_TOKEN" \
  "$BASE_URL/api/v1/admin/dashboard")
if [ "$agent_admin_status" = "403" ]; then
  echo "角色隔离：正常（agent 无法访问 admin）"
else
  echo "角色隔离：异常（agent 可访问 admin）" >&2
  exit 1
fi

echo "验收完成：PASS"
