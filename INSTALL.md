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
- `.github/workflows/ci.yml`：CI 静态编译检查

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
```

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

- `GET /api/v1/admin/dashboard` 与 `GET /api/v1/agent/dashboard`
- 说明：管理员账户可访问两类控制台，座席仅可访问座席控制台
- 角色隔离（agent 不能访问 admin 接口）

如需做一次完整 API 流程 smoke（联系人→模板→活动→启动→通话→事件），再执行：

```bash
bash scripts/smoke-outbound-api.sh
```
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
- 变更 SQL 模型后，按你的数据库管理方式进行变更（如 Alembic）

### 10.2 安全建议（生产必做）

- 不要把 `.env` 明文放在仓库
- 代理层只放行必需端口
- `TELEPHONY_WEBHOOK_TOKEN` 必须有值
- 日志中打码手机号（如有敏感合规要求）
- 对接短信/电话接口增加重试和幂等保护

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
