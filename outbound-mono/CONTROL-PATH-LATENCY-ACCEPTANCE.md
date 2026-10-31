# 控制面并发与延时修复验收

日期：2026-10-31。基线：main `916d455`。分支：`perf/control-path-latency-20261031`。

本次针对代码分析报告中的低风险项，不改变业务语义、幂等键、保序、租约或 generation 栅栏。全局平台锁拆分、任务批量领取和进程内事件直达会改变并发语义，**未包含**在本次修改中。

## 修复范围

| 报告问题 | 本次处理 | 位置 |
|---|---|---|
| `Store.locate` 按租户逐个查询（1+T 条 SQL，锁定读还会在其他租户的不存在行上加锁） | `callsession` 先按全局 UUID 主键读 `tenant_id`，再走原有带租户条件的 `findById`/`lockById`；改为固定 2 条 SQL。新增的 Mapper 方法已登记到租户查询例外清单 | `common/dao/Store.java`、`CallSessionMapper.tenantOfId`、`tenant-query-policy.json` |
| 回调收件箱只能等 100 ms 轮询 | 回执写入的事务提交后发布 `CallbackReceived`，`TaskWorker` 在 after-commit 阶段立即触发消费；定时轮询保留，用于恢复 | `CallbackInbox`、`TaskWorker.received` |
| 语音事件 outbox 只能等轮询（默认 100 ms），同一通话的后一条要再等一个周期 | 写入提交后，以及前一条收到 HTTP 确认后，在轮询线程上合并执行一次投递；失败重试的退避（≥1 s）不变 | `voice/service/VoiceCallbacks.wake` |
| 租约续期只有 1 个线程 | 续期线程数改为 2~8 个（CPU 核数的一半） | `TaskWorker.heartbeat` |
| 平台锁为公平锁 | 改为非公平锁；已排队的等待者仍按顺序获得锁 | `JvmPlatformLock` |
| 媒体 WebSocket 在 `synchronized` 内阻塞发送，JDK 21 下会钉住虚拟线程的载体线程 | 改为每个 Stream 一把 `ReentrantLock` | `NativeMedia.Stream.binary/text` |

保持不变：
- `VOICE_PARTIAL_TRANSIENT_ENABLED` 默认值不变。改为不持久化 partial 会减少已保存的话轮记录，属于数据和产品行为变化，需要单独决定。
- `leaseCutoff()` 语义不变。

## 新增测试

- `VoiceTest.committedCallbacksAndAcknowledgedPredecessorsWakeDeliveryBeforeThePollInterval`：轮询周期设为 10 s，同一通话的两条事件按顺序在 3 s 内送达。这只能由提交后唤醒和确认后唤醒实现。
- `BusinessHttpIT.locateResolvesTheOwningTenantFromThePrimaryKeyWithoutScanningTenants`：租户 2 的通话可以定位到（含锁定读），未知 ID 返回 null。

## 验证记录

本地结果（macOS arm64；JDK 24 以 `--release 21` 编译，Docker Testcontainers MySQL 8.4/Redis；跳过了前端构建和 JDK 版本 enforcer。JDK 21 全量 `mvn verify` 和浏览器验收以 PR 的 GitHub `outbound-mono` 检查为准）：

- Spotless 格式检查通过。
- 单元测试全量 335 项：失败、错误、跳过均为 0。
- 集成测试 83 项：`BusinessHttpIT` 21、`QueueConcurrencyIT` 31、`OutboundReliabilityIT` 31，失败、错误、跳过均为 0。

没有做容量测试（500 CPS / 500 在途），本次不声明容量达标；测试环境、真机和生产都没有发布。
