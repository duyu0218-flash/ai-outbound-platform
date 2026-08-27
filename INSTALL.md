# AI 外呼平台安装与部署手册

本文档面向你们当前仓库，给出从开发环境到生产环境的完整可执行流程。按该文档可完成：

- 基础服务部署（PostgreSQL/Redis/MinIO）
- 后端控制面（`control-api`）与 AI 服务（`ai-agent`）启动
- 基础联调验证
- 海外扩展的先期准备

> 本手册默认仓库名为 `ai-outbound-platform`，安装位置为你的本机目录。

## 1. 前置条件

### 1.1 必要软件

- Docker Desktop / Docker Engine（推荐）
- docker-compose v2+
- Git
- jq（脚本验收依赖）
- 可选：Python 3.11、Node.js（仅用于本地开发工具）

安装示例（macOS）：
```bash
brew install jq
```

### 1.2 目录结构确认

- `backend/`：控制面服务（FastAPI）
- `agent/`：AI 话术策略服务（FastAPI）
- `docker-compose.yml`：本地一键启动文件
- `.env.example`：环境变量示例
- `.github/workflows/ci.yml`：CI 静态编译与后端回归测试

## 2. 一次性获取代码

```bash
git clone git@github.com:<your-org>/ai-outbound-platform.git
cd ai-outbound-platform
```

如果你已经有仓库，可跳过此步，直接在项目根目录执行后续步骤。

## 3. 配置环境变量

### 3.1 生成 `.env`

```bash
cp .env.example .env
```

编辑 `.env`，至少更新以下参数：

- `API_KEY`：你的管理 API Key（用于所有控制面 API 调用）
- `DATABASE_URL`：数据库连接字符串
- `REDIS_URL`：Redis 连接字符串
- `TELEPHONY_PROVIDER`：`mock`（联调）或 `http`（接入真实 PBX）
- `TELEPHONY_WEBHOOK_TOKEN`：建议给网关回调加签
- `AI_AGENT_URL`：AI 服务地址（compose 下默认 `http://ai-agent:8001`）
- `SMS_PROVIDER`：`mock` 或对接真实短信供应商
- 生产化增强参数（建议按环境调参）：
  - `REQUEST_TIMEOUT_MS=15000`（单请求超时）
  - `REQUEST_ID_HEADER=X-Request-ID`（链路透传）
  - `TRUSTED_HOSTS=localhost,127.0.0.1`（生产改成你的域名白名单）
  - `RATE_LIMIT_ENABLED=true`
  - `RATE_LIMIT_DEFAULT_RPM=600`
  - `RATE_LIMIT_AUTH_RPM=60`
  - `RATE_LIMIT_WINDOW_SEC=60`
  - `DATABASE_POOL_SIZE=10`
  - `DATABASE_MAX_OVERFLOW=20`
  - `DATABASE_POOL_TIMEOUT_SEC=30`
  - `DATABASE_POOL_RECYCLE_SEC=1800`

### 3.2 默认测试账号体系（推荐先验收）

服务启动会创建两类默认测试账号：

- 管理员：`admin / 12345678`（角色：`admin`）
- 座席：`1001@test / 12345678`（角色：`agent`）

访问方式：

