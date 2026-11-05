# 2026-11-05 运行保护修复与验收

依据用户提供的《AI外呼平台优化技术方案 V2（2026-11-05）》，从 main `2c2d3030ca015f9973a33180942295798ad140a0` 实施。本文记录代码行为与验证边界；方案中的阶段 B/C 仍按其前置门禁推进，不把未来架构设计视为已经上线的能力。

## 对应修复

| 项目 | 实现与保留的不变量 |
| --- | --- |
| A1 单实例保护 | 生产必须持有专用 MySQL 物理连接上的 GET_LOCK；数据库名与稳定部署标识生成锁名。取得锁后才创建业务数据源和 worker。第二实例启动失败。独立线程每秒检查原 CONNECTION_ID 的所有权，检查/连接失败立即 FROZEN，拒绝 HTTP、事务、平台锁及新增拨号，然后以 78 退出，不自行重连恢复写入。 |
| A2 分层预算 | JDBC 建连 3 秒、socket 10 秒；显式 URL 配置必须为正且不超过上限，保留 TLS 参数。默认/后台池取连接 5 秒，实时池 1 秒；MySQL 行锁/元数据锁 5 秒。通用事务 15 秒，准入/实时事务 5 秒；嵌套事务只能缩短已有截止时间。平台锁后台 2 秒、HTTP 最多 3 秒，超时/中断返回稳定错误码，保留重入与事务结束释放；普通 worker 与批量拨号 worker 将锁争用延后，不消耗业务失败次数。Redis 建连与命令各 500 毫秒，原生客户端增加总截止时间关闭一次性连接，防止持续慢速返回绕过 idle 超时。 |
| A3 调度隔离 | 显式 4 个轻量触发线程；20 个定时入口分别提交到固定枚举定义的单线程、有限队列执行通道，同一任务不得重入。每轮默认 5 秒协作预算，逾时中断；忽略中断的任务继续占有原通道，不能启动替代写入者。拨号、AI、回执与续租原执行器保留。短信回执每轮最多 4×1000 条，域名探测/营销旅程按预算结束，余项留给下一轮。 |
| A4 ESL 故障分级 | 已定位单通事件错误持久隔离，后续同通事件继续有序隔离，保留原资源。原始事件身份可靠时去重；身份或归属不明、隔离持久化失败或过载时自动暂停。NORMAL/DEGRADED/PAUSED/RECOVERING 独立于人工 stopped 标志；30 秒窗口至少 20 个样本、错误率达到 20% 暂停。距故障 10 秒且连续 5 个健康巡检、积压低于 128 才恢复试运行；RECOVERING 最多每秒允许一个新请求。普通事件最多占 1792 个槽位，256 个槽位保留给终态/后台确认，溢出事件必须持久隔离。 |
| A5 锁内 I/O | 身份告知音频在准入事务前读取，准备租户/凭据版本/音色/文本标识及 SHA-256。事务只匹配不可变凭证和 10 秒有效期；身份变化或过期拒绝。播放前复验标识和摘要，素材丢失或变化安全失败。准入前读取带时效的 WebRTC 注册快照，未知/超过 5 秒不预留；最终仍由事务内坐席状态和持久预留决定。已提交的未知命令仍按原冻结身份对账，不通过重建命令重拨。 |

## 有效配置与运维

- `ENV=prod` 或 `production` 时不能关闭 `JAVA_SINGLE_INSTANCE_GUARD_ENABLED`。开发环境默认关闭，可显式设为 `true` 演练。
- `JAVA_DEPLOYMENT_ID` 默认 `outbound-mono`。同一部署的新旧实例必须保持相同值，不能更改标识来绕开互斥。此锁仅限同一 MySQL 实例，不授予自动切主、多实例或滚动升级资格。
- `JAVA_LOCK_WAIT_MS` 为 1—2000；`JAVA_HTTP_LOCK_WAIT_MS` 为 1—3000。超时为 `PLATFORM_LOCK_TIMEOUT`，中断为 `PLATFORM_LOCK_INTERRUPTED`，运行所有权丢失为 `RUNTIME_OWNERSHIP_FROZEN`；HTTP 为 503。不能据此重发已存在的未知拨号命令。
- `JAVA_SCHEDULED_WORK_MS` 默认 5000，范围 100—30000；只影响定时入口工作切片，不能代替网络超时。已经有独立执行器的任务继续使用其原批量/租约预算。
- 新增 V44 `voice_event_quarantine`，不修改 V1—V43。仅保存脱敏后的事件身份、调用归属及错误类型，不写入号码、转写、鉴权头或供应商正文。`RECONCILE_REQUIRED` 表示尚未处理成功；没有按 TTL 删除/释放资源的清理器。
- 隔离事件需要将 `voice_event_quarantine` 与 `voice_attempts`、`dial_command_journal`、PBX 终态证据按 UUID/call/attempt 核对。不得直接重放后续事件或把隔离记录当成功；无法补偿的通话转人工。只有独立终态或可靠未发送证明允许原生命周期协议释放资源。
- 自动保护不会调用人工停止的恢复接口；人工/合规 stopped 保持原有授权和审计要求。

