# Java 辅助服务迁移

统一版本：**JDK 17 / Spring Boot 4.1.1**，与 `backend-java` 核心保持一致。

本目录正在接替 `agent`、`recording_adapter`、`voice_gateway` 及其媒体工作器。四个角色使用同一个 Java 制品，通过 `JAVA_SERVICE_ROLE=agent|recording|voice|media` 分进程运行。Java 不通过调用旧 Python 服务实现业务。前端继续使用 React；FreeSWITCH、PostgreSQL、Redis 和对象存储属于独立基础设施，不属于业务语言改写。

**迁移验收进行中，尚未正式替换部署。** 旧 Python 代码暂作行为基准与回退参考；不能把已写入 Java 源码、模拟协议测试或注册路由当成全部功能验收完成。

## 范围与入口

| 角色 | 入口/状态 | 主要检查 |
| --- | --- | --- |
| Agent | `/health`、`/readyz`、`/agent/start`、`/agent/turn` | 中英文、否定转人工、外部模型授权、历史截断、脱敏、共享额度、追踪脱敏 |
| 录音 | `/health`、`/readyz`、`/metrics`、`/v1/recordings/ingest`、`/v1/recordings/delete` | 来源允许列表、S3 签名、流式大小限制、并发、删除标记、重启恢复、校验后源清理 |
| 网关 | `/v1/call/*`、`/v1/admin/*`、签名录音下载及清理、内部媒体事件/额度 | 签名防重放、租户及线路、预算、幂等、真实 ESL 协议、回调顺序和 ACK、受限转人工 |
| 媒体工作器 | `/internal/state`、`/internal/command`、`/v1/pipecat/media/{token}` | epoch/session/attempt 围栏、播报幂等、ASR、PCM、打断、模块实际播放事件、关闭证据 |

旧 `PIPECAT_*` 和 `VOICE_AI_PIPELINE=pipecat|hybrid` 配置名用于兼容调用方，Java 实际运行时是原生 Java PCM 管道，不运行 Python Pipecat，也不声称具有 Python SDK 版本。`pipecat_version` 返回空值。OpenAI ASR 默认模型沿用旧依赖的 `gpt-realtime-whisper`，可显式指定 `JAVA_OPENAI_STT_MODEL`，不自动更换模型。原生 OpenAI 路径的本地 VAD 参数需要用实际线路音频校准。

## 构建及开发验证

```sh
export JAVA_HOME=/path/to/jdk17
bash services-java/scripts/build-java17.sh clean verify
python services-java/scripts/stack_smoke.py
python services-java/scripts/media_process_smoke.py
python services-java/scripts/verify_routes.py
python services-java/scripts/verify_compose.py
```

后一个命令需要开发用 Python/httpx 作为测试驱动，以及已构建的核心和辅助服务 jar。它启动的业务服务全部是 Java，使用临时 SQLite、loopback 和模拟电话驱动，不连接业务数据库、真实线路或真实模型。测试报告保存在 `validation/`；普通 `target/` 编译产物不提交。

## 数据与切换约束

- Agent 共享额度沿用 Python SQLite `budgets/usage` 表和配额配置摘要；提交前竞争可有限重试，提交结果不确定时不重放额度预留。
- 录音沿用 `.lifecycle` 文件名、对象键、删除标记和上传回执；网关沿用 SQLite 账本、回调队列及媒体归属记录。切换前须停止旧 Python 控制器/工作器/录音适配进程、备份并迁移验证。Java 文件锁不能代替停止旧 Python 进程；不同语言的锁 API 不保证互斥。
- 未完成的拨号、媒体归属、未知语音额度不得按 TTL 清空。切换前需排空或凭供应商证据对账处理。
- 核心生产配置仍要求 Redis TLS、明确 CORS、合法凭据引用及独立发布验收。不能只开启 `JAVA_RELEASE_ACCEPTED` 来代替验收。

## 全 Java 配置入口

```sh
docker compose -f docker-compose.yml -f docker-compose.java.yml -f docker-compose.java-services.yml config
```

三个文件依次组合，将四个业务服务的构建、命令及健康检查切到 Java，旧 Python 核心任务进程不在默认启动集合内。这里只给出配置解析命令，正式启动须先完成离线切换条件。默认基础方案为桥接网络、网关进程内媒体；多媒体工作器采用同机 loopback HTTP/WS，需要独立配置 `JAVA_SERVICE_ROLE=media`、`MEDIA_WORKER_ID`、端口及共享 `MEDIA_RPC_TOKEN`。不能把 loopback 工作器地址直接用于互不共享网络命名空间的桥接容器。

录音源目录 `/recordings` 是可写命名卷；实际 FreeSWITCH 必须共享同一个节点目录/卷及匹配的写权限。不要直接复用旧 compact 的只读录音挂载。模型额度、网关账本、录音生命周期都必须保留在已验证的持久卷内；新建空卷不能代替旧状态迁移。

`PIPECAT_FALLBACK_TO_LEGACY=true` 只在已明确配置旧媒体启动命令、且旧模块和原生会话清理被确认后回退；清理结果未知会停止该次启动。该旧媒体命令属于部署方批准的 PBX 能力，必须单独确认其不再依赖 Python。ASR 连接期限沿用 `ALIYUN_NLS_CONNECT_TIMEOUT_SEC`。`LLM_MAX_KEEPALIVE_CONNECTIONS` 在 Agent 启动时映射到 JDK HTTP/1 空闲连接池，HTTP/2 复用由 JDK 管理。

## 验证与发布边界

已建立 32 个原 Python 显式 HTTP/WS 入口和 158 个配置字段的静态对照；这不等于所有入口状态都已实测。本地已覆盖策略对照、旧账本升级/回滚、签名、防重放、额度、HTTP 边界、真实 S3、独立 Java 媒体进程崩溃重启、浏览器关键链路和四角色 Linux 容器。具体数量、制品及已知限制见 [ACCEPTANCE.md](ACCEPTANCE.md)。

真实旧库/账本、外部 S3/TLS、云 ASR/TTS/模型、真实 SIP/RTP/WebRTC、500 总在途、持续负载及完整控制器崩溃恢复仍需单独验收。独立 Codex Security 差异扫描被宿主 Git 版本与清单工具参数不兼容阻断，保留部分草稿；不能以已通过的 OSV/FindSecBugs 门禁代称完整差异扫描通过。测试环境、真机与生产发布尚未完成。

协议实现参考：[OpenAI Realtime transcription](https://developers.openai.com/api/docs/guides/realtime-transcription)、[Langfuse OTLP](https://langfuse.com/integrations/native/opentelemetry)。文档可证明协议约定，不能证明真实账户或目标环境已联调通过。
