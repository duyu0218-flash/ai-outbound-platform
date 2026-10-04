# 单机 500 软件修复：独立代码审查

日期：2026-10-04。审查者：独立子代理 `capacity_reviewer`。审查工作树：`/workspace/ai-outbound-platform-single500-fixes-20261004`。比较基线：冻结候选 `/workspace/ai-outbound-platform-single500-20261003`，Git 基线 `9fcb9f27490b2b8db9b8b3d060b085623fbd6b82`。

**静态审查结论：未发现阻断进入回归与合成软件正确性测试的 P0/P1，可以进入测试。审查者未执行测试；此结论不是测试通过或真实 500 路容量认证。软件正确性通过后可交付修复，本地硬件容量限制独立记录，不作为软件交付阻塞；仍不得把软件测试通过描述为真实 500 路商用资格。**

## 审查范围

首轮相对冻结候选读取了以下 12 项新增/修改文件的完整内容或相关差异，并沿用既有 WorkPool、lease、prepared-action、任务领取、回调 FIFO、WebHookSession、语音事件写入与网络适配器路径核对相邻行为：

- 生产应用：`backend/app/services/dispatcher.py`、`backend/app/services/ai_actions.py`。
- 后端测试：`backend/tests/test_async_ai.py`、新增 `backend/tests/test_ai_wait_completion.py`。
- 负载及观察：`scripts/load-single-host-dialogue.py`、新增 `scripts/single_host_reply_observer.py`、`scripts/fixtures/single500_instrumented_ai.py`。
- runner 与工具测试：`scripts/run-single-host-load.py`、`scripts/test_single_host_load_runner.py`、新增 `scripts/test_single_host_reply_observer.py`。
- 部署说明与隔离容器配置：`deploy/single-host-500/README.md`、`docker-compose.callback-load.yml`。

随后另静态复核 5 项 Agent 传输恢复增量：新增审查范围 `agent/app/llm.py`、新增测试 `agent/tests/test_llm_transport_retry.py`、`scripts/fixtures/single500_real_agent.py`，以及上列 `scripts/load-single-host-dialogue.py` 与 `docker-compose.callback-load.yml` 的后续差异。总审查快照为 15 项。

这些是本次相对冻结候选的差异，不能把原 HEAD 下已存在的全部候选 patch 当成本次新改动。原候选的 21 项改造已有独立审查及测试记录，本报告没有重新做整仓审计，也没有引用旧测试结果代替新改动测试。审查者只阅读文件、比较差异及计算文件摘要，没有运行 pytest、编译、Docker、负载、外部服务或 GitHub 操作，没有修改实现或旧证据。

## 生产路径结论

| 检查点 | 独立结论 |
|---|---|
| 模型完成后的提示竞态 | `_wait_for_ai` 在 liveness/current 判定后以及等待提示 prepare 排队返回后分别检查 `request.done()`，检查时已完成的模型不会再派发提示。current=false 仍返回 None；已完成的模型异常仍经 request.result() 传播，没有变成成功回复。尚未完成模型的正常等待提示保留；已经开始的提示播放不能由此撤回 |
| 新鲜状态与租约 | `_load_current_ai_call` 使用 populate_existing 的 Call SELECT，可选 FOR UPDATE；`_ai_call_matches` 检查尝试、活动状态及新鲜 scalar realtime sequence。锁等待后的 call 读及 realtime 读之后仍有执行租约检查。没有通过缓存 ORM 状态允许过期动作 |
| load/prepare 合并 | `load_action` 的 Session 在 `_prepare_ai_turn` 之前关闭；prepared 已提交重放直接返回，未提交重放仍走原动作准备。没有在模型网络等待中持有 DB 连接 |
| finish/动作准备合并 | `_finish_ai_turn(..., prepare_only=True)` 仍先保存 prepared action 并提交，再以新 Session 准备动作；后半段再次检查当前通话与 lease。动作准备失败/通话已过期时保留原条件返回行为 |
| record/finish 合并 | 常见有语音、无 hangup/短信的路径减少一次 pool queue hop，`record_speech` 与 `finish` 仍是独立事务。`finish` 再加载并锁定通话、检查 lease，decision 与 action_committed 在 WebhookSession 的外层提交中保存。并未把两个事务冒称为单一原子提交 |
| 网络边界 | 模型、speak、hangup、短信仍在有界 AI coroutine 上执行，prepare/record/finish 留在 DB 工作池。无 DB 连接跨这些网络 await；handoff 的 commit 部分只写业务状态/请求及 callback outbox |
| HTTP 未知结果 | speak 的 Timeout/NetworkError 或 `X-Voice-Outcome=unknown` 仍转为 LeaseLost，避免改发 fallback 产生重复外部动作。稳定 command_id、speech/attempt guard、prepared 结果及后续条件写路径保留 |
| 取消与排空 | 原 WorkPool shield、取消标记与已接收线程工作排空没有改动。合并单元共用该取消 lease；进入下一子事务前的 fresh/lease 检查仍能拒绝后续动作，原线程结束前不会释放该工作槽或提前结束租约 |
| 锁序与 FIFO | `_finish_ai_turn`/动作提交仍先锁 Call，再操作 conversation/realtime/子记录或 leased task；load_action 对 Task 的锁在关闭其 Session 后才进入 Call 阶段。没有跨合并边界持有 task→call 两把锁。任务 FIFO/领取逻辑、回调 FIFO、64 连接预算和生产准入门禁未在本次改变 |

