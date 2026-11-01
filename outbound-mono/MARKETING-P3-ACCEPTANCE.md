# P3 外呼结果判定：验收记录

模块：`com.duozhi.outbound.marketing.outcome`；迁移：`V33__marketing_outcome.sql`；页面：`/admin/marketing/outcomes`（`frontend/src/pages/MarketingOutcomes.vue`）；测试：`src/test/java/com/duozhi/outbound/marketing/outcome/MarketingOutcomeIT.java`。

## 范围

| 能力 | 实现 |
| --- | --- |
| 触发 | `OutcomeWorker` 按 `callsession(updated_at,id)` 水位扫描已结束通话（走现有 `ix_callsession_updated_at` 索引），只取 `updated_at <= now - MARKETING_OUTCOME_SETTLE_SEC`（默认 60 秒）的行，等转写和通话分析落库。受 `MARKETING_WORKERS_ENABLED` 控制，默认关闭；测试直接调用 `step(limit, settle)`。扫描是跨租户的，SQL 旁有注释；每通电话随后带自己的 tenant_id 处理。水位行 `FOR UPDATE` 锁住整个批次，多实例不会扫同一窗口。同一通电话同一 attempt 已判定的，worker 不会再判。 |
| 结果存储 | `mk_call_outcome`（call_id 唯一）：三层的生效结果码和自动结果码、每层置信度、总置信度、证据 JSON、规则版本、是否计入用户（线路故障不计入）、复核状态。 |
| 触达事件 | 每层结果码通过 `TouchEvents` 写入，`source_type=callsession`，归因用 `Attribution.find(tenant, CALL, callId)`（非营销通话为 null），phone_hash 由 `PhoneIdentity` 从 `callsession.phone` 计算。来源 ID：第一次 attempt 用 `callId`，重拨用 `callId@n`；新规则版本重跑后结果变化的层写 `<来源>#v<版本>`（detail 带 `supersedes`）；人工纠正写 `<来源>#review`。 |
| 跳过 | 号码无法规范化，或 `MESSAGING_MASTER_KEY` 未配置（`PhoneIdentity.hash` 抛 503）时，写 `state=SKIPPED` 和 `skip_reason`，不写触达事件；按范围重跑会重新处理。判定异常也记为 SKIPPED，worker 继续往后走。 |
| 第 1 层 接通 | 依次看：早期媒体识别文本 → 接通后首轮像运营商提示音的转写（有效发言 ≤2 轮且未命中语音助手话术）→ SIP 码 → 挂断原因（FreeSWITCH Hangup-Cause，即 `last_error`）→ 应答时间 → 振铃时长 / 状态兜底。结果：`CALL_NO_ANSWER`（480/408、NO_ANSWER、振铃 ≥45 秒）、`CALL_BUSY`（486、USER_BUSY、“正在通话中”）、`CALL_REJECTED`（603、CALL_REJECTED、振铃 ≤3 秒即结束）、`CALL_INVALID`（404/484/410/604、UNALLOCATED_NUMBER 等、“空号”）、`CALL_POWER_OFF`（“关机/不在服务区”、SUBSCRIBER_ABSENT）、`CALL_SUSPENDED`（“停机/欠费”）、`CALL_LINE_FAIL`（5xx、网关和网络类原因，`counts_against_user=false`，接通率分母不含它）、`CALL_ANSWERED`。 |
| 第 2 层 接听质量 | 只对 `CALL_ANSWERED`。顺序：语音助手话术（前两轮客户发言）→ 语音信箱（状态 VOICEMAIL 或话术）→ 大模型语音助手判定（仅当配置了 Spring AI chat model 且规则开启，置信度 ≥0.8 才采纳）→ `ANS_REFUSE_HARD`（有效发言 <2 轮且命中拒绝话术，或通话分析为 stop_contact）→ `ANS_NOT_OWNER`（话术或通话分析为 wrong_person）→ `ANS_INSTANT_HANGUP`（接通 ≤3 秒且不是平台挂断，或开场白播完前挂断且无有效发言）→ `ANS_CONVERSATION`（≥2 轮有效发言）→ `ANS_SHORT_HANGUP`（≤10 秒，或有效发言不足 2 轮）。有效发言：最终转写、识别置信度 ≥0.5、去掉标点和语气词后至少 2 个字。 |
| 第 3 层 意向 | 只对 `ANS_CONVERSATION`。D 拒绝关键词（含拒绝话术）→ 话术节点（`callsession.flow_node_key` 命中规则里配置的节点）→ A 关键词 → `callanalysis.result_code` 映射（qualified_lead/callback_confirmed→A，interested→B，rejected→C，stop_contact→D；已人工复核的分析置信度 0.95）→ B、C 关键词 → 大模型兜底（输出等级、理由、置信度）→ 都没有就是 `INTENT_E` 并进入复核。意向置信度 <0.7 进复核；接通、接听质量置信度 <0.7 也进复核。等级定义：A 高意向，B 有兴趣，C 暂无需要，D 明确拒绝/勿扰（`OutcomeCodes.BLOCKING` 中，之后不再联系），E 无法判断。 |
| 规则 | `mk_outcome_rule`：按租户分版本，版本 0 是代码里的内置默认，不入库。草稿 → 发布；发布时旧版本归档；存储的配置合并在默认值之上。所有阈值和话术库（语音助手、语音信箱、非机主、拒绝、语气词、运营商提示音四类、意向 A-D 关键词、话术节点、通话分析映射、SIP 码和挂断原因分组）都在规则里。发布时可带日期范围重跑；也可单独按范围（可选任务）重跑，上限 `MARKETING_OUTCOME_RERUN_LIMIT`（默认 5000）通，超出返回 `truncated=true`。重跑是幂等的：结果不变就不写新触达事件；证据里记录规则版本；已人工复核的结果不会被覆盖（只更新自动结果列）。 |
| 人工纠正 | 复核队列 = `needs_review AND review_state='PENDING'`。纠正会校验各层结果码，并保证层级一致（未接通就没有第 2、3 层）；生效结果被覆盖，自动结果保留，改动写入 `mk_outcome_review`，与自动结果不同的层写一条 `#review` 触达事件。准确率按自动结果码统计：复核样本数和其中被保留的数量。纠正为语音助手的样本，可以一键把客户首句加入规则草稿的语音助手话术库，发布后生效。 |
| 页面 | 判定结果（按日期、任务、结果码、复核状态筛选，号码脱敏）、结果分布（接通率、秒挂率、语音助手占比、有效对话率、A+B 占比、三层分布）、待复核（详情抽屉里能看到依据、转写和缺失信号，可纠正、加入话术库、按当前规则重判）、判定规则（版本列表、草稿编辑、发布并重跑）、判定准确率。 |

