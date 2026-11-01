# 营销中台 P1：数据包、撞库与人群 验收记录

原分支 `feat/marketing-p1-data`；Codex 已接续到 `codex/marketing-resume-20261101`，保留原工作区。包 `com.duozhi.outbound.marketing.dataset`，迁移 `V31__marketing_dataset.sql`，页面 `MarketingDatasets.vue`（数据包与撞库）、`MarketingSegments.vue`（人群圈选）。所有接口都在 `/api/v1/marketing/...` 下，经过 `MarketingAccess.operator` 校验（仅管理员，或带 `admin:marketing` 的 API Key）。

## 范围

### 1. 数据包（`DatasetImports`、`TextFiles`）
- 表：`mk_dataset`、`mk_dataset_version`、`mk_dataset_chunk`（上传暂存）、`mk_dataset_row`。
- 支持 CSV/TXT，编码为 UTF-8（带或不带 BOM），否则按 GB18030 严格解码。表头别名为 `phone / userTel / mobile / 手机号 / 手机号码`（不区分大小写）；没有表头时取第一列为号码。地区（region/地区/省份/归属地/城市）、运营商（carrier/运营商）、来源（source/来源/渠道）单独成列，其余列存入 `attrs` JSON。属性中出现的手机号会被掩码。
- 上传时一次解析，按 2000 行切块，每块加密后暂存。每块的导入和该块的行在同一事务中提交，导入完成后清空该块的密文。导入中断后，从下一个未完成的块继续。小文件在上传请求内直接导入完毕，大文件交给 worker，或在页面点“继续导入”。
- 号码经 `PhoneIdentity` 规范化，再做哈希、加密和掩码，任何表都不存明文。去重靠 `unique_hash`（只在版本内首次出现的有效号码上设置），跨块也生效，重复行记为 `duplicate`，原因写“与第 N 行号码重复”。无效行保留，原因为“号码为空”或“号码格式无效”，原值只存掩码。
- 每个有效号码都分别按外呼和短信两个渠道过一遍 `ContactPolicy.admit`。被拒的写入 `blocked_reason`（如“外呼受限：通话黑名单”），页面上可以逐行查看并筛选。
- 版本带 `authorization_ref`，上传时可以填写，之后也可以补填。没有授权凭证的版本提交撞库会返回 409。

### 2. 撞库（`MatchAdapter`、`Matching`）
- 表：`mk_partner`、`mk_match_job`、`mk_match_request`、`mk_match_result`。
- 适配器有三种：
  - `mock`：基于号码 SHA-256 的确定性评分。生产环境默认关闭，需要 `MARKETING_MATCH_MOCK_ENABLED` 打开。
  - `ant_das`：移植自 ant-platform 的 `intention.py` / `batch_queries.py`，包括：
    - 请求参数为 `phone`（11 位大陆号码），topN 取 1–3，默认 3。
    - `parse_scores` 的校验规则保持一致：hour 为 0–23、score 不小于 0、hour 不重复、最多 24 条，按分值降序取前 topN。
    - 沿用相同的错误码表，并在 10032/10034 后退避 2 秒。
    - 每个进程共享一个限流器，默认 300 QPS，可通过 `DAS_QPS_LIMIT` 在 1–300 之间配置。
    - 状态机为 `reserved → pending → success / no_data / failed / uncertain`。其中 `reserved→pending` 用 CAS 更新，这是唯一的发送许可。pending 和 uncertain 的请求永远不会自动重发；pending 超过 5 分钟仍没有回报的，记为 `uncertain / PENDING_REVIEW`。
    - 暂停阈值与原实现一致：遇到 10001/10025/10027/10032/10034/20002 立即暂停；连续 5 次失败暂停；发送不少于 20 次且错误率达到 10% 时暂停。结果保存出错也会暂停。暂停后在页面点“继续”恢复。
    - 传输层使用 `DasGateway` 接口。`HttpDasGateway` 按官方 DAS OpenAPI 协议直接 POST 到 `/gateway.do`，使用 HmacSHA1 表单签名并校验成功响应签名，无需额外中转服务。必须显式设置 `DAS_ENABLED=true` 及密钥、产品实例和数据集，默认关闭；生产只允许 HTTPS。详见 `MARKETING-DAS-ACCEPTANCE.md`。
  - `hash_file`：导出 SHA-256 文件（大陆号码按 11 位数字计算），再导入命中结果文件（列为 sha256、hit、score、grade、labels、best_hours；没有出现在文件中的号码记为未命中）。
