# 单机 500 并发独立代码审查

审查日期：2026-10-03。审查者：独立审查子代理 `capacity_reviewer`。工作副本：`/workspace/ai-outbound-platform-single500-20261003`。基线提交：`9fcb9f27490b2b8db9b8b3d060b085623fbd6b82`。

**结论：本次差异及测试阶段新增的诊断工具改动通过独立代码审查，未发现剩余 P0/P1。独立审查者未执行测试；最终回归证据包含工具 24 项通过及 12 进程/500 媒体控制会话通过，但新版 mixed10 与 dialogue2 控制负载均失败。容量验收仍未通过，不批准更新 GitHub 或原本地工作树。**

## 审查范围与方法

首轮只读检查了相对基线的全部 15 个已跟踪文件差异，以及 5 个新增文件：`ai_capacity.py`、`test_ai_capacity.py`、`single_host_load_validity.py`、`test_single_host_load_validity.py` 和架构设计文档。测试阶段再次复核测试 fixture、设计文档的队列口径，以及负载有效性、对话计时样本和媒体验收夹具的最终改动。最终范围为 16 个已跟踪文件差异及 5 个新增文件，共 21 个；完整文件清单及最终 SHA-256 见文末。

另外读取了已有的全局准入、网关持久账本、Inbox/FIFO、AI 任务领取和租约、AI 动作、媒体归属及验收脚本，用于核对新代码与现有路径的关系。审查覆盖实际拨号门禁、Redis TTL/代次/退出/领取失败、AI 队列查询、连接预算和负载/runner 的通过条件。未宣称对整个仓库进行了全量重新审计。

本轮进行了源代码、配置、断言、文档和已有测试证据阅读，以及版本差异、文件摘要和保存的计时样本的独立算术复核；未运行 pytest、负载脚本、容器、真实拨号或云模型请求，未修改应用源码，未操作 GitHub。唯一写入的交付物为本报告。测试通过判断依据执行代理保存的完整日志，不把代码阅读视为测试执行。

## 已修复的阻断项

| 编号 | 原问题 | 最终代码与复核结论 |
|---|---|---|
| P1-01 | Agent 就绪不能证明 AI 执行进程存活；四个 AI worker 全停时仍可能接通客户 | 新增 `ai_capacity.py:101` 固定身份读取与校验，在 `call_service.py:274` 的真实领取路径执行，缺 worker、代次/槽位不匹配、过期、损坏数据和 Redis 错误均拒绝新准入 |
| P1-02 | mixed 场景只看最终完成量，发压器落后仍可能报告容量 SLO 通过 | 新增 `single_host_load_validity.py:11` 要求完整样本、实际 ACK 时间、95% 速率比、调度 P99 ≤100ms、计划至 ACK P99 ≤1s 及逐 10s 窗口；`load-single-host-dialogue.py:350` 和 `run-single-host-load.py:68` 均将该门槛纳入成功条件 |
| P1-03 | 首轮改造仅在数据库锁前检查健康，等待全局/租户/通话锁超过 TTL 后仍可拨号 | 审查中提出并已修复。`workers_valid_until` 返回所有指定 worker 中最早的单调截止；`call_service.py:413` 在 DIALING 更新前、`call_service.py:453` 在提交前复核，过期即回滚；新回归断言 candidate 仍为 QUEUED 且 attempts=0 |

P1-03 首轮复核依据为源代码与新回归断言阅读。后续 PostgreSQL 专项日志显示此用例通过：实际领取路径获得数据库准入锁后，夹具人为延迟使捕获资格过期，断言仍为 QUEUED 且 attempts=0。这是锁内延迟回归证据，未据此宣称已完成多连接竞争或目标机故障负载验收。

## 重点复核结果

