# AI 对话观测：Langfuse

Langfuse 用于定位每轮回复慢在哪里、调用了哪个模型、消耗了多少 Token，以及失败发生在哪一轮。它不替代 Prometheus/Grafana 的呼叫容量、数据库、队列和线路监控。

## 已接入的入口与状态

| 入口 | 观测内容 | 需验证的状态 |
| --- | --- | --- |
| `POST /agent/start` | `agent.start` span | 开场成功、鉴权失败 |
| `POST /agent/turn` | `agent.turn` span、最终 speak/handoff 动作 | 规则回复、转人工、租户未授权外部模型、异常、取消 |
| OpenAI-compatible 调用 | 父 turn 下的 `llm.reply` generation | 模型、参数、耗时、供应商返回的 Token、请求/解析错误 |
| `GET /readyz` | 受服务 Token 保护的 observability 状态 | 禁用、初始化成功、初始化失败；不声称服务连通 |
| 服务生命周期 | 后台批量导出、退出时 shutdown | SDK 初始化故障、观测写入故障、关闭、启动取消 |

每次请求创建独立 trace，以 HMAC 后的 call_id 作为 session_id 关联同一通电话的多轮请求。密钥轮换会改变该关联值。并发请求使用 ContextVar 隔离父子关系。记录的 generation 耗时包含本地配额等待和响应校验，不应解释成供应商纯推理耗时。

当前不采集手机号、原始 call_id、话术、转写、知识库正文、模型回复、音频和异常消息。错误只记录异常类名。Langfuse 项目密钥只存在于 agent 服务端环境中，不出现在前端及 readyz。项目管理员可以查看全部采样 trace；本次没有建设面向租户的 Langfuse 查询页面或租户权限映射。

## 启用

1. 部署并初始化你管理的 Langfuse 实例，创建项目及 API keys。也可使用已批准的云端项目。实例安装参考 [官方自托管文档](https://langfuse.com/self-hosting)。本仓库不自动启动 Langfuse 及其数据库，也不默认发送到公共云端。
2. 在 agent 使用的环境文件中填写以下变量；不得将真实密钥提交 Git。

```dotenv
LANGFUSE_ENABLED=true
LANGFUSE_BASE_URL=https://langfuse.example.com
LANGFUSE_PUBLIC_KEY=pk-lf-your-project
LANGFUSE_SECRET_KEY=sk-lf-your-project
LANGFUSE_SAMPLE_RATE=0.1
```

3. 重建并重启目标环境的 agent，使新增依赖和配置生效。主 Compose 的 ai-agent 已从 `APP_ENV_FILE` 加载环境；其他独立启动的 agent 进程也必须加载上述配置。生产环境强制 HTTPS。禁用时不导入或初始化 SDK、不建立导出客户端。
4. 用合成客户资料执行开场、规则回复、模型回复、转人工和失败场景，在 Langfuse 中检查 session、trace、generation、模型、Token、错误及导出字段。验收时可临时设置采样为 `1`；线上先低比例灰度。

SDK 固定为 `langfuse==4.15.6`，使用 [官方 Observation API](https://langfuse.com/docs/observability/sdk/instrumentation)。后台批处理每批 64 条、最多等待 5 秒，HTTP 超时 2 秒；导出使用 SDK 有界队列，拥塞时可能丢弃观测。业务请求内不执行 flush 或连通性请求。服务关闭时才等待 SDK 排空。采样为 0 时不导出 trace，采样为 1 时覆盖全部请求。

初始化或观测写入异常不阻断业务；配置格式错误则在启动时明确失败。`/readyz` 的 `initialized=true` 只代表客户端已创建，`connectivity_verified=false` 始终提醒操作者仍需真实接收验收。远端不可用时导出失败不计入业务成功判定。不要把采样 trace 数当成完整呼叫计数。

只有供应商真实返回的非负整数 Token 被记录，不推算缺失用量。Langfuse 价格匹配得到的成本用于分析，不作为客户账单；自定义模型需要在 Langfuse 配置正确价格。本次不修改计费、生产话术或自动转人工策略，不启用远程 Prompt 覆盖或 LLM 自动裁判。

## 验证与上线条件

测试命令：在 agent 目录运行 `python -m pytest -q tests`。新增回归使用真实 SDK 与内存 exporter 核验导出字段；模型响应是合成响应，不是真实供应商通话。HTTP collector 测试只验证 OTLP 接收协议，不替代 Langfuse 实例持久化和 UI 验收。

正式上线前必须完成：目标 Langfuse 实例认证、持久化及 UI 验收；核查采集内容与访问权限；在实际部署配置上对比启用前后的吞吐、P95/P99、资源和队列丢弃；验证远端断网、密钥错误和恢复；真实线路和模型端到端验收；CI 全绿和发布审批。关闭 `LANGFUSE_ENABLED` 并重启 agent 可回退观测接入，不改变业务数据库。

本项目没有门店、房型、购物车、下单、支付、订单或“我的”电商页面，不能声明通过该验收链路。对应业务链路为登录 → 名单/话术/知识库 → 任务 → 呼叫/AI 回复或转人工 → 通话记录/报告 → 退出；验证结果单独记录于本次验收报告。