## 新表与接口

表：`mk_call_outcome`、`mk_outcome_rule`、`mk_outcome_review`、`mk_outcome_watermark`。

接口都在 `/api/v1/marketing/outcomes` 下，都要经过 `MarketingAccess.operator`：

- `GET /meta`：三层结果码、任务列表、worker 和大模型状态、当前规则版本
- `GET /`：判定结果列表，参数 `from,to,campaign_id,code,review_state,page,size`
- `GET /stats`、`GET /review-queue`、`GET /accuracy`
- `GET /calls/{callId}`：详情、证据、转写、复核记录
- `POST /calls/{callId}/classify`：按当前规则重判单通
- `POST /calls/{callId}/review`：人工纠正，body 为 `connection_code / answer_code / intent_code / note`，不传的层保持不变
- `POST /calls/{callId}/assistant-phrase`：可选 `phrase`，默认取客户首句
- `GET /rules`、`POST /rules`（新建或更新草稿）、`PUT /rules/{version}`、`POST /rules/{version}/publish`（可选 `rerun_from / rerun_to / campaign_id`）
- `POST /rerun`：`from / to / campaign_id`

时间参数统一按 UTC，可以是 `YYYY-MM-DD` 或 `YYYY-MM-DDTHH:mm:ss`；页面会把本地日期换算成 UTC。

## 输入信号：现有系统里有什么、缺什么

| 信号 | 现状 | 处理 |
| --- | --- | --- |
| 挂断原因 | 有。FreeSWITCH `Hangup-Cause` 经 `VoiceGateway.status` 进入 `hangup_reason`，由 `CallLifecycle` 写进 `callsession.last_error`。 | 按规则中的原因分组判定。 |
| SIP 码 | **缺**。网关原来不转发 `variable_sip_term_status`。 | 在 `VoiceGateway.status` 里加了 4 行，把它作为 `sip_code` 放进状态回调 payload（会存进 `callevent.payload`）。判定器读 payload 里的 `sip_code`，或在 `last_error` 里解析 `SIP 486` 这类文本。没有时记入 `missing_signals`，回退到挂断原因和状态。 |
| 挂断方 | **缺**。 | 同样加了 `variable_sip_hangup_disposition` → `hangup_disposition`（recv_* 是对方，send_* 是平台）；payload 里的 `hangup_side` 也认。没有时按对方挂断处理，秒挂置信度从 0.95 降到 0.8。 |
| 应答时间 | 有，`callusage.answered_at`。 | 直接用。振铃时长 = 应答（或结束）− `callsession.started_at`。 |
| 首轮客户发言及时间 | 有，`speechturn`（`speaker_role=customer`，最终结果）。 | 用 `start_ms`，没有就用 `created_at − answered_at`。 |
| 开场白播完的时间 | 间接有：媒体回调写的 `callmetric.stage='media.listening'`。 | 取应答后第一条 `media.listening`（也认 `media.playback_done`、`tts.done`）；没有时“开场白未播完即挂断”这条规则不生效。 |
| 早期媒体 / 回铃音识别文本 | **缺**。网关设置了 `ignore_early_media=true`，系统不识别回铃阶段的音频。 | 预留接口：`callevent.payload.early_media_text`，或 `event_type='early_media'` 且带 `text`。另外，有些运营商会先应答再放提示音，这时 ASR 会把提示音当成客户首轮，判定器在这种情况下按运营商话术库判定。 |
| 通话分析 | 有，`callanalysis`，由 outbox 异步生成。 | 用来映射意向和识别拒绝/非本人；靠 settle 延迟等它落库。如果判定时还没有，会记入 `missing_signals`，之后可以按范围重跑。 |