| 范围 | 审查结果与实际边界 |
|---|---|
| 实际准入位置 | 保护位于 `_claim_dispatch_slot`，可覆盖正常调度与人工请求最终进入的持久拨号领取路径。Redis I/O 在全局数据库锁之外；锁等待不会无限延长捕获的健康资格。门禁失败不会改变既有呼叫状态或释放未知 PBX 名额 |
| 身份和配置 | 单机模板要求 4 个固定身份各 160 槽位，共 640；按固定键 MGET，不通过 SCAN 汇总其他进程。JSON 重复身份、非法 ID、布尔/越界槽位拒绝。空要求保留其他开发档兼容 |
| TTL 与代次 | owner 以 SET NX EX 取得；PULSE 与 WITHDRAW 的 Lua 均核对 epoch。旧进程不能覆盖或删除新进程状态；owner 或 state 缺失均不允许拨号。心跳与本地文件不是通话所有权账本 |
| 初始化、失败、退出 | 初始化完成并成功领取轮询后才 ready；领取异常即时安排 ready=false；共享发布失败退出领取循环。优雅退出先撤回 state，再等待已接受任务结束，最后按本代次释放 owner。崩溃仍存在 TTL 发现窗口，文档明确不保证瞬时停止全部新呼叫 |
| AI 队列压力 | 查询仅取一条超龄记录，排除未来 available_at、完成/死信及已耗尽次数；过期 PROCESSING 按租约恢复时刻计算等待。复用现有 TaskOutbox 索引和当前 ORM session。具体 FIFO 后继口径见下方 P2 |
| 持久性和顺序 | 本次未改变平台容量锁、网关持久 intent、FULL 回调提交、Inbox 分区锁/同通话 FIFO、任务 fencing 或现有未知结果保留规则；仍须运行相关回归 |
| 连接与进程预算 | 模板 DB 池及 overflow 未增大：API 6×5 + Inbox 6×1 + AI 4×5 + 后台 8 = 64。心跳使用 Redis，不新增 DB 池、迁移或服务。31.5 CPU 和 52.75 GiB 模板上限继续只是静态预算，本轮未重新渲染或测量 |
| 负载证据 | first HTTP 200 只对首次观察的 speech 身份计样本，重试不重复增加 ACK 数；负载有效性与正确性、延迟分开。报告继续明确 synthetic、模拟播放和真实 SIP/RTP/ASR/TTS/LLM 未验证 |
| runner | 容器退出 0 仍必须有报告，所有必需字段须严格等于 true；conversation 另需对话控制 SLO。新标签、既有项目检查、限定 evidence 目录及仅清理本次项目的边界保留 |

## 非阻断项与测试建议

**P2：队列指标口径已在设计文档澄清。** `ai_capacity.py:141` 统计已到期的 PENDING/FAILED，而 `task_queue.py:469` 的领取器还要求该通话没有更早的未完成任务。因此健康 PROCESSING 或未来 retry head 后面的 FIFO 后继也可能触发全局暂停。最终设计文档已明确统计“已到期待执行”任务，包括被前序任务阻塞的后继，属于保守保护，并非只统计此刻可领取的队首。后续可结合实测决定是否复用 no_earlier 条件；本轮没有以减少暂停为由放宽门禁。

测试阶段建议保留逐事件计划/ACK 时刻和环境/源码摘要，方便复算有效负载的分母与窗口；汇总 P99 和最终排空不能替代原始容量证据。

应实际执行以下检查后再报告测试通过：

- SQLite 与 PostgreSQL 下真实领取门禁：worker 缺失/恢复、Redis 故障、锁等待跨 TTL、队列超龄、已有呼叫不受门禁回滚影响，以及第 501 路拒绝。
- 用隔离 Redis 执行 `test_real_redis_generation_fencing_withdrawal_and_ttl`；须设置 `SINGLE500_REDIS_TEST_URL`，不能将该项被 skip 视为通过。
- AI 初始化/领取异常、SIGTERM 排空、SIGKILL 后 TTL 到期、代次替换；确认回调继续持久接收和现有任务租约继续运作。
- 模板渲染、4 个身份/640 槽位/64 DB 连接、命名空间一致，以及原有 Inbox FIFO、批回调、Agent 配额、网关/录音回归。
- 人为放慢发压器、缺样本、低到达率和失败报告：负载脚本与 runner 均必须非零退出；正常负载按真实记录验证，无论最终是否排空。
- 目标 32 vCPU /64 GiB 单机及独立发压机上的真实 SIP/RTP、云语音/模型批准额度、首音/打断、录音、桥接与 8/24 小时长稳。

## 容量资格与发布状态

本次批准仅针对文末摘要对应代码进入测试。历史报告、合成 500 会话、640 个任务槽位和静态资源预算均不能证明真实 500 路容量。目标机、真实媒体/云服务、长稳及故障恢复未通过之前，应继续保持 GitHub 和原本地工作树不更新。

后续如发生应用源代码或通过条件变更，应重新审查相关差异，并更新代码摘要；测试证据必须关联最终被测试的版本。

## 测试阶段证据复核补充

本补充为独立审查者读取执行代理保存的日志与 JSON 报告；独立审查者本人仍未执行测试。常规回归与容量验收分别判断。

### 测试 fixture 修正