新的模型/liveness 竞态测试覆盖正常完成、通话过期和模型异常；缓存测试覆盖同一 Session 中的旧 Call 与 Realtime 值；租约测试在 Call SELECT 后失效；combined replay 测试验证完整执行后的二次运行不再次调用模型、播放或追加 decision。补充的 cancel/lease 参数化测试在 record_speech 已提交后阻止 finish，检查第一事务的转录保留、decision 不提交，并验证线程排空前取消任务尚未结束；未知 HTTP 结果测试依次模拟 ReadTimeout 与 gateway 409 unknown，检查 prepared 未提交重放仍用相同 command_id、模型只请求一次且 action_committed 保持 false。审查者没有执行这些测试，不能据静态用例内容声称回归已通过。

## 回复观察与软件模式

`ReplyObserver` 只完成已登记的 `(call_id, attempt, sequence)` Future，不会为旧 attempt 或未登记 round 创建可误用的完成事件。reader 异常会保存失败类型与时间、向所有尚未完成 Future 传递 ReplyObservationError，并拒绝未来登记；已取消 Future 不会再次 set_result。后台观察失败不再仅靠清理时吞掉的 task exception 表现为静默超时。

harness 仍为每轮等待 45s，没有扩大超时。失败会计入 dialogue_errors，超时会读取该 call/attempt 的 SpeechTurn、任务 payload 与 realtime sequence，不用原观察器的 transcript/时间窗条件筛掉诊断记录；诊断读取失败也保存错误类型。原始成功回复时延及各轮数组加入 timing 文件并计算 SHA，不能用成功样本分位数替代失败请求。task timeline 和 claim/通话锁/pool 事件提供后续迟到关联；claim_started/finished 以显式 task_id 关联，pool/通话锁事件带 current_claim。

新的观察器仍使用已登记时间的最早边界查询，并在新建合成 DB、每 call 从第一轮开始的夹具中将 round 作为 sequence。它增强错误可见性，不是对历史 B1/C2 超时根因的确认。真实历史会话/额外 final speech 的业务序号不能简单等同于客户端 round；本轮后续测试需用保存的 customer/event/task sequence 核对这一假设，不能把全局 1000 task/metric 当作逐轮提交证明。

`--acceptance` 默认 capacity，显式 software 才改变隔离测试退出门槛。Compose 透传 SINGLE500_ACCEPTANCE；软件模式仍要求 correctness_passed 与 software_acceptance_passed 严格为 True，保留原任务/转录/回调总量、完整客户回复、AI 指标、失败指标及源码不变检查。观察器 failure、45s timeout、未完成/死信、数量不符或运行源码改变仍使软件失败。runner 仍将负载进程或清理的非零退出码传出，缺失字段也不能通过。

capacity_slo_passed、load_validity_passed、conversation_control_slo_passed、队列/时延和 real_sip_rtp_asr_tts_llm 的实际结果没有改为 True。软件 mode 的退出 0 仅表示本轮软件检查通过；默认 capacity 的全部门槛保留，生产拨号准入没有软件模式旁路。

## 建议落实与待测边界

初审提出的 P2 诊断清理建议已落实并复核：`scripts/load-single-host-dialogue.py:242` 起将 fixture/head HTTP、speech post 和 wait_for 纳入同一 try/finally；退出时 unregister，未完成 Future 取消，已完成且未取消的 Future 提取异常。前置 HTTP/post 错误继续传播，整次运行失败，不会因清理而变成软件通过。本轮未发现待修复的 P0/P1。

测试阶段应重点验证：

