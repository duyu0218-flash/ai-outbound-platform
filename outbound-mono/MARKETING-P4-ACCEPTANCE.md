# 营销中台 P4 验收记录：编排、频控、用户档案与漏斗看板

分支 `feat/marketing-p4-journey`，包 `com.duozhi.outbound.marketing.journey`，迁移 `V34__marketing_journey.sql`，页面 `MarketingJourneys.vue`、`MarketingProfiles.vue`、`MarketingDashboard.vue`。

## 范围

### 1. 组合策略引擎
- 表：`mk_journey`（活动与草稿）、`mk_journey_version`（已发布版本，只插入不修改）、`mk_journey_member`（成员、当前节点、`next_run_at`、状态）、`mk_journey_step`（每个成员每个节点一行，既是执行日志也是防重发保护，唯一键 `(member_id,node_id)`）。
- 版本是 DAG JSON，节点：`sms` 发短信、`call` 加入 AI 外呼任务、`wait` 等待 N 小时、`condition` 条件分支（自成员加入起是否出现指定结果码，最多等待 `windowHours`，超时走“不满足”默认分支）、`end`。发布前校验节点编号、引用、结果码、无环。草稿内容未变时再次发布不生成新版本；运行中的成员始终使用启动时绑定的版本。
- 启动：先预估（快照人数、实验组/对照组、准入过滤人数及原因、按最贵路径估算的成本），预估成本超过租户阈值时进入“待审批”，必须由另一位管理员（不能是提交人、不能是 API Key）批准后才入组。入组按快照序号幂等（唯一键 `(tenant_id,journey_id,member_sequence)`）。
- 对照组成员照常走流程，但发送节点只记 `control` 步骤，不发送。
- Worker（`MARKETING_WORKERS_ENABLED` 控制，默认关闭）：`FOR UPDATE OF m SKIP LOCKED` 选出到期成员后用条件更新领取（带 claim token 与 5 分钟租约），每个节点在独立事务内与发送、步骤日志一起提交；租约过期回收，旧 token 失效。单步方法 `JourneyEngine.step/claim/run` 供测试调用。
- 监听 `TouchEvents.Recorded` 唤醒等待中的成员：优先按 `journey_member_id`，没有归因时按号码 hash；条件本身总是重新读 `mk_touch_event`，唤醒只是提前检查。
- 暂停 / 继续 / 停止；所有成员结束后活动自动变为“已完成”。
- `ChannelSender`、`SnapshotMembers` 通过 `ObjectProvider` 注入：未接入时应用正常启动，页面提示“数据模块未接入”，含发送节点的活动发布、任何活动的预估/启动返回 409 及明确说明。

### 2. 跨渠道频控
- `JourneyContactPolicy`（`@Primary ContactPolicy`）：先委托底座 `SuppressionContactPolicy`，再执行 `FrequencyRules`：
  - 可联系时段（默认 09:00–20:00，时区 Asia/Shanghai，可按租户在 `mk_policy_config` 设置）；
  - 每人每渠道每日上限（租户时区自然日）与 7 天滚动上限，计数来自 `mk_dispatch`；
  - 短信后 120 分钟内不外呼，除非之后出现 `LINK_CLICKED`（来自 `mk_touch_event`）。
- 编排引擎在发送前先做同样的检查，被时段/上限/间隔挡住时按返回的 `retryAt` 延后（最多 30 次），不是直接丢弃。
- 时间基准：`mk_dispatch.created_at` 是数据库会话时区，`mk_touch_event.occurred_at` 与 P4 各表是应用时区，`MarketingClock` 统一按 Instant 换算，JVM 与 MySQL 时区不同也不会错位。
- 默认时段通过 `MARKETING_CONTACT_WINDOW` 显式配置；测试基座设为 `00:00-24:00`，所以 `MarketingFoundationIT` 及其他模块的测试结果与运行时刻无关；时段测试使用租户配置 + 固定时钟。