- 结果统一为：是否命中、分数 0–100（合作方给的 0–1 视为概率并乘以 100）、等级（A ≥80，B ≥60，C ≥40，其余为 D）、最佳时段 best_hours、标签 labels。
- 缓存：按“合作方 + 产品 + 号码”缓存 30 天（`mk_match_result`），success 和 no_data 都缓存。命中缓存的请求记为 `cached`，`charged=false`，不会再调用合作方。
- 可以导入 ant-platform 导出的结果 CSV（列为：批次ID、原始行号、手机号、推荐序号、时间（小时）、分值、查询状态、结果码、上游请求ID、本次流水）。其中“成功”和“无数据”的行转成撞库结果，并写入缓存；其他状态的行跳过，也不会重新查询。导入时可以选择关联的版本，用来关联 row_id。
- worker `DatasetWorker` 由 `MARKETING_WORKERS_ENABLED` 控制，默认关闭。单步方法有：`DatasetImports.processOne`、`Matching.step`、`SendTasks.step`、`DatasetWorker.step`。页面上的“立即执行”“继续导入”“继续发送”按钮调用的也是这些单步方法。

### 3. 分析
`GET /match-jobs/{id}/analysis` 返回以下内容：
- 状态计数、计费次数和缓存次数；
- 命中率；
- 分数分布（10 档）；
- A–D 等级计数；
- 首选时段分布（0–23 点）；
- 标签 Top 20；
- 与更早撞库任务的重合人数、重合率，以及按任务列出的重合明细。

### 4. 人群与快照（`Segments`，实现 `SnapshotMembers`）
- 表：`mk_segment`、`mk_segment_snapshot`、`mk_snapshot_member`。
- 规则可以组合以下条件：
  - 数据范围：数据包或版本、撞库任务；
  - 撞库结果：仅命中、分数区间、等级、标签（JSON_OVERLAPS）；
  - 是否包含准入受限的号码；
  - 触达历史（读 `mk_touch_event`）：近 N 天未外呼、近 N 天未发短信、近 N 天收到短信但未点击短链、排除出现过指定结果码的号码（可限定天数）。
- 支持预览人数。冻结时用一条 `INSERT…SELECT` 写入成员，`sequence` 按号码哈希排序。对照组按 `SHA2(snapshot_id + phone_hash)` 分桶，比例 0–50%，同一快照内可以复现；快照生成后不再变化。

### 5. 发送（`SnapshotChannelSender` 实现 `ChannelSender`；`SendTasks` 负责批量任务）
- 先按注入的 `ContactPolicy` 做准入检查，通过后在执行层创建短信或外呼，最后调用 `Attribution.register` 写 `mk_dispatch`。号码只在这一步解密。
- `sms`：通过 `Messaging.create` 创建消息（复用原有的幂等、授权、频次和记账逻辑），幂等键由快照、模板和号码生成。设置了 `landingUrl` 时：
  - 通过 `ObjectProvider<LinkMinter>` 生成短链，用它替换模板中的 `{{link}}` 变量或变量值里的 `{link}`，消息创建后再调用 `bindMessage`；
  - 没有 LinkMinter 实现时拒绝发送，原因为“短链模块未接入”；批量任务创建时直接返回 503。
- `call`：复用 `contact` / `campaigncontact` / `callsession` 记录：找到或创建联系人，加入 `campaigncontact`，再按 `campaign:{id}:contact:{cid}` 创建 callsession（外呼任务运行中则为 queued，否则为 created，等任务启动时接手）。按这个键重复发送是幂等的。
- 批量任务表为 `mk_send_task` 和 `mk_send_item`。可以选已有外呼任务，也可以新建：新建时调用现有的 `Campaigns.create` 接口，创建出来的是草稿，需要到“外呼任务”页面启动。任务有进度，并按原因统计被拦截的人数；对照组成员记为 `held_out`，不触达。中断后从游标继续，超过 2 分钟没有完成的认领会重新处理（幂等）。

### 新增接口（前缀 `/api/v1/marketing`）
`GET/POST /datasets`，`GET/POST /datasets/{id}/versions`（multipart），`GET /dataset-versions`，`GET /dataset-versions/{id}`，`POST /dataset-versions/{id}/process`，`PUT /dataset-versions/{id}/authorization`，`GET /dataset-versions/{id}/rows`，`GET /match/adapters`，`GET/POST /partners`，`PUT /partners/{id}`，`GET/POST /match-jobs`，`GET /match-jobs/{id}`，`POST /match-jobs/{id}/run`，`POST /match-jobs/{id}/resume`，`GET /match-jobs/{id}/analysis`，`GET /match-jobs/{id}/hash-file`，`POST /match-jobs/{id}/hit-file`，`POST /match-imports/ant-platform`，`GET/POST /segments`，`PUT /segments/{id}`，`POST /segments/preview`，`POST /segments/{id}/snapshots`，`GET /snapshots`，`GET /snapshots/{id}`，`GET /snapshots/{id}/members`，`GET /send-options`，`GET/POST /send-tasks`，`GET /send-tasks/{id}`，`GET /send-tasks/{id}/items`，`POST /send-tasks/{id}/run`，`POST /send-tasks/{id}/cancel`。