## 对公共文件和现有文件的改动

- `src/main/java/com/duozhi/outbound/voice/service/VoiceGateway.java`：在 `status()` 回调 payload 里加了两个可选字段 `sip_code`、`hangup_disposition`。只增加字段，`event_id` 和去重逻辑不变，状态映射也没动。原因：第 1 层需要 SIP 码，第 2 层需要知道挂断方，现有系统拿不到这两个信号。
- 现有测试有三处改动。底座提交（V30 和“营销中台”导航分组）之后，这三处在基线上就已经失败，`mvn -B verify` 因此无法通过，与 P3 无关。为了不让每个模块各改各的计数，这里改成了通用写法：
  - `src/test/java/.../integration/test/DataLayerIT.java`：比较表清单时排除 `mk_*` 表，这些表由营销模块自己的测试校验。
  - `src/test/java/.../integration/test/DataLayerReplayIT.java`：迁移执行数和当前版本改为从 classpath 上的 `V*__*.sql` 计算，不再写死 29/11/7；表数量断言同样排除 `mk_*`。
  - `frontend/tests/client-browser.mjs`：侧边栏分组的预期值加上“营销中台”。
  其他模块合入时，如果也改了这几处，请保留其中一份。
- 没有改 `marketing.common`、`OutcomeCodes`、`router.js`、`i18n.js`，也没有改其他模块的文件。

## 测试与结果

```
mvn -B -Dtest=MarketingOutcomeIT -Dit.test=MarketingOutcomeIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false verify
mvn -B verify
```

`MarketingOutcomeIT` 共 9 个用例，用合成的 `callsession / callusage / speechturn / callmetric / callevent / callanalysis` 数据覆盖：

- 第 1 层 8 个结果码，包括 SIP、挂断原因、振铃超时、快速拒接、早期媒体提示音、应答后的提示音转写，以及线路故障不计入用户
- 第 2 层 7 个结果码，包括开场白未播完即挂断、平台挂断不算秒挂
- 大模型语音助手判定：未配置时跳过，置信度 0.92 时采纳，0.6 时不采纳
- 第 3 层 A–E：关键词、通话分析映射、话术节点规则版本、大模型兜底，以及低置信度进入复核
- 触达事件的归因（snapshot / journey member）、phone_hash，非营销通话没有 dispatch，号码无效时跳过
- worker 水位：开关关闭时不执行；扫描一次；更新时间变化后重新扫描但不重判
- 新规则版本发布后重跑会改变结果、写入 `#v` 来源事件；再次重跑没有变化；已发布版本不能编辑；阈值校验
- 人工纠正覆盖自动结果并写入 `#review` 事件；重跑不覆盖人工结果；跨层结果码报 422；按结果码统计准确率；一键加入话术库并发布后生效
- 分布统计（接通率不含线路故障）、按任务/结果码/日期筛选、详情不返回 phone_hash、坐席访问返回 403

结果（2026-11-01）：`MarketingOutcomeIT` 9/9 通过；完整 `mvn -B verify` BUILD SUCCESS，surefire 338 个、failsafe 364 个，失败 0、错误 0、跳过 0；Spotless、Prettier 和前端构建都包含在内。

## 未验证

- 真实 SIP / FreeSWITCH 数据：`sip_term_status`、`sip_hangup_disposition` 的转发只按 FreeSWITCH 变量名实现，没有在真实线路上抓过；不同运营商、网关返回的 Hangup-Cause 分布也需要用真实话单校准规则里的分组。
- 真实运营商提示音的识别：话术库是根据常见提示语写的，系统目前也不做早期媒体 ASR。“先应答再放提示音”的情况依赖 ASR 转写质量。
- 大模型准确率：只用确定性桩验证了调用链路、阈值和兜底；提示词效果、语音助手和意向判定的准确率都没有用真实样本评估。默认配置（`spring.ai.model.chat=none`）下不会调用大模型。
- 生产节奏：settle 默认 60 秒；如果通话分析的 outbox 积压超过这个时间，意向会按缺少分析来判定，需要按范围重跑来补。
- 页面只在构建层面验证过（vite build、prettier），没有在浏览器里点过一遍。
