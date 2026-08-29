# AI 外呼平台安装与部署手册

本文档面向你们当前仓库，给出从开发环境到生产环境的完整可执行流程。按该文档可完成：

- 基础服务部署（PostgreSQL/Redis）
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

- `API_KEY`：默认租户的服务端 API Key；多租户集成请改用 `TENANT_API_KEYS_JSON={"1":"...","2":"..."}`
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
  - `DEFAULT_CALL_TIMEOUT_SEC=120`（无终态回调时释放并发）
  - `SCHEDULER_ENABLED=true`（异步活动必须开启）

### 3.2 默认测试账号体系（推荐先验收）

服务启动会创建两类默认测试账号：

- 管理员：`admin / 12345678`（角色：`admin`）
- 座席：`1001@test / 12345678`（角色：`agent`）

访问方式：

- 管理端地址：[http://localhost:8000/admin](http://localhost:8000/admin)
- 座席端地址：[http://localhost:8000/agent](http://localhost:8000/agent)
- 文档地址：[http://localhost:8000/docs.html](http://localhost:8000/docs.html)

### 3.3 中英文界面切换

- 管理端和座席端右上角均有 `中文 / English` 选择器。
- 语言切换即时生效，无需重新登录。
- 语言偏好保存在浏览器本地，并在 `/admin` 与 `/agent` 之间共享。
- 语言偏好只保存 `zh-CN` 或 `en-US`，不会保存密码、Token 或 API Key。

### 3.4 前端页面结构

- 管理端运营模块：`/admin` 仪表盘、`/admin/contacts` 客户管理、`/admin/scripts` 话术管理、`/admin/campaigns` 外呼任务、`/admin/calls` 通话记录。
- 管理端系统模块：`/admin/users` 用户与座席、`/admin/lines` 外呼线路、`/admin/settings` 系统配置、`/admin/system` 监控与审计。
- 座席端：`/agent` 座席工作台、`/agent/calls` 通话记录。
- 登录入口：`/admin/login` 与 `/agent/login`。
- Docker 构建会自动安装并编译 `frontend`；本地直接运行后端前，请先在 `frontend` 执行 `pnpm install && pnpm build`。

直接登录 API：

```bash
curl -X POST http://localhost:8000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"12345678"}'
```

生产环境先使用预置管理员登录 `/admin/users` 创建正式账号、完成权限与密码验收，再设置 `DEMO_USERS_ENABLED=false` 并重启。演示密码不得带入生产。系统禁止管理员停用或降级自己，并保证每个租户至少保留一个启用的管理员。

管理页面不会保存短信、线路或大模型密钥。生产密钥必须通过环境变量或独立密钥管理服务注入，避免在浏览器和审计日志中泄露。

### 3.5 从管理端调整并发容量

1. 使用管理员账号进入 `/admin/settings`，选择“并发容量”。
2. 设置“租户最大同时通话数”并保存。该值保存到数据库，新的拨号抢占和调度批次会立即读取，无需重启。
3. 进入 `/admin/lines` 确认启用线路的并发值；进入 `/admin/campaigns` 配置活动并发。
4. 进入 `/admin/system` 查看“已配置容量、实际生效并发、当前活跃通话、可用槽位”。

实际生效值为租户容量、活动并发和启用线路并发中的最小值。`.env` 中的 `MAX_CONCURRENT_CALLS` 只是该租户尚未保存容量配置时的默认值。提高页面配置不会自动购买运营商、PBX、ASR 或 TTS 并发额度，正式提高前必须确认外部容量。

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
- `POST /v1/call/speak`
- `POST /v1/call/transfer`
- `POST /v1/call/hangup`

请求/返回字段请按 `backend/app/services/telephony.py` 中的 `HttpAdapter` 期望值对齐。`dial` 会携带 `caller_id` 和媒体参数；`speak` 接收 `text`、`language`、`voice`、`provider`，网关负责完成实际 TTS 或播放。

多租户部署可设置 `TELEPHONY_PROVIDER=tenant`。控制服务会对所有启用线路汇总并发容量，再按优先级、权重和当前占用率选路；选中后把 `telephony_line_id` 绑定到通话，后续播放、转接和挂断使用同一条线路。`provider=mock` 仅用于验收，其余线路的 `gateway` 必须是 `http://` 或 `https://` 语音桥接地址。直接填写 SIP URI 不会自动完成注册、媒体协商或 WebRTC 坐席接听；这些能力必须由 FreeSWITCH、Asterisk 或运营商平台承载并通过 HTTP 适配接口接入。

## 10. 升级与部署（生产）

### 10.1 镜像与版本策略

- `control-api`、`ai-agent` 建议使用固定镜像 tag（例如 `v1.x.x`）
- 生产升级禁止只依赖 `create_all`。本版本 PostgreSQL 升级脚本位于：
  - `backend/migrations/postgresql/20260828_event_audit_indexes.sql`
  - `backend/migrations/postgresql/20260828_admin_management.sql`
  - `backend/migrations/postgresql/20260828_contact_integrity.sql`
  - `backend/migrations/postgresql/20260828_call_retry_schedule.sql`
  - `backend/migrations/postgresql/20260829_runtime_configuration_linkage.sql`

发布顺序：

```bash
# 1. 备份（替换连接信息与文件名）
pg_dump --format=custom --file=ai_outbound_before_20260828.dump "$DATABASE_URL"

# 2. 暂停写入流量/活动派发后执行结构升级
psql "$DATABASE_URL" \
  -v ON_ERROR_STOP=1 \
  -f backend/migrations/postgresql/20260828_event_audit_indexes.sql

psql "$DATABASE_URL" \
  -v ON_ERROR_STOP=1 \
  -f backend/migrations/postgresql/20260828_admin_management.sql

# 执行前必须先处理同租户重复号码；发现重复时脚本会主动终止，不会静默删数据
psql "$DATABASE_URL" \
  -v ON_ERROR_STOP=1 \
  -f backend/migrations/postgresql/20260828_contact_integrity.sql

psql "$DATABASE_URL" \
  -v ON_ERROR_STOP=1 \
  -f backend/migrations/postgresql/20260828_call_retry_schedule.sql

psql "$DATABASE_URL" \
  -v ON_ERROR_STOP=1 \
  -f backend/migrations/postgresql/20260829_runtime_configuration_linkage.sql

# 3. 发布固定版本镜像，启动后检查
curl -fS http://localhost:8000/health
curl -fS http://localhost:8000/readyz
```

最新脚本还会补齐坐席归属、通话线路绑定、线路优先级/权重/凭证引用字段及索引。应用启动时也会执行同等的幂等加列迁移，但生产环境仍建议先在维护窗口显式执行 SQL。必须先备份，并在预发布 PostgreSQL 上演练后再执行生产变更。

### 10.2 安全建议（生产必做）

- 不要把 `.env` 明文放在仓库
- 代理层只放行必需端口
- `TELEPHONY_WEBHOOK_TOKEN` 必须有值
- 日志中打码手机号（如有敏感合规要求）
- 对接短信/电话接口增加重试和幂等保护
- 强制设置 `TRUSTED_HOSTS`，避免 Host 头注入
- `ENV=production` 时服务会拒绝默认密钥、SQLite、空 Redis、`mock` 电话适配器、通配 CORS、演示账号或空 webhook token
- 设置 `CORS_ALLOW_ORIGINS=https://你的管理域名`，不要使用 `*`
- 设置 `DEMO_USERS_ENABLED=false`，并使用至少 32 字符的非占位符 `SECRET_KEY` / `JWT_SECRET`；多租户服务调用配置 `TENANT_API_KEYS_JSON`

### 10.3 代码级回归检查

```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate
pip install -e '.[dev]'
pytest -q tests
cd ..

cd agent
python3 -m venv .venv
source .venv/bin/activate
pip install -e '.[dev]'
pytest -q tests
cd ..

cd frontend
corepack enable
pnpm install --frozen-lockfile
pnpm build
cd ..

PYTHONPYCACHEPREFIX=/tmp/ai-outbound-pycache python3 -m compileall -q backend/app agent/app
git diff --check
```

GitHub Actions 会分别执行控制服务和 AI 服务测试，并在控制服务任务中重新构建、校验前端产物。合并前应确认两个矩阵任务均为绿色。

以上检查通过只代表代码和开发环境回归通过，不等同于真实 PBX、短信、录音存储、多实例 Redis/PostgreSQL 或生产网络验收通过。

### 10.4 本版本功能边界

- 活动：支持启动、暂停、恢复、停止和删除。停止只终止未派发任务，已经提交给运营商的通话要逐通挂断。
- 录音与短信：活动开关已在服务端强制执行；系统保存录音回调 URL 和短信日志，但真实对象存储与短信送达取决于外部服务。
- 短信重试：管理员可在 `/admin/system` 查看并重试失败/禁用状态的日志；成功记录不能重复发送。
- 双语：管理端、座席端和规则型 AI 回复支持中文/英文。真实 ASR/LLM/TTS 的双语效果需要接入后另行验收。
- 坐席工作台：具备通话列表、状态操作和转人工请求，不包含浏览器 WebRTC 媒体通话、耳麦设备检测、自动排队抢单和通话保持。
- 生产部署：`scripts/bootstrap.sh` 不再覆盖已有 `.env`；控制 API worker 可通过 `CONTROL_API_WORKERS` 配置。多 worker 调度已使用 Redis 主锁，Redis 不可用时生产调度器不会降级为无锁执行；仍必须执行实际 PBX/ASR/TTS 容量压测。

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