1. 新增 race/cache/read-after-lease/replay/observer/runner 用例，以及既有取消排空、网络等待不占 DB、过期通话与有界任务回归。
2. 执行新增的事务间 cancel/lease 测试及既有取消排空回归，验证后一阶段不得继续，第一阶段已经提交的记录不会被当成可回滚的整体。
3. 执行新增的 prepared 未提交、HTTP 未知结果重放测试，以及既有 owner/租约失效回归，验证不得再请求模型、改变稳定命令身份或让过期 owner 提交 decision。新增 HTTP 用例依赖现有测试夹具的 TELEPHONY_RETRY_TIMES=0，覆盖跨两次任务执行的重放；它不单独证明配置了内部 HTTP 重试时的全部路径。
4. 同一 500 合成会话的完整逐轮软件正确性，明确保留所有 timeout/观察错误和非零退出。若还有迟到，依据 task created/available/claim、同 call 前序任务及 lock acquired、回复提交/扫描时间定位，不因本地 CPU 受限而略过软件失败。

这些是后续验证要求，不是审查者声称已经执行的测试。本地性能、发压有效性及真实音频容量按实际结果单独记录；发布不得宣称未经验证的 500 路商用能力。应用或门槛再次改变时应复核新差异并更新摘要。

## 首次回归后的 fixture 增量复核

实现方报告首次完整 PostgreSQL 回归为 331 passed、3 failed：两项页面 503 正在补齐新工作树构建产物，一项线路并发测试受此前测试的活跃通话残留影响。这是实现方提供的阶段状态，本审查者没有运行该回归或独立核验完整失败日志，不将三项失败记为已解决。

已静态复核 `backend/tests/test_async_ai.py:29` 的清理增量：只操作该测试通过 tracked make_call 记录的 call_ids，在 teardown 中将存在的 Call 标为 COMPLETED，与删除这些 aggregate_id 对应 TaskOutbox 同一事务提交。保留 Call、SpeechTurn、CallEvent 审计行，且在测试主体断言之后执行，不会把主体失败改为通过。make_call 默认创建 IN_AI，生产准入 CAPACITY_STATUSES 包含 IN_AI、不包含 COMPLETED，这项修改可以消除该模块测试留下的活跃通话占额；生产准入或容量门槛未改。两份生产改动文件 SHA 与上次审查一致。

该 fixture 增量未发现 P0/P1，可以在补齐构建后以全新数据库完整重跑。重跑仍需确认三项失败全部消失；若还有失败，应继续查证，不能只凭上述静态关联断言全部原因已解决。审查者未执行测试。

## Agent 传输恢复增量复核

实现方报告软件重复测试 fixed2 失败：998 观察回复、1 dead、1 timeout，Agent 模型传输发生未处理 httpx.ReadError。该失败须保留；此处记录阶段信息，没有将恢复实现视为已经验证通过，也不只凭 Agent/model 全局计数唯一定位此前所有观察超时。

`agent/app/llm.py:17` 的 `_request_completion` 把原模型请求放入一次外层 asyncio.timeout，最多进行两次 wire attempt，只对 ConnectError、ReadError、RemoteProtocolError 重试一次。两次请求复用原 URL、headers、payload 与 token_budget，不重放业务动作。50ms backoff、配额获取及后续请求都受同一个配置截止时间约束，不在第二次重置超时。Python 3.11 最低版本允许 asyncio.timeout。HTTP 拒绝、httpx TimeoutException、invalid/空模型回复不进入这类重试；CancelledError 继续传播。

每次 wire attempt 独立 quota.acquire，成功获取后由 finally release；拒绝或取消发生在 acquire 内时，原 AccountQuota.acquire 的 BaseException 分支自行回退 inflight。release 只释放并发槽，不退回已保留的账号请求/token 预算，所以不因未知传输结果低计供应商消耗。原 429/503 Retry-After block 行为保留。本审查没有修改或重新认证整个 quota 实现。

新增 10 个参数化测试用例静态覆盖三种可重试错误后成功、持续错误的两次上限、timeout/HTTP/invalid 不重试、第二次预算拒绝、backoff 共用截止时间与 backoff 中取消。需要实际运行这些用例及原 Agent 回归；静态内容不代表通过。

合成故障仅位于 `single500_real_agent.py`，要求隔离 mock、ENV=test 和本机模型端点；开关默认 false，harness 仅对端口 18941 的一个 Agent 显式设置 true。该夹具在一次真正 post 前抛 ReadError，其他请求继续走原 client；它只能证明受控 pre-send 故障恢复，不能代替未知 post-send 失败或真实网络稳定性的证据。后续故障注入结果应明确核验 injected_errors=1、Agent transport retry=1 与完整软件数量；只打开旗标不足以证明故障执行。

