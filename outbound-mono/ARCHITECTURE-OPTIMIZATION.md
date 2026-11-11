# 架构优化实施与验收记录

2026 年 11 月 2 日。方案依据为《AI外呼平台技术架构评估与演进方案 V2》，起始主干 f8de1d6。本记录按方案项目跟踪，完成情况以代码、测试和实际环境证据为准。

2026 年 11 月 5 日新增的运行保护修复另见 [对应方案与验收](RUNTIME-PROTECTION.md)，下文保留 11 月 2 日批次的范围与证据。

## 本批实现

- V36 为同租户同号码建立跨活动、跨渠道的数据库准入保护。锁保留至调用者事务完成，旅程节点仍与发送、归因一并提交。
- 发送前从解密后的号码重算身份，拒绝不一致的哈希。相同短信或外呼目标重试可返回原目标，不再因为已达到频次上限而错误拒绝。
- 发送路径读取已提交的最新触达事实，覆盖调用者已经建立旧 RR 快照的情况。联系人定位和状态复核也使用当前读。预览和旅程预估继续使用普通读，避免在取得号码保护之前反向加锁。
- 营销发送自建事务及旅程节点事务使用 READ COMMITTED，依靠显式成员锁、号码保护及原业务行锁维持原子性，避免不同收件人在空索引区间互相阻塞。加入已有调用者事务时保留原隔离级别，并用当前读处理旧 RR 快照。短信先取得消息模块同样要求的租户写锁，外呼先取得租户共享锁，再锁号码和联系人，避免外键共享锁升级死锁。其余业务和语音账本的默认 RR 及刷盘校验继续生效。
- 日计数、周计数、周内最早触达和最近短信合并为一次有界时间范围的聚合查询。短信点击查询按需要单独执行。
- 增加 outbound_lock_wait_seconds 与 outbound_lock_hold_seconds 的直方图。scope 只取 platform、identity、partition，不使用号码、租户、通话 ID 或实际锁键。每次成功取得的 handle 计一次，重入 handle 分别计时，不能把累计持有秒数解释为互斥区总墙钟时间。
- 修正根 README、模块 README 和部署说明的主线版本与迁移口径。原 DAS V35 升级测试固定目标版本，后续迁移不改变其测试含义。

## 页面入口与状态清单

本批改变底层准入及指标，沿用既有页面与 API。

| 入口 | 本次需要复验 |
| --- | --- |
| 登录及退出、管理员及坐席权限 | 登录跳转、失效令牌、退出返回、越权请求 |
| 数据包与圈选快照 | 导入结果、预览、冻结、保存回显、返回 |
| 短信与外呼发送任务 | 创建、运行、拒绝原因、归因、对照组不发送 |
| 营销旅程 | 发布、启动、等待、暂停、恢复、停止及频控延后 |
| 话单、线索、企业交付及我的工作台 | 浏览器原有主链路、权限、表单、保存回显与返回 |
| 指标入口 | 既有鉴权与新锁等待和持有直方图 |

商城的门店、房型、购物车和支付入口不属于本项目。业务回归使用数据包、快照、触达任务、mock 执行、触达结果、档案和漏斗链路，并执行原外呼、转人工和企业门户回归。

## V2 方案逐项状态

| 方案项目 | 状态与未完成工作 |
| --- | --- |
| 固定代码及证据基线 | 已确认远端 main 与起始提交相同，实施分支独立。当前版本目标 Linux 的重复容量基线尚未建立 |
| 锁分类与耗时观测 | 已实现并测试低基数指标。目标环境采样、完整写入者归因和性能收益未验证 |
| 跨活动频控 | 本批实现数据库保护、当前读、原目标重试与同事务归因，新增真实 MySQL 并发与回滚反例 |
| 查询收敛 | 本批合并频控历史读取。已有并行任务领取与回执提交后唤醒保留 |
| V19 配额权威接线 | 未完成。需要全写入者及政策更新协议、旧新权威影子对比、发送安装栅栏与恢复一致性 |
| 拆锁和多角色部署 | 未完成。当前 JVM 事务锁仍是共享资源正确性的保护，应用继续只支持单实例。需跨进程所有权、持久通知和崩溃恢复测试后才能启用 |
| 旧实现退役 | 部署使用方尚未盘点，旧实现和默认分服务 Compose 仍保留 |
| 录音、脱敏及企业权限 | 本批执行既有功能回归。目标环境跨租户导出、备份删除、密钥恢复演练未验证 |
| 持续容量与电话体验 | 未完成当前版本目标 Linux 的 500 CPS、混合 AI、两轮一小时及长稳验收 |
| 真实通道与成本 | DAS、电话、模型、审核、短信的真实权限、音频与账单核对未验证。每次产生真实费用前须逐次说明用途和预计费用并取得批准 |