已复核 `backend/tests/test_ai_capacity.py` 的两项修正：使用提交前保存的 `task_ids` 查找 ORM 对象，避免提交后对象失效；先导入既有 `test_production_hardening` 的测试环境，再导入应用服务，避免缓存开发环境配置。修正仅影响测试装配，没有修改应用门禁或放宽断言。

### 已读取的回归证据

日志记录 SQLite 后端 312 passed/16 skipped、PostgreSQL 后端 326 passed/2 skipped、PostgreSQL 专项 2 passed、网关 250 passed、录音 14 passed、前端 11 passed/构建完成，以及工具 23 passed。静态预算报告明确为 64 个应用 DB 连接、640 AI 槽位、600 媒体槽位，`real_500_call_capacity_verified=false`。

初次 `agent.txt` 只有 3 个进度点，执行代理终止该进程后重新运行；已读取 `agent-final.txt` 的逐项结果与完整结束行，明确为 **15 passed in 1.08s**，不是先前口头提及的 14 项。

已读取 `ai-capacity-postgres-redis-fresh.txt`，完整结果为 **15 passed in 2.15s**，其中真实 Redis 代次/TTL、退出撤回，以及实际准入锁内资格过期均明确 PASSED，没有 skip。该证据来自新建专用测试 DB。旧 DB 重跑的 `ai-capacity-postgres-reused-db-failed.txt` 明确为 2 failed/13 passed：恢复准入和清除本用例积压后放行失败；执行代理说明该 DB 保留了此前全套测试状态。旧失败日志保留，新 DB 通过没有将旧失败删去或改写为通过；现有日志也不足以宣称此专项在任意脏 DB 下可重复独立运行。应用运行时代码摘要未改变。

全量 PostgreSQL 的 2 项 skip 未在全量日志中列明原因；上述专用日志提供了真实 Redis 关键项单独执行的明确证据。已核对可选产品专项的 `PRODUCT_TEST_DATABASE_URL` 跳过条件及其专用日志 2 passed，但未把全量日志缺失的逐项 skip 信息补成实际记录。初版工具 23 passed 与最终 `tools-final.txt` 的 **24 passed in 6.29s** 分开保留；最终日志对应新增负计时断言后的版本。

### 已确认的负载失败

| 场景 | 正确性与实际负载 | 延迟/积压证据 | 结论 |
|---|---|---|---|
| mixed，计划 600 回调事件/s，30s | 6000 终句在 30.029s 生成，约 199.81 终句/s；最终对账未完成 | Inbox 剩余 7737、最老 116.71s；网关最大投递年龄 113.60s；503 发生 5647 次；模型传输重试 2 次 | 正确性和容量 SLO 均失败 |
| conversation，500 合成会话、3 轮，计划 125 轮/s | 1500/1500 回复与 4500 事件最终完整对账；实际生成仅约 21.21 终句/s | 回复 P99 31.247s；1500 个轮次全部错过截止；Inbox 最大完成延迟 4.181s；网关最大投递年龄 1.773s | 对账通过，对话、延迟与计划负载失败 |

两份 `load_validity` 只有“missing or invalid load samples”，缺少完整样本与分类计数，不能仅据这句话认定 uvloop 提前计时是唯一原因。现有独立延迟和积压数据已经证明上述运行未通过；改进无效样本的诊断不得将旧运行改写为容量通过。

此前要求补充原始 signed lag、计划/首次 ACK、数量、最小值、非有限和提前计数，并在等待绝对计划时刻后再次检查单调时钟。最终代码完成以下改动，先通过复审后由执行代理测试；最终工具测试与短负载证据已补充，旧容量失败仍有效。

### 最终诊断工具复审