### 3. 用户档案
- `mk_profile`：短信数/送达/点击、外呼数/接通/秒挂/有效对话、语音助手标记、最高意向、是否转化、出现过的结果码、首末触达时间、最近渠道、阶段；`mk_profile_history` 记录阶段变化。
- 阶段：待挖掘 / 高潜 / 已触达 / 活跃 / 商机 / 已转化 / 沉默 / 不可触达。规则按顺序匹配（`anyCodes` / `noneCodes` / `minTouches`），租户可在“频控与单价设置”里改 `stage_rules`。
- 幂等：`mk_profile_applied` 以 `mk_touch_event.sequence` 为主键，监听器和补偿任务都经它，同一事实只计一次；短信/外呼次数按来源（消息 / 通话）的最早一条事实计一次，与应用顺序无关。
- 不漏：事件只在提交后发布（回滚不计）；监听器在 `REQUIRES_NEW` 事务中写入（afterCommit 阶段原事务连接仍绑定在线程上）；补偿任务按 `sequence` 水位扫描，水位只越过已超过 `MARKETING_PROFILE_SETTLE_SECONDS`（默认 120 秒）的事实，晚提交的序号下次仍能扫到。
- 页面输入手机号，以 POST 请求体提交，服务端算 hash；返回脱敏号码、档案、阶段历史、触达时间线（`TouchEvents.history`）和参与的活动，不返回明文和 hash。

### 4. 漏斗看板
- `mk_stat_hourly` 按小时汇总（按活动 / 快照 / 渠道 / 实验或对照组 / 指标），Worker 每 5 分钟重算最近 3 小时，页面“重新汇总所选日期”可补算。看板只读汇总表。
- 漏斗：圈选 → 触达（送达或接通）→ 互动（点击或有效对话）→ 意向 A+B → 转化。人数指标按“该范围内首次满足的那一小时”计入，跨小时相加不重复。活动成员从入组时起按号码匹配事实（含对照组的自然转化）；不经活动、直接按快照发送的，按快照计（圈选人数 = 首次发送人数）。
- 指标：短信送达率、点击率、接通率、秒挂率、语音助手占比、有效对话率、意向率、转化率、增量转化（实验组转化率 − 对照组转化率）、总成本与单个意向成本（单价可配置）。筛选：活动 / 快照 / 日期 / 渠道。页面为卡片 + 漏斗条 + 按活动、按日期两张表。

### 5. 活动页面
列表、新建/编辑（纵向节点列表 + 分支预览）、保存草稿、发布、快照预估、启动、审批、暂停/继续/停止、运行情况（各节点停留人数、执行结果、分组状态、触达结果、最近执行记录）、频控与单价设置。

## 新增表与接口

表：`mk_policy_config`、`mk_journey`、`mk_journey_version`、`mk_journey_member`、`mk_journey_step`、`mk_profile`、`mk_profile_history`、`mk_profile_applied`、`mk_worker_cursor`、`mk_stat_hourly`。

接口（均在 `/api/v1/marketing`，`MarketingAccess.operator` 鉴权）：
`GET/POST /journeys`，`GET/PUT /journeys/{id}`，`GET /journeys/{id}/versions/{n}`，`POST /journeys/{id}/publish|estimate|start|approve|reject|pause|resume|stop`，`GET /journeys/{id}/runtime`，`GET/PUT /policy`，`POST /profiles/lookup`，`GET /profiles/overview`，`GET /dashboard`，`POST /dashboard/refresh`。

## 测试

目录 `src/test/java/com/duozhi/outbound/marketing/journey/`。`JourneyFakes` 提供假 `SnapshotMembers` / `ChannelSender`（`@Primary`，经主 `ContactPolicy` 准入，按营销时钟写 `mk_dispatch`），只由导入它的测试类使用，形成独立的 Spring 上下文，不影响其他营销测试；`MarketingClock.fix/advance/reset` 控制时间。

| 测试 | 覆盖 |
| --- | --- |
| `JourneyEngineIT` | 短信 → 等 24h → 点击？→ 外呼 : 二次短信 全流程（含对照组、归因、运行情况、自动完成）；条件按成员 ID / 按号码唤醒、无关结果码不满足、超时走默认分支；发布不可变、未变化不新建版本、运行绑定版本；非法流程（循环、未知结果码）与坐席无权限；并发领取不重复、租约回收与旧 token 失效、6 个 worker 并发执行每人只发一次；暂停/继续/停止；预估过滤与成本、超阈值需另一位管理员审批 |
| `FrequencyPolicyIT` | 底座屏蔽优先；每日上限（分渠道、次日恢复）；7 天滚动上限及 retryAt；短信后 2 小时不外呼、点击后放行、间隔可配；时段（显式默认、租户配置、边界 08:59/09:00/19:59/20:00、配置校验）；活动在时段外延后到次日 09:00 再发送 |
| `ProfileIT` | 各计数与阶段迁移、阶段历史、沉默/不可触达/高潜；租户自定义阶段规则；重复事实、事务回滚、绕过监听器写入后由补偿任务补上、补偿重复执行与再次应用均幂等、水位不越过未稳定事实；POST 查询档案（脱敏、无明文无 hash、非法号码 422、坐席 403） |
| `FunnelDashboardIT` | 小时汇总表数值、漏斗五步、全部比率、增量转化、成本与单个意向成本、渠道筛选、直接按快照发送的统计、多活动与按日表、重算幂等、单价修改生效、空结果 |
| `JourneyNotConnectedIT` | 未接入 P1 时应用可用，能力标记为 false，发布/启动返回“数据模块未接入” |

