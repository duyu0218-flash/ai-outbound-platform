# 营销中台：模块边界与公共契约

营销中台把“存量数据 → 撞库 → 圈选 → 触达（AI 外呼 / 短信 + 短链）→ 结果判定 → 档案 → 二次触达”做成一个闭环。外呼（`campaign`、`callsession`）和消息（`msg_*`）沿用现有执行层，营销中台只负责上层的数据、编排与分析。技术方案原文见共享文档《AI外呼平台 · 全生命周期营销技术方案》。

## 模块划分

| 模块 | Java 包 | 迁移版本 | 管理端页面 | 职责 |
| --- | --- | --- | --- | --- |
| 公共底座 | `marketing.common` | V30 | — | 号码身份、触达事件、归因、准入策略、权限、结果码 |
| P1 数据与人群 | `marketing.dataset` | V31 | `/admin/marketing/datasets`、`/admin/marketing/segments` | 数据包导入、撞库（MatchAdapter）、结果分析、人群规则与快照、从快照创建外呼/短信任务 |
| P2 短链 | `marketing.link` | V32 | `/admin/marketing/links` | 域名池、短链生成、`/s/{code}` 跳转与点击采集、短信状态报告转为触达事件 |
| P3 结果判定 | `marketing.outcome` | V33 | `/admin/marketing/outcomes` | 外呼三层判定（接通 / 接通质量 / 意向）、判定规则与语音助手话术库、人工纠正 |
| P4 编排与分析 | `marketing.journey` | V34 | `/admin/marketing/journeys`、`/profiles`、`/dashboard` | 组合策略引擎、跨渠道频控（`@Primary` ContactPolicy）、用户档案与生命周期、漏斗看板 |

## 公共契约（`marketing.common`，各模块只读不改）

- `PhoneIdentity`：号码规范化为 E.164；`hash` 与 `msg_message.recipient_hash` 相同；`encrypt/decrypt` 使用 `MESSAGING_MASTER_KEY`；`mask` 输出 `138****8000`。任何营销表都不存明文号码。
- `OutcomeCodes`：唯一的结果码词表。新增结果码只能改这个类。`BLOCKING` 中的结果码出现后，所有渠道停止联系该号码。
- `Attribution`：创建短信或外呼时调用 `register(...)` 写 `mk_dispatch`，记录它来自哪个快照、活动和成员；处理回执时用 `find(...)` 取回。
- `TouchEvents`：写 `mk_touch_event` 的唯一入口。同一来源的同一结果码只存一次，事务提交后发布 `TouchEvents.Recorded`。P4 监听它推进活动并更新档案。
- `ContactPolicy`：发送或拨号前必须调用 `admit(...)`。底座的 `SuppressionContactPolicy` 检查通话黑名单、短信退订、DNC 和阻断类结果码；P4 用 `@Primary` 实现叠加频控，并在内部委托底座策略。
- `MarketingAccess`：营销页面只对管理员开放，API Key 需要 `admin:marketing` 权限。
- `SnapshotMembers`（P1 实现）：读取快照成员，按序分页，区分实验组与对照组。
- `ChannelSender`（P1 实现）：单人发送短信或把单人加入 AI 外呼任务。内部依次完成准入检查、在执行层创建、写归因。P1 的批量建任务和 P4 的活动节点都通过它执行。
- `LinkMinter`（P2 实现）：为每个收件人生成短链，消息创建后再调用 `bindMessage` 关联消息 ID。`ChannelSender.sms` 在 `landingUrl` 不为空时调用它，用短链替换模板中的 `{link}` 占位符。
- 消费方注入以上三个接口时必须使用 `ObjectProvider<...>`：实现方模块还没合入时，应用要能正常启动，并在页面上提示该能力“待接入”。
- `MarketingStore`：JdbcTemplate 封装。SQL 写在各模块内，必须绑定 `tenant_id`；后台跨租户扫描的语句旁边要写注释说明。

## 模块之间的依赖

模块之间不直接调用对方的类，只通过公共契约和数据表交互：

- P1 生成快照，实现 `SnapshotMembers` 和 `ChannelSender`。P4 只通过这两个接口读取成员和发送，不读 P1 的表。
- P2 和 P3 把回执写成 `mk_touch_event`。P4 只依赖 `TouchEvents.Recorded` 和 `mk_touch_event`，不依赖 P2、P3 的内部表。
- P1 的 `ChannelSender.sms` 通过 `LinkMinter` 生成短链（P2 实现），自己不实现短链。

## 开发约定

- 每个模块只修改自己的包、自己的迁移文件、自己的页面文件（`frontend/src/pages/Marketing*.vue` 中归属自己的那几个），以及自己的测试目录 `src/test/java/com/duozhi/outbound/marketing/<模块>/`。
- 修改公共文件（`router.js`、`i18n.js`、`OutcomeCodes` 等）时，必须在验收记录里写明原因。
- 测试继承 `MarketingTestBase`：所有营销测试共用同一个 MySQL 容器和 Spring 上下文，每个测试使用独立租户。后台 worker 由 `MARKETING_WORKERS_ENABLED` 控制，默认关闭；测试中直接调用 worker 的单步方法。
- 每个模块写一份 `MARKETING-P{n}-ACCEPTANCE.md`，记录范围、测试命令与结果、未验证项（真实通道、真实线路、合作方联调）。
- 合并前须通过 `mvn -B verify`，其中包含 Spotless、Prettier、全部测试和前端构建。
