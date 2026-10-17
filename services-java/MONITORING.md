# Java 监控口径

网关和录音 `/metrics` 继续要求 `METRICS_TOKEN` 或其文件引用。Agent readiness 使用服务令牌，媒体内部状态使用独立 `MEDIA_RPC_TOKEN`。监控不得采集完整转写、电话号码、签名链接或令牌作为标签。

| 指标/状态 | Java 含义 |
| --- | --- |
| `ai_outbound_voice_gateway_ready/draining/calls` | 实际就绪、排空标记及当前网关绑定数 |
| `ai_outbound_voice_security_*` | 持久账本停止标记、活跃 attempt、拒绝数、待发回调数/最老年龄；沿用旧名称 |
| `ai_outbound_voice_esl_queue_depth/oldest_age_sec/overloaded/errors_total` | Java 实际事件排队数、未完成事件最老年龄、过载锁存、处理异常；不等同 Python 事件循环延迟 |
| `ai_outbound_voice_callback_http_inflight` | 当前实际 HTTP 回调请求数 |
| `ai_outbound_voice_ledger_commits_total` | 本进程成功根事务提交数；嵌套事务只计一次，回滚不计；不等同旧 Python writer 批次数 |
| `ai_outbound_pipecat_sessions/session_capacity` | 兼容名称；实际运行的是原生 Java 媒体 |
| `ai_outbound_voice_pcm_input_frames_total/pcm_arrival_gap_max_seconds` | 成功接收 PCM 帧数与本进程观察到的最大到达间隔 |
| `ai_outbound_voice_tts_first_audio_count/tts_first_audio_seconds_sum/tts_first_audio_max_seconds` | 实际 TTS 首次成功 PCM 发送；计时包含 Java 工作队列等待 |
| `ai_outbound_java_heap_used_bytes/heap_max_bytes/threads/uptime_seconds` | 当前 JVM ManagementFactory 观测 |
| `ai_outbound_java_gc_collections_total/seconds_total` | JVM 累计 GC 次数和耗时 |
| `ai_outbound_recording_adapter_ingest_total/delete_total` | 已发生结果计数，沿用受限固定 result 标签；无事件时不伪造事件样本 |

媒体 `/internal/state.metrics` 返回本工作器真实计数。控制器只聚合健康且未过期的工作器：数量和耗时求和，最大值取 max。未获取到的指标省略，不写假零；工作器重启或健康集合变化会导致聚合值下降，不能当跨 epoch 单调全局计数。请结合 readiness/抓取状态告警，不用缺失样本推断业务为零。

旧 Python writer_queue、writer_batch、executor/event_loop 指标停止提供；原有自定义告警必须按上表迁移，不能用固定零兼容。仓库现有商业面板/告警使用的 ready、calls、sessions、安全账本和录音指标继续保留。Java API 进程的完整监控继承核心实现。

Java worker 健康探针统一为 `java -jar /app/control.jar --healthcheck`，各进程有独立 loopback PORT。准入信号 `ai:java-<随机实例ID>` 写入既有 Redis stability signals；`at` 绑定实际 AI 通道进展时间，队列读取期间的耗时也计入年龄，慢数据库不会刷新旧心跳；`queue_p95_ms` 取最近 30 秒最多 256 次实际调度等待，`oldest_wait_ms` 取数据库已到期 AI 任务最老等待。未启动、停止、数据库读取失败和通道进展过期不产生新健康心跳；Redis 故障保持准入失败关闭。该信号用于保护，不能代替吞吐或 500 路资格证明。