- 管理端地址：[http://localhost:8000/admin](http://localhost:8000/admin)
- 座席端地址：[http://localhost:8000/agent](http://localhost:8000/agent)
- 文档地址：[http://localhost:8000/docs.html](http://localhost:8000/docs.html)

直接登录 API：

```bash
curl -X POST http://localhost:8000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"12345678"}'
```

生产环境必须在数据库中预置正式管理员账号后设置 `DEMO_USERS_ENABLED=false`；演示密码不得带入生产。当前仓库尚未提供生产用户管理界面，正式账号的开通、停用与密码重置流程属于上线前置项。

## 4. Docker 方式启动（推荐）

### 4.1 一键启动

```bash
docker compose up -d --build
```

### 4.2 查看服务状态

```bash
docker compose ps
docker compose logs -f control-api
docker compose logs -f ai-agent
```

### 4.3 健康检查

```bash
curl http://localhost:8000/health
curl http://localhost:8001/health
curl http://localhost:8000/readyz
```

### 4.4 生产健康就绪验收

```bash
curl -s http://localhost:8000/health | jq
curl -s http://localhost:8000/readyz | jq
curl -i http://localhost:8000/api/v1/calls -H "x-api-key: dev-api-key" -H "x-tenant-id: 1" | head -n 1
```

验收要点：

- `/health` 为“存活探针”：返回 `200` 且 `checks.db=ok`、`checks.redis=ok`（或 `redis` 未配置时也可为 `ok`）。
- `/readyz` 为“就绪探针”：除 `db/redis` 外还会检查 `ai_agent` 与 `telephony`，用于编排器和切流前置校验。
- 所有 API 响应应包含 `request_id`，便于后续定位故障。
- 触发频控场景应返回 `429`，且有 `Retry-After` 与 `X-RateLimit-*`。

## 5. 本地开发运行方式（不走 docker）

> 仅用于开发排查，生产不推荐直接用此方式。

### 5.1 启动数据库

- 使用本机 PostgreSQL/Redis，或将 `DATABASE_URL` / `REDIS_URL` 指向云端服务。

### 5.2 安装依赖并启动服务

```bash
# 后端控制面
cd backend
python3 -m venv .venv
source .venv/bin/activate
pip install -e .
export APP_PORT=8000
uvicorn app.main:app --host 0.0.0.0 --port 8000

# 新开终端，启动 AI 服务
cd ../agent
python3 -m venv .venv
source .venv/bin/activate
pip install -e .
uvicorn app.main:app --host 0.0.0.0 --port 8001
```

## 6. 快速功能验收清单（第一轮）

以下步骤建议每次部署后都执行：

1. 新建联系人（可选：设置同意与 DNC）
   ```bash
   curl -X POST http://localhost:8000/api/v1/contacts \
    -H "x-api-key: <你的 API_KEY>" \
    -H "x-tenant-id: 1" \
    -H "Content-Type: application/json" \
    -d '{"phone":"13800000000","name":"测试用户","tags":"onboard","consent_state":"consented"}'
   ```
2. 新建活动并绑定联系人
   ```bash
   curl -X POST http://localhost:8000/api/v1/campaigns \
    -H "x-api-key: <你的 API_KEY>" \
    -H "x-tenant-id: 1" \
    -H "Content-Type: application/json" \
    -d '{"name":"demo-活动","script":"标准话术","mode":"ai_handoff","contact_ids":[1]}'
   ```
3. 新建话术模板并绑定活动（推荐）
   ```bash
   curl -X POST http://localhost:8000/api/v1/script-templates \
    -H "x-api-key: <你的 API_KEY>" \
    -H "x-tenant-id: 1" \
    -H "Content-Type: application/json" \
    -d '{"name":"售前话术","category":"sales","content":"您好，{客户姓名}，请问我是否可以先确认您的来电需求？","is_active":true}'

   curl -X POST http://localhost:8000/api/v1/campaigns \
    -H "x-api-key: <你的 API_KEY>" \
    -H "x-tenant-id: 1" \
    -H "Content-Type: application/json" \
    -d '{"name":"template-活动","script_template_id":1,"mode":"ai_handoff","contact_ids":[1]}'
   ```
4. 查看模板列表
   ```bash
   curl -X GET "http://localhost:8000/api/v1/script-templates?active_only=true&page=1&size=20" \
    -H "x-api-key: <你的 API_KEY>" \
    -H "x-tenant-id: 1"
   ```
5. 启动活动（模拟自动拨号）
   ```bash
   curl -X POST "http://localhost:8000/api/v1/campaigns/1/start?max_dials=10" \
    -H "x-api-key: <你的 API_KEY>" \
    -H "x-tenant-id: 1"
   ```
6. 查询通话会话
   ```bash
   curl -X GET "http://localhost:8000/api/v1/calls?page=1&size=20" \
    -H "x-api-key: <你的 API_KEY>" \
    -H "x-tenant-id: 1"
   ```
7. 查询通话事件（用于排障）
   ```bash
   curl -X GET "http://localhost:8000/api/v1/calls/<call_id>/events" \
    -H "x-api-key: <你的 API_KEY>" \
    -H "x-tenant-id: 1"
   ```

## 6. 账号体系验收（推荐）

启动服务后，直接一键执行：

```bash
bash scripts/test-demo-accounts.sh
```

> 提示：`scripts/test-demo-accounts.sh` 依赖 `jq` 解析返回 JSON。

脚本会自动验证：
- `/health`
- admin 与 agent 登录 token 获取
- `GET /api/v1/auth/me`
- `GET /api/v1/admin/dashboard` 与 `GET /api/v1/agent/dashboard`
- 角色隔离（agent 不能访问 admin 接口）

说明：管理员账户可访问两类控制台，座席仅可访问座席控制台

如需做一次完整 API 流程 smoke（联系人→模板→活动→启动→通话→事件），再执行：

```bash
bash scripts/smoke-outbound-api.sh
```

## 6.1 一体化验收命令清单（上/下线前可直接执行）

### 6.1.1 活动异步拨号增强验收

建议执行一次异步启动链路（`async_dial=true`）：

```bash
CAMPAIGN_ID=<你的活动ID>
curl -sS -X POST "${BASE_URL}/api/v1/campaigns/${CAMPAIGN_ID}/start?auto_dial=true&async_dial=true&max_dials=5" \
  -H "x-api-key: ${API_KEY}" \
  -H "x-tenant-id: ${TENANT_ID}" | jq .
```

返回 `dispatch_mode=async` 且 `dispatch_result.status=queued` 视为通过。随后立即查询：

```bash
curl -sS -X GET "${BASE_URL}/api/v1/calls?page=1&size=20" \
  -H "x-api-key: ${API_KEY}" \
  -H "x-tenant-id: ${TENANT_ID}" | jq .
```

如果你要切回同步可控验证，用 `async_dial=false`。

### 6.1.2 webhook 回调幂等增强验收

取任意会话 ID（如 `/api/v1/calls` 首条），重复同一 webhook 回调两次，事件只应记录一次（前提：回调 payload 去重）：

```bash
CALL_ID=<你的通话ID>
curl -sS -X POST "${BASE_URL}/api/v1/webhooks/telephony/status" \
  -H "Content-Type: application/json" \
  -H "x-webhook-token: ${TELEPHONY_WEBHOOK_TOKEN}" \
  -d "{\"call_id\":\"${CALL_ID}\",\"kind\":\"status\",\"payload\":{\"status\":\"answered\",\"event_id\":\"evt-dup-test\"}}"

curl -sS -X POST "${BASE_URL}/api/v1/webhooks/telephony/status" \
  -H "Content-Type: application/json" \
  -H "x-webhook-token: ${TELEPHONY_WEBHOOK_TOKEN}" \
  -d "{\"call_id\":\"${CALL_ID}\",\"kind\":\"status\",\"payload\":{\"status\":\"answered\",\"event_id\":\"evt-dup-test\"}}"

curl -sS -G "${BASE_URL}/api/v1/calls/${CALL_ID}/events?page=1&size=20" \
  -H "x-api-key: ${API_KEY}" \
  -H "x-tenant-id: ${TENANT_ID}" | jq .
```

理想现象：重复请求都返回 200，但事件列表仅新增 1 条（同一通话状态快照不重复）。

执行后续命令可把验收标准化（查询 webhook 去重计数/重复记录）：

```bash
bash scripts/test-webhook-idempotent.sh
```

建议在部署完成后，按顺序执行以下命令，任意一步失败即停止并排查。

```bash
set -euo pipefail

# 统一环境变量
export BASE_URL="${BASE_URL:-http://localhost:8000}"
export API_KEY="${API_KEY:-dev-api-key}"
export TENANT_ID="${TENANT_ID:-1}"
export ADMIN_USER="${DEMO_ADMIN_USERNAME:-admin}"
export ADMIN_PASS="${DEMO_ADMIN_PASSWORD:-12345678}"
export AGENT_USER="${DEMO_AGENT_USERNAME:-1001@test}"
export AGENT_PASS="${DEMO_AGENT_PASSWORD:-12345678}"

echo "1) 健康检查"
curl -sS "${BASE_URL}/health" | jq .
curl -sS "${BASE_URL}/readyz" | jq .
curl -sS "http://localhost:8001/health" | jq .

echo "2) 页面入口检查"
curl -sS -o /dev/null -w "%{http_code}\n" "${BASE_URL}/admin"            # 200
curl -sS -o /dev/null -w "%{http_code}\n" "${BASE_URL}/agent"            # 200
curl -sS -o /dev/null -w "%{http_code}\n" "${BASE_URL}/docs.html"        # 302 -> /docs

echo "3) 账号登录与权限链路"
admin_login=$(curl -sS -X POST "${BASE_URL}/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"${ADMIN_USER}\",\"password\":\"${ADMIN_PASS}\"}" | jq -r '.access_token')
if [ -z "$admin_login" ] || [ "$admin_login" = "null" ]; then
  echo "admin 登录失败"; exit 1
fi
echo "admin token: ${admin_login:0:20}..."

agent_login=$(curl -sS -X POST "${BASE_URL}/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"${AGENT_USER}\",\"password\":\"${AGENT_PASS}\"}" | jq -r '.access_token')
if [ -z "$agent_login" ] || [ "$agent_login" = "null" ]; then
  echo "agent 登录失败"; exit 1
fi
echo "agent token: ${agent_login:0:20}..."

curl -sS -H "Authorization: Bearer ${admin_login}" "${BASE_URL}/api/v1/auth/me" | jq -e --arg user "${ADMIN_USER}" '.username == $user'
curl -sS -H "Authorization: Bearer ${agent_login}" "${BASE_URL}/api/v1/auth/me" | jq -e --arg user "${AGENT_USER}" '.username == $user'

curl -sS -H "Authorization: Bearer ${admin_login}" "${BASE_URL}/api/v1/admin/dashboard" | jq
curl -sS -H "Authorization: Bearer ${admin_login}" "${BASE_URL}/api/v1/agent/dashboard" | jq
curl -sS -H "Authorization: Bearer ${agent_login}" "${BASE_URL}/api/v1/agent/dashboard" | jq

# 角色隔离：座席不可访问 admin dashboard
agent_admin_code=$(curl -sS -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer ${agent_login}" \
  "${BASE_URL}/api/v1/admin/dashboard")
if [ "${agent_admin_code}" != "403" ]; then
  echo "角色隔离异常：agent_admin_code=${agent_admin_code}"; exit 1
fi

echo "4) 核心链路 smoke（联系人→模板→活动→启动→查询）"
contact_id=$(curl -sS -X POST "${BASE_URL}/api/v1/contacts" \
  -H "x-api-key: ${API_KEY}" \
  -H "x-tenant-id: ${TENANT_ID}" \
  -H "Content-Type: application/json" \
  -d '{"phone":"13800000000","name":"验收用户","consent_state":"consented"}' | jq -r '.id')
if [ -z "$contact_id" ] || [ "$contact_id" = "null" ]; then
  echo "新建联系人失败"; exit 1
fi

template_id=$(curl -sS -X POST "${BASE_URL}/api/v1/script-templates" \
  -H "x-api-key: ${API_KEY}" \
  -H "x-tenant-id: ${TENANT_ID}" \
  -H "Content-Type: application/json" \
  -d '{"name":"验收话术","category":"sales","content":"您好，{客户姓名}，我是AI外呼助手。","is_active":true}' | jq -r '.id')
if [ -z "$template_id" ] || [ "$template_id" = "null" ]; then
  echo "新建话术模板失败"; exit 1
fi

campaign_id=$(curl -sS -X POST "${BASE_URL}/api/v1/campaigns" \
  -H "x-api-key: ${API_KEY}" \
  -H "x-tenant-id: ${TENANT_ID}" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"验收活动\",\"mode\":\"ai_handoff\",\"script_template_id\":${template_id},\"contact_ids\":[${contact_id}]}" | jq -r '.id')
if [ -z "$campaign_id" ] || [ "$campaign_id" = "null" ]; then
  echo "新建活动失败"; exit 1
fi

curl -sS -X POST "${BASE_URL}/api/v1/campaigns/${campaign_id}/start?max_dials=1" \
  -H "x-api-key: ${API_KEY}" \
  -H "x-tenant-id: ${TENANT_ID}" | jq

curl -sS -G "${BASE_URL}/api/v1/calls" \
  --data-urlencode "page=1" \
  --data-urlencode "size=5" \
  -H "x-api-key: ${API_KEY}" \
  -H "x-tenant-id: ${TENANT_ID}" | jq

echo "5) 限流和告警检查（可选）"
status_code=$(curl -sS -o /dev/null -w "%{http_code}" "${BASE_URL}/healthz")
echo "healthz status=${status_code}"

echo "验收完成：PASS"
```

注意：`jq -e` 或 JSON 结构不同会使命令失败，这说明链路返回格式与预期不一致，需回到对应接口日志排查。

### 6.3 上线前 0-5 标准验收清单（建议直接粘贴到发布记录）

```bash
export BASE_URL="${BASE_URL:-http://localhost:8000}"
export API_KEY="${API_KEY:-dev-api-key}"
export TENANT_ID="${TENANT_ID:-1}"
export ADMIN_USER="${DEMO_ADMIN_USERNAME:-admin}"
export ADMIN_PASS="${DEMO_ADMIN_PASSWORD:-12345678}"
export AGENT_USER="${DEMO_AGENT_USERNAME:-1001@test}"
export AGENT_PASS="${DEMO_AGENT_PASSWORD:-12345678}"

set -euo pipefail

# 0. 控制面可达
curl -fS "${BASE_URL}/health" >/dev/null
curl -fS "${BASE_URL}/readyz" >/dev/null
curl -fS "${BASE_URL}/healthz" >/dev/null
curl -fS "http://localhost:8001/health" >/dev/null

# 1. 页面入口
for u in /admin /agent /docs.html; do
  code=$(curl -sS -o /dev/null -w "%{http_code}" "${BASE_URL}${u}")
  [ "$code" -ge 200 ] && [ "$code" -le 399 ]
done

# 2. 鉴权角色链路
ADMIN_TOKEN=$(curl -sS -X POST "${BASE_URL}/api/v1/auth/login" -H 'Content-Type: application/json' -d "{\"username\":\"${ADMIN_USER}\",\"password\":\"${ADMIN_PASS}\"}" | jq -r '.access_token')
AGENT_TOKEN=$(curl -sS -X POST "${BASE_URL}/api/v1/auth/login" -H 'Content-Type: application/json' -d "{\"username\":\"${AGENT_USER}\",\"password\":\"${AGENT_PASS}\"}" | jq -r '.access_token')
curl -sS -o /dev/null -w "%{http_code}" "${BASE_URL}/api/v1/admin/dashboard" -H "Authorization: Bearer ${ADMIN_TOKEN}"
curl -sS -o /dev/null -w "%{http_code}" "${BASE_URL}/api/v1/agent/dashboard" -H "Authorization: Bearer ${AGENT_TOKEN}"
agent_admin_code=$(curl -sS -o /dev/null -w "%{http_code}" "${BASE_URL}/api/v1/admin/dashboard" -H "Authorization: Bearer ${AGENT_TOKEN}")
[ "$agent_admin_code" = "403" ]

# 3. 核心链路（联系人→模板→活动）
CONTACT_ID=$(curl -sS -X POST "${BASE_URL}/api/v1/contacts" -H "x-api-key:${API_KEY}" -H "x-tenant-id:${TENANT_ID}" -H 'Content-Type: application/json' -d '{"phone":"13800000001","name":"验收联系人","consent_state":"consented","dnc":false}' | jq -r '.id')
TEMPLATE_ID=$(curl -sS -X POST "${BASE_URL}/api/v1/script-templates" -H "x-api-key:${API_KEY}" -H "x-tenant-id:${TENANT_ID}" -H 'Content-Type: application/json' -d '{"name":"上线验收话术","content":"您好，我来协助核对业务问题。","category":"check","is_active":true}' | jq -r '.id')
CAMPAIGN_ID=$(curl -sS -X POST "${BASE_URL}/api/v1/campaigns" -H "x-api-key:${API_KEY}" -H "x-tenant-id:${TENANT_ID}" -H 'Content-Type: application/json' -d "{\"name\":\"上线验收活动\",\"mode\":\"ai_handoff\",\"script_template_id\":${TEMPLATE_ID},\"contact_ids\":[${CONTACT_ID}]}" | jq -r '.id')
START=$(curl -sS -X POST "${BASE_URL}/api/v1/campaigns/${CAMPAIGN_ID}/start?auto_dial=true&async_dial=false&max_dials=1" -H "x-api-key:${API_KEY}" -H "x-tenant-id:${TENANT_ID}" | jq -r '.dispatch_mode')
[ "$START" = "sync" ]

# 4. 事件闭环
CALL_ID=$(curl -sS -G "${BASE_URL}/api/v1/calls" -H "x-api-key:${API_KEY}" -H "x-tenant-id:${TENANT_ID}" --data-urlencode "page=1" --data-urlencode "size=1" | jq -r '.[0].id')
[ -n "$CALL_ID" ] && [ "$CALL_ID" != "null" ]
curl -sS -G "${BASE_URL}/api/v1/calls/${CALL_ID}/events?page=1&size=20" -H "x-api-key:${API_KEY}" -H "x-tenant-id:${TENANT_ID}" >/dev/null
curl -sS -G "${BASE_URL}/api/v1/calls/${CALL_ID}/webhook-stats" -H "x-api-key:${API_KEY}" -H "x-tenant-id:${TENANT_ID}" >/dev/null

# 5. 复测脚本
bash scripts/test-demo-accounts.sh
bash scripts/test-webhook-idempotent.sh
bash scripts/test-campaign-start.sh

echo "上线前验收通过"
```

另外，若你只想快速验证活动启动参数（`async_dial` 与 `max_dials`）：

```bash
bash scripts/test-campaign-start.sh
```

脚本会分别验证：
- `async_dial=false` 的同步发起是否返回 `dispatch_mode=sync`；
- `async_dial=true` 的异步发起是否返回 `dispatch_mode=async` 且 `dispatch_result.status=queued`；
- `max_dials` 是否限制最终会话条目不超出预期。

## 7. 核心模式说明

- **纯人工**：`human_only`（只建立呼叫，不触发 AI）
- **纯 AI**：`ai_only`（全程 AI 交互）
- **AI+转人工**：`ai_handoff`（AI 识别到关键词后转人工）
- **AI+短信**：`ai_with_sms`（会话内可挂起时可触发挂断短信）

### 7.1 API 示例

- 纯 AI 外呼：`"mode":"ai_only"`
- AI+人工：`"mode":"ai_handoff"`
- AI+短信：`"mode":"ai_with_sms"`

## 8. webhook 回调配置（重要）

将你的 PBX/外呼网关回调地址配置为：

- `POST /api/v1/webhooks/telephony/status`
- `POST /api/v1/webhooks/telephony/transcript`
- `POST /api/v1/webhooks/telephony/recording`

回调请求头建议增加：

- `x-webhook-token: <TELEPHONY_WEBHOOK_TOKEN>`

回调负载建议包含 `call_id`（UUID）、`kind`、`payload`。

## 9. 外呼供应商（telephony）对接说明

如果你将 `TELEPHONY_PROVIDER=http`，网关必须支持：

- `POST /v1/call/dial`
- `POST /v1/call/transfer`
- `POST /v1/call/hangup`

请求/返回字段请按 `backend/app/services/telephony.py` 中的 `HttpAdapter` 期望值对齐。

## 10. 升级与部署（生产）

### 10.1 镜像与版本策略

- `control-api`、`ai-agent` 建议使用固定镜像 tag（例如 `v1.x.x`）
- 生产升级禁止只依赖 `create_all`。本版本 PostgreSQL 升级脚本位于：
  `backend/migrations/postgresql/20260828_event_audit_indexes.sql`

发布顺序：

```bash
# 1. 备份（替换连接信息与文件名）
pg_dump --format=custom --file=ai_outbound_before_20260828.dump "$DATABASE_URL"

# 2. 暂停写入流量/活动派发后执行结构升级
psql "$DATABASE_URL" \
  -v ON_ERROR_STOP=1 \
  -f backend/migrations/postgresql/20260828_event_audit_indexes.sql

# 3. 发布固定版本镜像，启动后检查
curl -fS http://localhost:8000/health
curl -fS http://localhost:8000/readyz
```

脚本把 `callevent.event_type` 从固定枚举调整为 `VARCHAR(64)`，并补齐事件类型、通话状态和更新时间索引。必须先备份，并在预发布 PostgreSQL 上演练后再执行生产变更。

### 10.2 安全建议（生产必做）

- 不要把 `.env` 明文放在仓库
- 代理层只放行必需端口
- `TELEPHONY_WEBHOOK_TOKEN` 必须有值
- 日志中打码手机号（如有敏感合规要求）
- 对接短信/电话接口增加重试和幂等保护
- 强制设置 `TRUSTED_HOSTS`，避免 Host 头注入
- `ENV=production` 时服务会拒绝默认密钥、SQLite、空 Redis、`mock` 电话适配器、通配 CORS、演示账号或空 webhook token
- 设置 `CORS_ALLOW_ORIGINS=https://你的管理域名`，不要使用 `*`
- 设置 `DEMO_USERS_ENABLED=false`，并轮换 `SECRET_KEY`、`JWT_SECRET`、`API_KEY`

### 10.3 代码级回归检查

```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate
pip install -e '.[dev]'
pytest -q tests
cd ..
PYTHONPYCACHEPREFIX=/tmp/ai-outbound-pycache python3 -m compileall -q backend/app agent/app
git diff --check
```

以上检查通过只代表代码和开发环境回归通过，不等同于真实 PBX、短信、录音存储、多实例 Redis/PostgreSQL 或生产网络验收通过。

## 11. 常见故障排查

### 11.1 启动失败

- `control-api` 起不来：优先检查 `DATABASE_URL`、`REDIS_URL`、`AI_AGENT_URL`
- `ai-agent` 起不来：检查端口占用及镜像构建成功

### 11.2 不能外呼

- 检查 `TELEPHONY_PROVIDER` 与 `TELEPHONY_PROVIDER_ENDPOINT`
- 检查是否开启并传了 `TELEPHONY_WEBHOOK_TOKEN`
- 检查联系人是否 `dnc`、`consent_state` 是否是 `not_consented/revoked`

### 11.3 回调未生效

- 回调 URL 是否可从公网上访问
- `x-webhook-token` 是否一致
- 回调 payload 中是否带 `call_id`

## 12. 版本记录

- `0.1.0`：控制面+AI 基础能力（含活动、呼叫、webhook、事件查询、重试接口）