| 文件与位置 | 复审结论 | 最终验证证据与边界 |
|---|---|---|
| `single_host_load_validity.py:11` | 无效样本明确返回数量、最小 signed lag、负 lag/非有限样本/ACK 提前计数及 `failure_reasons`；负数仍导致失败，没有截断或容差放行。有效样本继续强制 95% 速率、100ms 调度 P99、1s ACK P99 及逐窗口门槛 | 最终工具 24 passed；新版负载明确列出速率、ACK 延迟和窗口失败 |
| `test_single_host_load_validity.py:38` | 新断言要求 -.01ms 原样保留、负值计数为 1、分类原因存在且无法通过；已有慢发压、缺样本、ACK 提前等断言保留 | 最终工具 24 passed，没有以此前 23 passed 代替 |
| `load-single-host-dialogue.py:204` | conversation 与 mixed 均使用单调时钟循环等待绝对计划时刻，提前唤醒会继续等待；采样仍按实际时间减计划时间，没有改写测量值 | 两份新版 uvloop 运行的负 lag、提前 ACK 和非有限样本计数均为 0；其他失败门槛正常生效 |
| `load-single-host-dialogue.py:354` | 完整运行在最终 SLO 判定前保存原始 generator lag 与计划/首次 ACK 样本，并将相对路径和 SHA-256 写入报告；SLO 失败仍有原始样本。启动期异常不宣称存在完整负载样本 | 两份新版样本数量与 SHA 匹配；独立算术复核结果逐字段一致 |
| `load-node200-media-processes.py:64` | 发压前要求全部配置 worker 健康；保留总数和分布差≤1断言，新增 owner 数等于 calls 且 terminated 数为 0。仅改验收夹具，未修改运行时媒体代码 | 12 进程/500 控制会话通过；owner、remote count 和分布均一致，无真实 SIP/audio |
| `load-node200-media-processes.py:118` | 失败时保存阶段、worker epoch/健康/会话与 owner 数、脚本摘要和 worker 日志，再进入资源清理；失败继续抛出。成功与失败均明确 `real_sip_audio_asr_tts=false` | 新版成功报告与脚本摘要匹配，两次旧失败日志保留；未另作新失败快照路径的故障注入 |

上述最终改动先通过独立复审，再执行测试。最终工具回归通过，新负载继续正确判为失败；没有通过诊断修正改写旧容量失败。

### 媒体 500 控制会话验收问题

初次 `media-500-control.txt` 只证明组合断言“总数=500 且分布差≤1”失败，未输出两个条件的各自结果。第二次 `media-500-diagnostic.txt` 在创建阶段因没有健康进程空闲槽位失败。两次记录均应保留。

夹具使用 `media_allow_degraded_admission=True`，却以 `manager.ready()` 作为发压起点；该方法在至少一个进程健康时便为 true。因而 12 个进程逐步启动时可能过早发压，造成无可用槽位或偏斜分布。它是具体、可检查的夹具问题；现有日志不足以证明运行时在全部进程就绪后仍无法建立 500 会话。

最终夹具增加全部进程健康等待、owner/terminated 校验和失败快照，经复审后重测通过。已读取 `media-500-control-final.json`：12 个 worker 启动时全部健康；500 个 owner、0 个 terminated、远端总数 500、每进程 41/42 路，创建用时 1.040s；600 资源槽位满后拒绝下一路，账本恢复通过，worker 重启影响其 42 路并建立 42 个替代会话。脚本 SHA 与审查摘要匹配。没有修改媒体运行时或放宽每进程容量，报告明确 `real_sip_audio_asr_tts=false`；平台客户上限 500 与媒体资源上限 600 分别计量。该通过只证明 RPC 控制会话，不能证明 SIP/RTP/ASR/TTS 音频容量。

### 最终短负载与原始样本复核

已核对 `20261003-single-host-500-acceptance.md` 的关键数字和边界，未发现将短负载或媒体控制通过写成真实 500 路容量通过的问题。

| 新版场景 | 独立复核的证据 | 判定 |
|---|---|---|
| mixed10：500 合成会话、600 事件/s、10s | 2000 个 generator 与首次 ACK 样本齐全；调度 P99 24.524ms；ACK 时段 22.958s，约 87.114 终句/s，速率比 0.43557；计划至 ACK P99 12.835s，窗口内 961/2000 按时 ACK；Inbox 最大完成 48.231s；最终任务 1994 completed、2 pending、4 processing | 正确性、容量 SLO、负载有效性均 false；该短测不能替代持续或长稳验收 |
| dialogue2：500 合成会话、计划 125 轮/s、每会话 2 轮 | 1000 回复与 3000 事件最终对账；1000 个 generator/首次 ACK 样本齐全，实际 ACK 约 28.918 终句/s，速率比 0.23135；调度 P99 26.606s、计划至 ACK P99 26.743s；回复 P99 30.543s 高于 4.2s，1000 轮全部错过截止；Inbox 最大完成 3.999s | 正确性 true；容量 SLO、对话控制 SLO、负载有效性均 false |

两份新版原始样本的负 lag、非有限样本、提前 ACK 和负计划时间计数均为 0，失败原因已经明确是速率、延迟及窗口，而不是泛化的无效样本错误。两份 runner 原始输出均为 **process_exit=1 / cleanup_exit=0**，没有被最终排空或工具回归通过覆盖。