命令：

```
mvn -B -Dtest=NoSuch -Dit.test='Journey*IT,FrequencyPolicyIT,ProfileIT,FunnelDashboardIT,MarketingFoundationIT' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false verify
mvn -B verify
```

结果：见文末“全量验证”。

## 修改的公共 / 现有文件及原因

- `src/test/java/com/duozhi/outbound/marketing/MarketingTestBase.java`：增加 `MARKETING_CONTACT_WINDOW=00:00-24:00`。P4 的 `@Primary ContactPolicy` 带可联系时段，若不显式配置，`MarketingFoundationIT`（以及 P1–P3 调用准入的测试）在夜间运行会失败。
- `src/test/java/com/duozhi/outbound/integration/test/DataLayerIT.java`、`DataLayerReplayIT.java`：底座 V30 合入后这两个测试在基线上就已失败（表数 105、迁移数 29/11/7 写死）。改为忽略 `mk_` 表、迁移数按 V30 及以后的文件数累加。P1–P3 很可能做类似修改，合并时请保留一份。
- `frontend/tests/client-browser.mjs`：侧边栏分组断言加入底座新增的“营销中台”分组（底座合入后 `FrontendBrowserIT` 在基线上已失败）。
- 未修改 `marketing.common`、`router.js`、`i18n.js`。

## 待联调（P1、P2、P3）

- P1：真实 `SnapshotMembers` / `ChannelSender` 合入后去掉假实现验证：`phoneCipher` 用 `PhoneIdentity.encrypt` 加密（预估时需解密做屏蔽检查）；`ChannelSender` 内部调用 `ContactPolicy.admit` 时拿到的是 P4 的频控；单条发送写 `mk_dispatch` 的 `channel` 为 `sms`/`call`（频控计数依赖）；P1 批量任务同样受频控影响（默认短信每人每天 2 条、7 天 6 条，外呼每天 2 次、7 天 6 次）。
- P2：短信状态回执与点击事件需通过 `Attribution.find` 带上 dispatch，才能按 `journey_member_id` 唤醒成员；点击事件 `code=LINK_CLICKED`，用于外呼间隔放行和条件分支。
- P3：外呼结果按通话（同一 `source_id`）写 CALL_/ANS_/INTENT_ 事实，档案按来源计外呼次数。
- 迁移版本：本分支只有 V30 与 V34；已在开发库执行过 V34 的环境再合入 V31–V33 需要 Flyway `outOfOrder` 或重建库。

## 未验证

- 没有真实发送（短信通道、线路），发送均为假实现。
- 没有大数据量压测：预估与入组在请求线程内按 1000 条分页同步执行；小时汇总的“首次”计算按租户扫描成员与事实，百万级快照的耗时未测。
- 页面未在浏览器中逐项点击验证，仅通过 Vite 构建和 Prettier 检查。
- 多实例部署下的 Worker 并发只在单 JVM 多线程中验证（数据库层用 SKIP LOCKED 与条件更新保证）。

## 全量验证

2026-11-01，`mvn -B clean` 后 `mvn -B verify`：BUILD SUCCESS。单元测试（surefire）338 个，失败 0；集成测试（failsafe）374 个，失败 0、错误 0、跳过 0；包含 Spotless、Prettier、前端构建与 `FrontendBrowserIT`。P4 自身 19 个集成测试（JourneyEngineIT 7、FrequencyPolicyIT 6、ProfileIT 4、FunnelDashboardIT 1、JourneyNotConnectedIT 1）及 `MarketingFoundationIT` 4 个全部通过。