低基数指标：`outbound.lock.failures{reason=timeout|interrupted}`、`outbound.scheduled.delay`、`duration`、`age`、`rejected`、`budget_exceeded`（job 为固定枚举）；原 ESL 指标新增 `protection_state`，依次为 0/1/2/3。持续出现超时应冻结新准入并检查池等待、任务年龄和未知命令，不能通过重启或放大超时掩盖。

## 可复验命令

使用 JDK 21、Maven 3.9.11、Node 24.19.0、pnpm 11.25.0、Docker/MySQL 8.4，所有通道为本地模拟：

```sh
cd outbound-mono
mvn -B verify
mvn -B enforcer:enforce
```

`RuntimeOwnershipIT` 使用两个真实子 JVM 争锁，并切断持锁连接验证退出；`RuntimeBudgetsIT` 验证静默 MySQL 端点、池耗尽恢复、行锁等待回滚和嵌套截止时间；`ScheduledWorkTest` 验证忽略中断的营销任务不会阻塞拨号/回执，也不会重入；`EslEventProtectionTest` 验证隔离去重、后续顺序、资源保留、最小样本、人工停止不被自动清除和持久化失败；`OpeningAdmissionFactsTest` 验证租户、凭据版本、文本、时效与线程上下文。

受限环境可将 `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` 显式指向已有 Chromium，供浏览器测试使用；未配置时 CI 继续使用 Playwright 固定版本浏览器。两种运行的浏览器版本证据应分别记录。

## 单体制品与镜像

`Dockerfile.runtime` 只封装已经通过 `mvn verify` 的 `target/outbound-mono.jar`，JRE 21 按镜像 digest 固定，使用 UID/GID 10001，前端与全部 Flyway 迁移随同 jar 交付。CI 在功能回归后构建该镜像。此入口与历史 backend-java/services-java 镜像分开；没有切换现有现场部署。

```sh
cd outbound-mono
docker build -f Dockerfile.runtime --build-arg VCS_REF="$(git rev-parse HEAD)" -t outbound-mono:verified .
sha256sum target/outbound-mono.jar
docker image inspect outbound-mono:verified --format '{{.Id}}'
```

部署登记提交号、jar SHA-256 和镜像 digest。配置/密钥通过运行环境或受控 secret 文件注入；数据库迁移使用专用凭据，不烘焙进镜像。`/app/data`、`/app/applog` 及实际 PBX 录音路径须单独持久化并赋予运行 UID 所需权限。更新先关闭新准入、处理已捕获拨号及在途回执、确认旧 PID 退出，再迁移、启动唯一实例。运行保护以 78 退出后必须人工确认旧实例状态再恢复，禁止无条件自动重启形成反复争锁。

## 阶段门禁和六类状态

本批只修复单实例主线中的运行缺口。持久配额权威全写入者切换、号码在途占位协议迁移、有界跨租户任务领取、dispatcher 执行端 fencing、跨机角色/媒体、数据库切主和生产恢复均不能据本批代码宣布完成。原 JVM 共享资源锁、任务租约/代次、提交后下发、命令日志和回执幂等继续有效。

目标 Linux 的两轮 500 CPS、500 在途 AI 控制路径、8/24 小时长稳、真实 SIP/RTP/云 ASR/LLM/TTS、真实短信、F222 真机和恢复演练仍需独立证据。容量阈值保留，未降低或跳过后标为通过。当前没有测试/生产环境的发布动作，没有真实付费通道调用。源码、静态、开发功能、测试发布、真机、生产六类状态见本批验证结果，开发回归不能替代生产放行。