已读取 `timing-recomputation.json`，并仅用标准库对保存的原始数组重新计算数量、最小 signed lag、P99、实际 ACK 时段/速率比及逐窗口结果，与报告 `load_validity` 所有字段完全一致；该动作是静态证据算术复核，没有运行项目测试或负载。两份原始数组文件 SHA 均与报告匹配，每份运行报告的全部 114 项源码摘要也与当前文件匹配。文末 21 个审查文件摘要再次全部匹配，应用和工具代码没有再次改变。

已读取 `resource-cleanup.json`：记录本次 PostgreSQL/Redis 回归容器已移除，四个本次 Compose project 的容器、网络和卷均无剩余，测试镜像保留供复现。本次复核没有再执行容器清理命令。原本地目录只读状态检查为干净的 `work` 分支，HEAD 仍为基线；`sync-state.json` 记录未提交、推送、创建 PR、合并或同步原目录。本审查未访问 GitHub，也未将历史日志删除或重新签为通过。

**最终结论：代码独立审查通过，最终工具回归和媒体控制会话通过；新版与旧版控制负载均未通过容量验收。4 vCPU/16 GiB 执行机结果不能外推为 32 vCPU/64 GiB 真实 500 路资格，媒体控制与模拟对话也不能拼成真实端到端语音证据；GitHub 与原本地工作树继续不更新。目标主机、真实线路/云服务、1200 事件/s 突发、真实逐档测试及 8h/24h 长稳仍未执行。**


## 最终复审版本 SHA-256

以下 21 个文件为本次独立审查通过后进入最终测试的版本，最终证据复核时摘要再次全部匹配。此前回归和失败负载按各自运行时版本解释；诊断增强后的工具回归及新版短负载有独立证据，不自动继承历史通过结果。

```text
3c9b31470683b2e35c62efc36142e73a1e8966e24bc3dc0cc37993655a7b6fa3  .env.example
a396b91d3a63758b6fbec13aa6236865360cdc0e7d30bc63a15af2c2d7a0c593  backend/app/config.py
4cd78eaadd06128a39575656f5624ac4a4a137a3614163fea68caad18d3575c1  backend/app/services/async_ai.py
52860713dbfa9b27e9b98e1200eeb4f906b2dadbbabdce00d6d6851c0b70c556  backend/app/services/call_service.py
9d58a1940907e9c9c52236206f8e06133f8201641db80f1de7cac8c637eb3867  backend/app/services/metrics.py
c21cca401e3aae11426b592a70f73ab6fa5d10bcf4639985d0d71ce3b0f90800  deploy/prometheus/alerts.yml
009b1bc146846f47d62c7a9123cf93ba58d288baf24fc189cea7239534fe2bed  deploy/single-host-500/README.md
975b147906e56459b341df2864da84092b7280e6c36f10af764aa6b4e8b402df  deploy/single-host-500/host.env.example
af9e6d00365f011645cc74c54ddf3314876d053410b4d2a97231a94da8d6a28e  docker-compose.callback-load.yml
3e3f63c5f5d83ceef3440e1a105c38b54854692baa494e6519dc3c749ece526f  docker-compose.single-host-500.yml
0ed2ae0358f61b38c87dab4aef2210f4c51fb14d99da64e2f1e537e089648840  scripts/check-single-host-500.py
103433a76b69aeff82547d8a36cc685afb62b87e81614442a281c5caadb7cb22  scripts/load-node200-media-processes.py
9f34311b00d172aacdcbf4d97a30a0581b86c602e309ce570f1e4c6eaa1c5b54  scripts/load-single-host-dialogue.py
17ef9b4fa061760e3f692e1f37d81f56bb29056c7c0361cda581c4a74b1b35d9  scripts/run-single-host-load.py
3cb8d003661b18c65b227efbd199d270dabb5b869de855d91cbf561b14fbcac4  scripts/test_single_host_load_runner.py
2e834cb3a645429c32174d286cc3fc72c4a7c01e43fe2a292459d5edcb2787d5  scripts/test_single_host_profile.py
b5a843ba521e40ed2f221ba900339e4675a57e17bca38e000e164f702adaec2f  backend/app/services/ai_capacity.py
d50b9cdeda439479edcfa74a97a3b5424f5d6d6cf0e9b6f81f734dde169b139a  backend/tests/test_ai_capacity.py
dbc72359eeb4012cbaefcf679c2a6d33a4d313a9e67c350c45c7914884607156  docs/reviews/20261003-single-host-500-design.md
d56d0a559a2a8a363733b77e9399db5b457157b233130a9d936ab210acd8abeb  scripts/single_host_load_validity.py
0ecf58137516f9a50ad8bd181fd027a3b3a13f6735c0814625647a70ae052ea6  scripts/test_single_host_load_validity.py
```