## 合并及上线边界

功能回归通过后可提交本批基础优化。该状态不授予全方案完成、容量达标、多实例或生产发布资格。测试环境发布、真机及生产发布均单独记录。

上线前须完成 V36 迁移演练与备份恢复，按现行约束停旧进程再启动新进程，验证实际业务链路和告警。持续容量、真实通道、故障恢复与成本门禁仍须分别取得证据。

本地首次全量测试曾在 Testcontainers 的数据库启动握手阻塞，线程栈确认尚未进入企业业务测试。企业门户测试容器已设置连接和读超时，后续完整结果单独记录。

## 最终本地验证

2026 年 11 月 2 日 23 时 03 分，JDK 21.0.13、Maven 3.9.11、Docker MySQL 8.4/Redis 和 Chromium 环境执行完整 mvn -o -B verify 通过。345 项单元测试、427 项集成测试，失败、错误和跳过均为零。Spotless、Mapper XML、Prettier、前端构建、依赖约束和独立 JAR 冒烟均通过。

浏览器主业务 43 组、营销 7 组通过，覆盖实际点击、页面内容、表单保存回显、返回、登录退出和权限异常。语音与百炼的既有浏览器回归也包含在本次完整测试中。七项新增数据库用例覆盖旧快照下争最后名额、回滚、原目标重试、哈希不一致、不同号码与活动并发、短信跨渠道间隔、V35 到 V36 升级和重复迁移以及不同短信收件人的死锁反例。

完整日志位于本地 .tools/arch-full-final.log，专项记录位于 .tools/arch-final-focused.log 和 .tools/arch-admission-lock-order.log 等。早期失败及握手中断保留，最终通过使用修复后的源码重新执行整套测试。

| 状态 | 本批结果 |
| --- | --- |
| 源代码已修改 | 已完成本记录列出的基础优化，GitHub 提交与 CI 以关联 PR 为准 |
| 静态检查通过 | 通过 |
| 开发环境功能测试通过 | 通过，345 单元和 427 集成 |
| 测试环境已发布 | 本批未发布 |
| 真机测试通过 | 未验证 |
| 生产环境已发布 | 本批未发布 |

本轮尚无实际环境的性能收益、500 CPS、长稳、真实通道和多实例通过证据。此前复现的并发死锁已纳入七项新增回归并通过；本地功能范围内尚未发现未修复失败。目标 Linux 和真实供应商资源均未进入本轮。用户确认先完成本地与 GitHub 验证。

## 2026 年 11 月 3 日：锁指标导出修复

111 隔离部署发现 `/actuator/prometheus` 在有效鉴权下返回 200，但响应体为空。应用手工创建的 PrometheusMeterRegistry 使用内部原始注册表，而 Boot 4 的导出端点读取另一个 PrometheusRegistry。现显式提供共享的原始注册表，并将其注入应用计量注册表，使业务指标、平台锁计时及 Actuator 使用同一数据源；不再出现写入和导出分离。

新增配置回归覆盖关闭可选导出时原指标仍可用、已有原始注册表被正确复用。真实 HTTP 集成回归覆盖业务指标、wait/hold × platform/identity/partition 六组非零计数、sum 和 histogram bucket，以及三个指标入口的有效令牌、缺少令牌和错误令牌状态。独立 JAR 冒烟也检查实际 `/actuator/prometheus` 的业务与六组锁指标，防止仅测试注册表对象而漏掉发布制品。

本次涉及的入口为 `/actuator/prometheus`、`/metrics` 和 `/metrics/runtime`。后两者继续维持原指标筛选语义；新增锁指标从 Actuator 导出。scope 仍只取上述三个固定值，没有加入电话号码或实际锁键。页面与主业务链路使用既有完整浏览器回归；111 继续保持供应商 mock、真实通道及后台发送关闭。当前修复不改变单实例和容量未验收边界，也不需要新增数据库迁移。测试通过、GitHub 合并及 111 发布的实际结果分别以对应验收证据为准。

## 2026-11-11 staged repair candidate (not a capacity qualification)

The candidate keeps the single-controller requirement. Phase completion must be assessed separately
from the presence of code or a green functional job:

| Phase | Candidate implementation | Remaining acceptance |
| --- | --- | --- |
| P0 | Reconciliation queries provider status without sending a timeout-derived hangup. Same attempt and provider identity are checked again under the call lock before terminal recovery. V50 atomically retains the original terminal callback before its transport row can be acknowledged/deleted; reconciliation replays this event and its original billing fields. An ended boolean without the correlated event retains capacity. | Delay acceptance beyond the watchdog age; prove no short synthetic occupancy and no resource release on UNKNOWN. |
| P1 | Callback consumption and playback/timer controls each use their own JDBC pool (callback default 2, control default 4, each bounded 1–4), separate from AI, analysis and general work. The default business connection budget increases by 6; capacity manifests record this explicitly. | 500 held calls, 50 planned turns/s, 120 seconds, original reply and drain thresholds. Further latency work depends on new measurements. |
| P2 | A dial cohort is admitted in independent chunks of at most 100 calls (`JAVA_DIAL_ADMISSION_CHUNK_SIZE`, default 100). A later failure leaves earlier immutable command identities recoverable. | Sustained CPS, failure/replay reconciliation. Durable-quota authority cutover and asynchronous report projection are not implemented by this patch. |
| P3 | Optional remote media transport and a separate JVM entry role, with RPC authentication, session/attempt/worker epoch checks and a worker-specific WS address. | Static-provider configuration only; real bidirectional PCM, control restart, live cancellation and accounting recovery remain unqualified. Profile authority RPC is not implemented, and profile mode is rejected rather than silently ignored. |
| P4 | Existing single-instance ownership, freeze and exit-78 behavior is retained. | Automatic controller/database failover is not implemented or qualified. Execution-enforced cross-primary fencing and a target DB replication/recovery contract are required. |
| P5 | Functional and synthetic capacity evidence must record the actual candidate Jar and all failed gates. | No blanket completion or production capacity claim until the independent gates pass. |

### Independent media process configuration

The default `MEDIA_WORKER_TRANSPORT=embedded` preserves the existing deployment. To exercise the
new transport, configure the controller with `MEDIA_WORKER_TRANSPORT=remote`, an independent
`MEDIA_RPC_TOKEN` of at least 32 characters, and `MEDIA_WORKERS_JSON` entries containing `id`,
`endpoint`, `ws_base`, and `capacity`. HTTP/WS hosts and explicit ports must match; outside
127.0.0.1 the pair must use HTTPS/WSS. The WS base ends in `/v1/pipecat/media` and is the address
reachable by FreeSWITCH, not the controller's loopback media address.

Start each worker from the same Jar using `OUTBOUND_RUNTIME_ROLE=media`, `MEDIA_WORKER_ID`,
`MEDIA_WORKER_CAPACITY`, `MEDIA_RPC_TOKEN`, `MEDIA_CONTROL_ENDPOINT`, the static speech-provider
configuration, and its own server port. This role imports HTTP/WS infrastructure only, without
business component scanning, Flyway, database pools or campaign workers. `/internal/state` and
`/internal/command` require the independent RPC bearer credential. The controller must reach the
worker and the worker must reach the controller. RPC failures retain ownership uncertainty.

`VOICE_AI_PROFILES_ENABLED=true` is rejected for this remote transport until the pinned profile
and credential authority is implemented. Do not enable remote transport for such deployments.
Worker state HTTP tests do not establish real audio capacity or continuity through a restart.
The absence of a real media/DB failover environment was confirmed for this task; those acceptance
items remain open rather than being replaced with synthetic results.

V50 terminal facts use the existing `VOICE_SENSITIVE_RETENTION_DAYS` policy (default 90 days)
and are removed in bounded purge batches. Older ended attempts without a retained terminal fact
remain conservative holds until normal callback recovery or explicit reconciliation. V50 is
additive; do not drop its trigger/table while the candidate writes callbacks. Its independent
schema contract and migration tests cover correlation, exact payload preservation, duplicate
terminal events, deletion of delivered transport rows and rollback atomicity.

The capacity profile enables the existing parallel task claim path and sets analysis batches to
4, bounded by the existing 1–16 contract. These are explicit configuration changes, not a
same-configuration performance comparison with earlier compatibility-mode runs. The production
analysis default remains 1. MySQL durability, SLO thresholds, stop deadlines and complete drain
checks are unchanged. The sending barrier retries only MySQL 1213/40001 transaction rollbacks,
at most once; exhaustion returns `503 DIAL_CONTROL_BUSY`. Clients may retry that documented
backpressure inside their original overall deadline, without assuming sending was closed.

