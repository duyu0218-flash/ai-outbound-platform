# AI 外呼平台（可自建部署版本）

本项目提供一个可自定义的 AI 外呼控制平台基础代码，包含：
- 录音转人工（Webhook + 转接事件记录）
- 纯人工外呼
- 纯 AI 外呼
- AI 触发转人工
- AI 结束后发送挂断短信（可扩展）

默认先给你可跑通的最小闭环，适合先做 PoC，后续可以替换为真实
FreeSWITCH/云通信服务和 LLM 提供商。

## 1. 目录说明

- `backend/`: 控制面服务（Campaign/Contact/Call/Webhook）
- `agent/`: AI 会话服务（AI 发言策略、转人工判定）
- `docker-compose.yml`: 一键启动服务
- `.env.example`: 环境变量样例（部署前复制为 `.env`）

## 2. 本地启动

```bash
cp .env.example .env
docker compose up -d --build
```

服务访问：
- 控制面 API：http://localhost:8000/health
- AI 会话 API：http://localhost:8001/health

## 3. 演示 API 用法

以下仅用于本地验证（默认 API Key：`dev-api-key`）：

1. 新建联系人

```bash
curl -X POST http://localhost:8000/api/v1/contacts \
  -H "x-api-key: dev-api-key" \
  -H "Content-Type: application/json" \
  -d '{"phone":"13800000000","name":"示例客户","tags":"demo"}'
```

2. 发起一通纯 AI 外呼

```bash
curl -X POST http://localhost:8000/api/v1/calls \
  -H "x-api-key: dev-api-key" \
  -H "Content-Type: application/json" \
  -d '{"phone":"13800000000","mode":"ai_only","max_attempts":1}'
```

3. 发起一通 AI 转人工外呼

```bash
curl -X POST http://localhost:8000/api/v1/calls \
  -H "x-api-key: dev-api-key" \
  -H "Content-Type: application/json" \
  -d '{"phone":"13800000000","mode":"ai_handoff","max_attempts":1}'
```

4. 人工挂起某通话

```bash
curl -X POST "http://localhost:8000/api/v1/calls/<call_id>/handover?reason=客户要求" \
  -H "x-api-key: dev-api-key"
```

## 4. 与真实 PBX/网关对接

代码里已预留 `backend/app/services/telephony.py` 的适配器接口。

- `telephony_provider=mock`：本地演示模式
- `telephony_provider=freeswitch`：改为你的 FreeSWITCH/网关 API 地址（`SIP_PROVIDER_ENDPOINT`）

你只需要在 `get_adapter` 和 `FreeSwitchAdapter` 中改接入你们的网关 API 即可。

## 5. 与 AI 引擎替换

默认 AI 判定在 `agent/app/policy.py` 中为规则策略，后续你可接：
- OpenAI / Qwen / Ollama / 你自己的模型服务
- 挂起关键词与话术配置
- 会话历史与质检标签

## 6. 部署到你的 GitHub 账号

在本地已完成项目骨架后，可以执行：

```bash
git init
git add .
git commit -m "init: ai outbound platform scaffold"
git remote add origin git@github.com:<你的用户名>/<仓库名>.git
git branch -M main
git push -u origin main
```

如果你把仓库创建和鉴权交给我，我下一步可按你账号直接帮你继续补：
- GitHub Actions（CI/CD）
- 真实通信网关适配（FreeSWITCH/AWS Connect/Twilio）
- CRM/工单系统对接
