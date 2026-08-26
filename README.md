# AI 外呼平台（可商用基础版）

本项目面向“国内先行、后续海外扩展”场景，提供可二次开发的外呼平台骨架，已包含：

- 通话会话与状态管理（录音转人工、纯人工、纯 AI、AI+短信）
- 联系人合规模块（DNC、用户同意/拒绝）
- 租户隔离与 API Key 鉴权
- Webhook 回调链路（状态 / 识别 / 录音）
- 挂断短信记录（`SmsLog`）
- 通话事件追溯（`CallEvent`）
- 失败/无应答重试（`/api/v1/calls/{call_id}/retry`）
- 话术模板（`/api/v1/script-templates`）与活动绑定

当前版本是“可上线前评估”状态，不依赖第三方前端，先从 API 与服务能力落地。
## 2bis. 测试账号体系（新）

- 管理端测试地址：[http://localhost:8000/admin](http://localhost:8000/admin)  
  默认账号：`admin` / `12345678`
- 座席端测试地址：[http://localhost:8000/agent](http://localhost:8000/agent)  
  默认账号：`1001@test` / `12345678`
- 文档页：[http://localhost:8000/docs.html](http://localhost:8000/docs.html)（指向 `/docs`）

> 登录成功会返回 `access_token`（Bearer）。用于后续用户态接口验证：
`Authorization: Bearer <access_token>`

- 管理端鉴权示例：`GET /api/v1/admin/dashboard`（仅 admin 可访问）
- 座席端鉴权示例：`GET /api/v1/agent/dashboard`（admin/agent 均可访问）

## 1. 目录

- `backend/`: 控制面服务（联系人、活动、外呼、webhook）
- `agent/`: AI 话术策略服务（可替换成真实 LLM）
- `docker-compose.yml`: 本地联调与演示启动清单
- `.env.example`: 环境变量样例

## 2. 快速启动

完整安装与生产化部署请先看： [INSTALL.md](INSTALL.md)

```bash
cp .env.example .env
docker compose up -d --build
```

- 控制面：http://localhost:8000/health
- AI 服务：http://localhost:8001/health

## 3. 环境变量

| 变量 | 说明 |
|---|---|
| `APP_NAME` | 服务名 |
| `ENV` | 环境名 |
| `API_KEY` | 管理 API 鉴权头 `x-api-key`（主 key） |
| `UI_API_KEY` | 可选备用 API key |
| `DATABASE_URL` | 数据库链接（建议 PostgreSQL） |
| `REDIS_URL` | Redis 链接 |
| `DEFAULT_TENANT_ID` | 默认租户 ID |
| `TELEPHONY_PROVIDER` | `mock` 或 `http` |
| `TELEPHONY_PROVIDER_ENDPOINT` | `http` 模式下电信/网关 API 地址 |
| `TELEPHONY_WEBHOOK_BASE` | 回调基础地址（控制面地址） |
| `TELEPHONY_WEBHOOK_TOKEN` | 回调鉴权 Token（可选，设置后会校验 `x-webhook-token`） |
| `AI_AGENT_URL` | AI 服务地址 |
| `SMS_PROVIDER_ENDPOINT` | 短信服务 API 地址 |
| `SMS_API_KEY` | 短信服务鉴权 |
| `REQUEST_TIMEOUT_MS` | 单请求超时（毫秒），用于后端防止长耗时请求 |
| `REQUEST_ID_HEADER` | 请求透传 ID 头，默认为 `X-Request-ID` |
| `TRUSTED_HOSTS` | 启用 `TrustedHostMiddleware` 的白名单，`,` 分隔；空值不启用 |
| `RATE_LIMIT_ENABLED` | 是否启用 API 限流 |
| `RATE_LIMIT_DEFAULT_RPM` | 默认每分钟请求数 |
| `RATE_LIMIT_AUTH_RPM` | `/api/v1/auth/login` 每分钟请求数（更严格） |
| `RATE_LIMIT_WINDOW_SEC` | 限流滑动窗口秒数 |

## 4. 示例 API

```bash
# 新建联系人
curl -X POST http://localhost:8000/api/v1/contacts \
  -H "x-api-key: dev-api-key" \
  -H "x-tenant-id: 1" \
  -H "Content-Type: application/json" \
  -d '{"phone":"13800000000","name":"示例客户","tags":"demo","consent_state":"consented"}'

# 新建活动并绑定联系人
curl -X POST http://localhost:8000/api/v1/campaigns \
  -H "x-api-key: dev-api-key" \
  -H "x-tenant-id: 1" \
  -H "Content-Type: application/json" \
  -d '{"name":"测试活动","script":"常规话术","mode":"ai_handoff","contact_ids":[1]}'

# 新建话术模板
curl -X POST http://localhost:8000/api/v1/script-templates \
  -H "x-api-key: dev-api-key" \
  -H "x-tenant-id: 1" \
  -H "Content-Type: application/json" \
  -d '{"name":"通用销售话术","content":"您好，{客户姓名}，我是AI外呼助手，先确认下您的信息","category":"sales","description":"演示话术"}'

# 话术模板列表（只看生效模板）
curl -X GET "http://localhost:8000/api/v1/script-templates?active_only=true&page=1&size=20" \
  -H "x-api-key: dev-api-key" -H "x-tenant-id: 1"

# 用模板创建活动（不重复填 script）
curl -X POST http://localhost:8000/api/v1/campaigns \
  -H "x-api-key: dev-api-key" \
  -H "x-tenant-id: 1" \
  -H "Content-Type: application/json" \
  -d '{"name":"测试活动-模板","script_template_id":1,"mode":"ai_handoff","contact_ids":[1]}'

# 发起纯 AI 外呼
curl -X POST http://localhost:8000/api/v1/calls \
  -H "x-api-key: dev-api-key" \
  -H "x-tenant-id: 1" \
  -H "Content-Type: application/json" \
  -d '{"phone":"13800000000","mode":"ai_only","max_attempts":1}'

# 启动活动（默认 auto_dial=true）
curl -X POST "http://localhost:8000/api/v1/campaigns/1/start?max_dials=100" \
  -H "x-api-key: dev-api-key" \
  -H "x-tenant-id: 1"

# 手工挂断 / 转人工
curl -X POST "http://localhost:8000/api/v1/calls/<call_id>/handover?reason=客户要求" \
  -H "x-api-key: dev-api-key" -H "x-tenant-id: 1"
curl -X POST "http://localhost:8000/api/v1/calls/<call_id>/hangup?reason=系统清场" \
  -H "x-api-key: dev-api-key" -H "x-tenant-id: 1"

# 查询通话事件
curl -X GET "http://localhost:8000/api/v1/calls/<call_id>/events?page=1&size=20" \
  -H "x-api-key: dev-api-key" -H "x-tenant-id: 1"

# 重试失败的外呼（达到最大尝试数后会拒绝）
curl -X POST "http://localhost:8000/api/v1/calls/<call_id>/retry" \
  -H "x-api-key: dev-api-key" -H "x-tenant-id: 1"

# 仪表台接口（配合 /admin /agent 页面）
curl -X GET http://localhost:8000/api/v1/admin/dashboard \
  -H "Authorization: Bearer <admin_access_token>"
curl -X GET http://localhost:8000/api/v1/agent/dashboard \
  -H "Authorization: Bearer <agent_access_token>"
```

## 5. 关键生产要点

- Webhook 安全：
  - 建议给网关回调加 `x-webhook-token`，并设置 `TELEPHONY_WEBHOOK_TOKEN`。
- 生产化增强：
  - 接口健壮性：全量返回值增加 `request_id` 便于链路追踪。
  - 统一超时：`REQUEST_TIMEOUT_MS` 防止挂死请求。
  - 统一身份追踪：`Request-ID` 透传与回写。
  - 限流防护：`RATE_LIMIT_*` 限制 API 异常流量，`/api/v1/auth/login` 有独立配额。
  - 安全头：默认注入常见 HTTP 安全头（禁用内嵌、MIME 保护、缓存控制等）。
  - 健康探针：`/health` 校验控制面数据库/Redis 可达（不阻塞核心外部依赖）；`/readyz` 检查数据库、Redis、AI 服务与 PBX 就绪状态，用于编排。
  - 输入与异常统一处理：请求参数错误返回 400，HTTP 异常保留原始状态码。
  - 信任主机：可通过 `TRUSTED_HOSTS` 固定可访问域名。
- 活动拨号：
  - `POST /api/v1/campaigns/{campaign_id}/start` 支持 `auto_dial` 与 `max_dials`。
  - 建议后续接入分布式任务队列，避免活动一次性同步阻塞。
- 外呼闭环：
  - 建议监控 `answered / failed / no_answer / voicemail / waiting_human / completed`。
- 合规：
  - 联系人必须经过同意/撤回、黑名单（DNC）检查。

## 6. 接入 PBX / 短信

- `backend/app/services/telephony.py`:
  - `mock`：联调演练；
  - `http`：按你的网关实现 `/v1/call/dial`、`/v1/call/transfer`、`/v1/call/hangup`。
- 回调地址固定为：
  - `POST /api/v1/webhooks/telephony/status`
  - `POST /api/v1/webhooks/telephony/transcript`
  - `POST /api/v1/webhooks/telephony/recording`
- `agent/app/policy.py` 先作为规则引擎占位，建议替换为真实 LLM policy。

## 7. 下一个可交付版本（建议）

- CI/CD（GitHub Actions）：镜像构建、单元测试、配置扫描
- 分布式任务队列（Celery/Temporal）：任务并发、限流、重试、死信队列
- 操作日志与审计、工单系统/CRM 双向同步
- 海外扩展：时区、隐私条款、国际电销规则与时段管控