### 对公共文件和既有文件的修改
`marketing.common`、`router.js`、`i18n.js`、messaging 和 campaign 模块都没有改动，只替换了 P1 自己的两个占位页面。所有既有能力都按原样调用：`Messaging.create`、`Campaigns.create`、`Store.insert`、`Attribution`、`ContactPolicy`。

为了让 `mvn -B verify` 通过，改了三个既有测试。这些测试从底座提交（V30 和营销导航）起就已经失败，并不是 P1 引入的问题：

- `integration/test/DataLayerIT.java`：表清单原本写死为 105 张。现在按 `mk_` 前缀把营销表加入期望集合，105 张原有表仍然逐一比对。
- `integration/test/DataLayerReplayIT.java`：迁移数量原本写死为 29、11、7，当前版本写死为 "29"。现在按磁盘上 V30 及以后的迁移文件计算营销迁移数和最新版本号；数据库清单查询排除 `mk_` 表，105 张原有表的断言不变。
- `frontend/tests/client-browser.mjs`：期望的侧边栏分组中加入底座新增的“营销中台”。

修改后，P2、P3、P4 新增 `mk_` 表或 V32–V34 迁移时，不需要再改这些计数。合并时三个文件可能与其他模块冲突，以本分支的写法为准即可。

## 测试

在 `outbound-mono` 下执行：

```
mvn -B -Dtest=NONE -Dit.test='DatasetImportIT,MatchingIT,SegmentSendIT' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false verify
mvn -B verify
```

| 测试 | 覆盖内容 |
| --- | --- |
| `DatasetImportIT`（4 个） | GB18030 + 中文表头别名；地区、运营商、来源单独成列，其余进 attrs，嵌入号码被掩码；去重；无效行带原因；ContactPolicy 拦截原因逐行可见；库中无明文；暂存块已清空；带 BOM 的号码清单；新版本号递增；坐席返回 403；3 个块的导入在中断后续跑，并能跨块去重；没有授权凭证不能撞库，补填后可以 |
| `MatchingIT`（8 个） | DAS 未配置返回 503，且不生成分数；3 个 worker 并发跑同一任务时，每个号码只调用上游 1 次，参数为 phone + topN=3 + 授权凭证；分数 82、等级 A、best_hours [9,20,13]；第二个任务全部命中缓存，计费次数为 0；传输异常时状态记为 uncertain，达到阈值后暂停，异常信息不落库；恢复后只发送 reserved 的请求，uncertain 不重发；10027 立即暂停；parse_scores 的边界情况；mock 的确定性和分析结果（分布、等级、时段、重合）；缓存过期后重新计费；哈希文件导出和导入；ant-platform CSV 导入，之后的 DAS 任务只为未知号码付费 |
| `SegmentSendIT`（4 个） | 规则组合：版本、撞库任务、命中、等级、分数区间、标签、近 N 天未外呼、未点击短链、排除结果码；坐席返回 403；快照冻结、A/B 分组可复现，后续变化不影响快照；批量外呼：mk_dispatch、callsession、campaigncontact 的数量都等于“实验组 − 被拦截人数”，拦截原因为“通话黑名单”，对照组记为 held_out，重复发送幂等；批量短信写入 msg_message 和 mk_dispatch(target_type=msg_message)；没有 LinkMinter 时返回 503 / “短链模块未接入” |

结果：见下方“验证结果”。

## 验证结果

Claude 最后一轮 P1 单模块 `mvn -B verify` 于 2026-11-01 20:31 完成（日志 `scratchpad/p1/verify-full3.log`）：338 个单元测试、370 个集成测试，均无失败、错误或跳过。

Codex 已将 P1 与 P2/P3/P4 接合，补充跨模块及浏览器验收；最终结果统一见 `MARKETING-RESUME-ACCEPTANCE.md`。

## 未验证项
- 真实蚂蚁 DAS 联调与真实分值量纲仍未验证。直连协议已由本地 HTTP 模拟网关验证，不代表真实鉴权、服务权限或计费已验收。
- 除 DAS 外的其他联合建模合作方：只提供了 mock 和哈希文件交换两种方式，没有对接任何真实合作方。
- 真实短信通道和外呼线路：短信走 messaging 的沙箱模拟供应商；外呼只创建了 callsession，没有实际拨打。
- 短链：接续分支已包含 P2；个人短链的创建、消息绑定、点击归因已通过 `MarketingLifecycleIT` 联合测试。真实域名和运营商环境仍未验证。
- 准入策略：接续分支已接入 P4 的 `@Primary` ContactPolicy；联合测试验证短信后外呼间隔限制和点击后的解除。
- 并发去重：V35 通过数据库持久化凭证在同租户、同合作方、同产品、同号码范围内共享查询，pending/uncertain/failed 不自动释放重发。缓存原先 topN 较小时不会为补齐时段而自动增加付费查询。详见本次 DAS 验收记录。
- 新建联系人时，授权状态记为 CONSENTED，依据是数据包版本的授权凭证（`consented_by = marketing:{snapshot}`）；已有联系人的授权状态不会改动。