harness 保留 Agent 与 Backend 分项 retry 计数并合计，capacity 的零 retry 门槛使用合计值，未放宽。software 模式仍要求 TOTAL 模型调用、TOTAL 客户回复、TOTAL 播放、TOTAL*3 回调，以及零 dead/失败和原 45s 单轮等待。合成故障恢复不得通过少计模型/回复、扩大等待或删除失败记录报成功。该 Agent 增量未改变两份 backend 生产文件。

这 5 项增量未发现阻断测试的 P0/P1，可以进行新增单元、Agent 回归与重复软件对照；测试结果待实际证据确认。软件交付与本地容量限制继续独立记录，真实 500 路容量尚未获认证。

## 提示准备等待期间完成模型的增量复核

实现方报告 Agent 修复版 fixed3/fixed4 均观察到 1000/1000 回复、软件检查通过，fixed3 自然发生的一次 ReadError 经 Agent 恢复，fixed4 仍有 30 次提示。本审查者尚未核对这些最终原始报告，也不将它们当作之后 dispatcher 增量已通过的证据。

已只读复核 `backend/app/services/dispatcher.py:569` 的新差异：等待提示先按原 execute_action 的 prepare 函数及 call/attempt 参数执行 pool.run，之后再次检查 request.done；模型已完成则 break，经原 request.result 返回或传播模型异常。仅在模型仍未完成且 prepare 返回有效 snapshot 时执行原 execute_prepared_action，durable=False 保留。prepare 的 fresh Call/sequence、执行租约及 Session 关闭行为沿用，实际网络前的执行 lease 与稳定 HTTP guard 不变；取消或 LeaseLost 仍进入外层 finally 取消并排空模型。该检查消除了 prepare 排队已经跨过模型完成后继续启动提示的窗口，不保证撤回已经开始的播放。

`test_ai_wait_completion.py` 新增正常结果/模型异常两种参数化情形，在模拟 prepare 返回前等待模型结束，断言无提示 dispatch 且结果/异常不被吞掉；原三个 liveness 完成竞态用例同时监控 execute_action 与 execute_prepared_action，未因改了调用函数而失去检查。新增两项尚待执行，生产 ai_actions 文件摘要未改变。

该两文件增量未发现 P0/P1，可以进入测试。应以最终 dispatcher 摘要完整执行 fresh PostgreSQL 回归、500 合成软件重复与受控注入；保留失败记录、逐轮数量和实际 capacity/load_validity 结果，之后再独立核对可重算原始证据。审查者仍未执行测试，真实音频 500 路资格未验证。

## 审查快照 SHA-256

```text
89433404a72b77fff39f32c6894af1ece2427fa27d8bcbb387ddccbd3b06e173  backend/app/services/dispatcher.py
3d05c4aec3e2f9a83a46539f3ac35de6670ef0ca6fea6592a5ff996611c3937f  backend/app/services/ai_actions.py
f81c7e8811193bf2b8e9116888fe4c6cd5cbd4510e68f53a6f3006445f01c1d4  backend/tests/test_async_ai.py
d92ab55c43e04e0f90ec50fac8e385f189dd611dafd550d8b44fad581609e394  backend/tests/test_ai_wait_completion.py
74c34e959352efd64ce8c284025faf4d3c06d655efb8bb8a634938d131454ce9  scripts/single_host_reply_observer.py
0acd7a661c6098542c40feb1a35002c983d2d6342ac404b48ceef3a95fd41589  scripts/test_single_host_reply_observer.py
2b29a9ac10d5f7aa80687e4c99aaf73a6c9fa5e1e4a41ab7c0b9b4d8031d1dab  scripts/load-single-host-dialogue.py
ef281c42a0cc9dccb0e8f7f6a987ab3269c9e05613371e5d91504e66e1b46a12  scripts/run-single-host-load.py
edc68ff589eed5ba97af85faf1bca99e03d6a723d4064c6e5e9c02dec34989f1  scripts/test_single_host_load_runner.py
00f23a1c70a0f71fa0093d6962f82b5b1b0d713e1fd5ed902f3e12084f7b271f  scripts/fixtures/single500_instrumented_ai.py
fe8a569ec7bdb847eb69880756a2a35858f41d331afe27b8adc41bed0db4da88  docker-compose.callback-load.yml
5f00a2caeac1d4c79844a582f3cdf87083a003472f055b45a2ddb44f712621a0  deploy/single-host-500/README.md
3540f5c3536f3875a728076fb2d3fcf21563d9228b3c3453a368b7b3465600e5  agent/app/llm.py
5be17f20b24e7fcdfe934a0d1ade8a96ff04ab6c410877b9f2bff2f6734d292a  agent/tests/test_llm_transport_retry.py
bbadc2d21946d46283ffea3bb8d6792e5392ee76dd8779e16b20427798b88c70  scripts/fixtures/single500_real_agent.py
```
