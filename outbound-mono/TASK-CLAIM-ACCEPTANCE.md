# 任务领取并行化与查询范围收敛验收

日期：2026-10-31。分支：`perf/task-claim-20261031`，基于 `perf/control-path-latency-20261031`（PR #101）。

## 各功能的性能分析结论（本轮）

| 功能 | 热路径 | 发现 | 本轮处理 |
|---|---|---|---|
| AI 轮次、播放控制、录音/回调集成、通话分析（持久任务） | `TaskWorker.claim` | 默认串行领取：所有 lane 共用一把 JVM 内的 `java-outbox-claim` 互斥锁，每次只读 1 个候选。仓库中已有并行领取模式（每租户最多 16 个 FIFO 合法候选，`FOR UPDATE SKIP LOCKED` 后复核租约，同一流仍等待前驱），也有并发测试覆盖，但默认关闭 | **默认改为并行领取** |
| 通话详情：事件列表、回调事件列表 | `Calls.events` / `Calls.webhookEvents` | `visible()` 已经确认通话属于调用者的租户，但随后仍对全部租户各查一次再拼接 | **只查询调用者租户** |
| 通话分析批处理的租约回收 | `processAnalysisBatch` | 检查后无缺陷：活跃租约不会被抢，孤立的过期任务能被回收 | 补两项回归测试 |
| 前端（Vue） | `router.js` | 路由已经按页面懒加载 | 不改 |
| 租户列表 | `Store.across` | 每次调用都查一次租户表，是单行级别的索引查询；改成缓存后，测试里有直接用 SQL 插入租户的场景，会有缓存过期风险，收益不足 | 不改 |

## 修改内容

- `JAVA_TASK_CLAIM_PARALLEL_ENABLED` 默认值从 `false` 改为 `true`（`TaskWorker.claim` 与 `processAnalysisBatch`）。设为 `false` 可恢复原来的串行领取。资源准入锁、终态锁、租约和同流保序都不变；并行领取本来就不解除电话和模型资源的准入锁。
- `Calls.events` / `Calls.webhookEvents`：用 `identity.tenant()` 直接查询，不再按全部租户扇出。返回内容和分页语义不变（通话只属于一个租户）。

## 新增测试

- `QueueConcurrencyIT.atomicAnalysisLeavesALiveLeaseWithItsOwner`：刚续期的分析任务不会被批处理领走。
- `QueueConcurrencyIT.atomicAnalysisReclaimsALoneExpiredLeaseWithoutASuccessor`：worker 崩溃后，没有后继任务的过期分析任务也能被回收并完成。

这两项在修改前后的代码上都通过，作用是防止回归，不是修复记录。

## 验证记录

本地结果（macOS arm64；JDK 24 以 `--release 21` 编译，Docker Testcontainers MySQL/Redis；跳过了前端构建和 enforcer。JDK 21 全量 `mvn verify` 以 PR 的 CI 为准），均在并行领取为默认值的条件下：

- Spotless 格式检查通过。
- 单元测试全量 335 项：失败、错误、跳过均为 0。
- 集成测试 87 项：`BusinessHttpIT` 21、`QueueConcurrencyIT` 33（含新增 2 项）、`OutboundReliabilityIT` 31、`CallbackRotationTest` 2，失败、错误、跳过均为 0。

没有做容量测试，不声明容量达标；测试环境、真机和生产都没有发布。