Terminal replay does not overtake provider delivery: the gateway exposes the retained event only
after that call's voice outbox stream has drained. With the durable inbox enabled, reconciliation
submits the original event to that inbox and never directly applies lifecycle effects ahead of
older receipts. Legacy direct processing remains available only in the existing inbox-disabled
mode. Reconciliation's count describes replay submissions, not proof of completed release.

Dial cohorts now commit and send each group of at most 100 before preparing the next group. The
cohort's lease/fence remains unchanged, but admission closure can stop later unreserved groups
without a large already-admitted unsent tail. Successfully acknowledged groups retain their own
task completion outcomes if a later group fails; only the unfinished suffix inherits that
failure. Prepare/send profiling records cumulative group durations rather than one contiguous
prepare phase followed by one send phase.

Control claims and completion use a separate pool so AI connection saturation cannot strand an
already-executed playback/timer task before its durable completion. Task failure logs record
phase, identity, exception classes and SQL codes, without payloads or exception messages; drain
censuses retain failure-code samples for FAILED/DEAD tasks. Non-conflict failure retry limits
and DEAD-task gates are unchanged. This isolation addresses observed control-lane transaction acquisition failures;
pressure results remain authoritative for liveness and latency qualification.

Control executor concurrency is also capped by its reserved connection budget, so new control
claims cannot occupy every reserved connection while additional control threads wait to record
completion. The default four control workers have four reserved connections; configuring a
smaller control pool lowers physical control concurrency, while logical owner ordering remains
unchanged. This adds a bounded reservation rather than treating transient failures as success.

Playback and timer commands can see HTTP 409 while a newer media generation or provider terminal
callback is still in transit. Non-appointment `after_playback` tasks now defer with their frozen
payload and idempotency identity, without consuming the failure-attempt budget. They remain
PENDING and count against drain until the normal correlated generation/terminal path supersedes
them. A 409 alone never completes the task or releases resources. Persistent conflicts still
fail the drain deadline. Other errors retain the existing FAILED/DEAD behavior, and appointment
dial commands are excluded from this conflict treatment. Structured task diagnostics include
HTTP status codes without logging command bodies or exception messages.

Ordinary `telephony_speech` receipt consumption for a locked `answered`/`in_ai` call now
commits using its partition, receipt and call locks without the global resource mutex. Business
writes, durable follow-up tasks and receipt completion remain atomic. All other callback kinds
and call states roll back that local attempt before retrying through the original platform-lock
path, preserving partition-before-call ordering without lock upgrades. This narrow route never
changes reservations, quota authority or human-agent ownership. `CALLBACK_INBOX_LOCAL_SPEECH_ENABLED=false`
restores the original receipt route. A real-MySQL regression holds the resource mutex while two
speech events complete in order, then proves the following terminal event waits for that mutex.
The AI capacity fixture exposes bounded callback worker/pool/poll/batch settings and reports their
actual values; changing these is a different resource/configuration profile, not an isolated
code-only speed comparison.

A subsequent 60-second AI thread sample showed multiple callback consumers waiting on the same
worker-maintenance identity while already holding their reserved JDBC connections. Scheduled
receipt consumers now coalesce maintenance per process and successful heartbeat interval (at most
one second, shortened for smaller worker TTLs). Explicit maintenance remains immediate. The worker
identity is acquired before borrowing JDBC, and failed maintenance does not advance the cadence.
The short heartbeat commits before receipt retention work. The existing seven-day minimum
retention predicate is preserved; cleanup uses one bounded DELETE of at most 1000 completed
receipts instead of loading and deleting up to 100 bodies individually. Pending and recent
receipts remain protected. Explicit maintenance calls still force a pass. A real-MySQL regression
blocks the elected maintenance caller and verifies the other consumers return and both callback
connections remain borrowable, then confirms the worker record commits after releasing the lock.

Admission closure now switches both dial claim paths to existing frozen journal identities.
Callback admission backpressure cannot pause captured-command recovery while waiting for the
sending barrier: recovery continues in ADMISSION_CLOSED, and fresh queued tasks remain untouched.
The separate SEND_CLOSED decision still prohibits sends and permits status confirmation only.
The reserved-unsent MySQL regression forces unhealthy admission feedback, recovers the captured
command through the real batch worker, verifies fresh task attempts stay zero, and closes sends
without bypassing acceptance/cancellation proof.

The isolated stop probe retries only documented pre-transaction `503 PLATFORM_LOCK_TIMEOUT`
responses for admission closure, recording each retry. Admission capture, sending closure and
full drain now share 180 seconds from the stop request; retries do not grant another deadline.
Unknown responses and unresolved sends remain failures.
