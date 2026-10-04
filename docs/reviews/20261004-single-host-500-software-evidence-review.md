# 单机 500 软件交付：独立证据审查

日期：2026-10-04。审查者：独立子代理 `capacity_reviewer`。工作树：`/workspace/ai-outbound-platform-single500-fixes-20261004`。Git 基线：`9fcb9f27490b2b8db9b8b3d060b085623fbd6b82`。

**结论：最终软件交付证据审查通过，未发现 P0/P1 阻断，可以按用户已明确授权的“本地硬件限制不阻塞软件交付”口径同步源码。真实 500 路容量、发压有效性和时延 SLO 均未通过，不能据此宣称真实 SIP/RTP/ASR/TTS 的 500 路商用资格。**

本报告补充已冻结的 [独立代码审查](20261004-single-host-500-software-independent-review.md)，没有改写其静态审查阶段状态。审查者没有执行生产、回归或负载测试，没有操作 GitHub；本轮只读取证据、核验文件摘要及执行纯数据复算，唯一新增仓库文件是本报告。

## 范围及完整性

读取 [结果报告](20261004-single-host-500-software-results.md)、证据目录中的 manifest、comparison、run-index、runner-results、测试日志、analyze.py、原始归档与来源清单，并核对来源工作树实际文件。

独立核验结果：

- manifest 中 115 项运行源码、30 项改动代码、4 项既有文档和 14 项证据摘要全部与实际文件匹配；新增的 runner-results 叶项也匹配。代码审查报告的 15 项快照逐项匹配，原代码审查报告 SHA 保持不变。
- 最终 fixed5、fixed6、fault7 三组 source_sha256 与 manifest 的同一 115 项源码完全相同，runtime_source_unchanged_during_test=true。比较基线 baseline2 与三组最终运行的六项共同观测/负载文件摘要、依赖和进程拓扑一致。
- 归档包含九轮、每轮结果/原始时延数组/AI 事件流三项，共 27 个唯一叶项，没有缺失或重复成员；每项压缩字节与展开 raw 文件相同。逐轮解压后，结果及数组与来源文件和证据目录副本逐字节一致；AI 事件流与原四个 worker 文件的无修改拼接一致。来源清单的 54 份原文件大小及 SHA 全部匹配。
- 审查 analyze.py 后执行纯数据复算，直接读取展开 raw 与在临时目录仅使用归档两种方式都逐字节重现 comparison.json。审查者还用独立读取脚本重算九轮全体成功回复与各轮 P99，检查样本有限且非负、样本数量及 timing SHA，没有依赖分析器的成功标志代替核验。

本次核验 manifest 的 SHA 是追加本报告前的快照。实现方后续登记本报告只能追加文档叶项及刷新相应状态；本报告核验的原始/源码叶项应保持不变。

## 回归日志

| 日志 | 独立读取结果 |
|---|---|
| backend-postgres-approved-final.txt | 338 passed、1 skipped；唯一跳过为需要独立 Redis 的架构续期/互斥用例 |
| redis-lease-final.txt | 单独补跑 1 passed；与上项按实现方指定的补跑用例合计 339 项 |
| agent-regression-final.txt | 25 passed |
| tools-regression-final.txt | 33 passed |
| backend-sqlite-focused.txt | 定向 32 passed、1 skipped；Redis 覆盖由后续环境补齐 |

首次完整 PostgreSQL 的 331 passed、3 failed、3 skipped 日志仍保存，三项失败名称与此前页面构建/活跃通话残留的调查一致；最终完整日志不再出现这些失败。没有删除失败日志或把初始运行当作最终通过。前端构建来源另有记录，结果报告明确未重新执行未修改的前端、网关或录音全套测试；本审查不追加这些未执行的通过项。

## 最终三组软件正确性

每组原始任务清单有 1000 个唯一 task_id、500 个 call_id，每个 call 的 task sequence 恰为 1、2；任务全部 completed、action_committed=true、attempts=1。每组原始事件流各有 1000 次 claim_started 和 claim_finished，唯一 task_id 集合与最终任务清单一致，没有靠全局完成计数忽略缺失任务。

每组实际成功观察回复及原始时延样本均为 1000，两轮各 500；模型、播放、AI 成功指标和 AI 转录各 1000，媒体回调 2000，总回调处理 3000。pending/dead、失败指标、conversation_errors 和观察器 failure 均为零，等待提示为零。既有代码审查确认观察器按已登记 call/attempt/sequence Future 完成，因此这些逐轮观察不能仅由后台任务计数代替。

| 最终轮次 | 完整观察回复 | 原始数组复算回复 P99 | 等待提示 | Agent / Backend 重试 | 实际注入 | 软件结果 |
|---|---:|---:|---:|---:|---:|---|
| fixed5 | 1000/1000 | 29.659541 s | 0 | 0 / 0 | 0 | 通过 |
| fixed6 | 1000/1000 | 27.571341 s | 0 | 0 / 0 | 0 | 通过 |
| fault7 | 1000/1000 | 30.936027 s | 0 | 3 / 0 | 1 | 通过 |

三个最终 runner 均 process_exit=0、cleanup_exit=0、acceptance_mode=software；software_acceptance_passed 与 correctness_passed 均严格为 true。任务、客户等待和回调数量门槛没有放宽，45s 单轮等待上限保留。

fault7 的两份 Agent 进程日志共记录三个 ReadError 重试，夹具统计仅注入一个发送前 ReadError，所以另有两个自然传输错误；Backend 重试为零。三个错误都恢复到完整业务结果，且不产生任务重试或失败指标。此前 fixed3 的自然 ReadError 重试一次、注入为零也能从进程日志及统计对应。此证据只支持这些已观察的模型传输恢复，不支持任意故障、未知外部业务动作或真实网络稳定性的保证。

## 对照及失败记录

baseline2 全体 1000 个成功回复的 P99 为 31.950508 s，提示 412 次；最终两次正常轮 P99 相对改善分别为 7.170362% 和 13.706095%，与结果报告一致，只适用于这些轮次。基线常规及提示 DB 调度 8236 次，最终每组 4000 次；这些计数不含全部共享 liveness/claim 工作，也不能把各阶段 P99 相加或宣称所有阶段尾延迟下降。

failed_before_agent_retry 的 fixed2 原始结果仍在索引、归档、来源清单及比较输出中：成功观察回复 998，任务 998 completed/1 dead，5 项失败指标，1 次客户观察错误，模型 998、提示 1，software=false；只有 499 个 call 有完整任务序号 1/2。其 27.134067 s P99 分母是 998 个成功样本，不能描述为完整 1000 次成功或拿来支持最终通过。结果报告没有掩盖该失败，也没有将此前中间版本作为最终同源码重复证据。

## 容量边界及同步结论

九轮 capacity_slo_passed、load_validity_passed、conversation_control_slo_passed 均为 false；三组最终结果的 real_sip_rtp_asr_tts_llm 与 effective_dialogue_capacity_verified 也为 false。500 个合成活跃通话记录、每 call 两轮、受本地资源及回复等待影响的发压，不是持续 125 轮/秒的有效容量测试或真实 500 路音频证书。结果报告保留这些限制，默认 capacity 模式及生产拨号门禁没有被软件退出模式绕过。

按用户后续明确要求，以上容量失败独立记录，不阻塞本次已通过独立代码审查、回归和软件正确性的源码交付；同步的表述应是“软件改造及软件验收通过”，真实目标机 500 路仍需要容量、音频和长稳测试。该结论是技术审查结论，不代表本审查者已经提交、推送、合并或更新原工作树。

## 本次证据快照 SHA-256

```text
68805882b9e680bf390626306d4bb515bfc6c5f69e9accf4b497d48840ec690b  既有独立代码审查报告
5b05eedbe5ca7234c309c3f646d0547471aeca420c0d4874a85036de92159974  软件结果报告
215071a8b364878565261c702a20cdbc96095ea3ad8bf802bf6a077fa52a8acd  manifest.json（追加本报告前）
86ce577bd0a1975b4b76962b68831858907be746199b15b71f3d08bd8741e6c7  analyze.py
f8c2609454a0950f8725c1dc62784ef4e24b89089b6b120649ffd2795cdbfa3e  comparison.json（两种复算逐字节一致）
31a94c85068a00ba09312745ab7c77629c457479aeb8e6ddb518f16c1c5c903a  raw-evidence.tar.gz
307576018b0431dc58302fa70eb6ade325fedc450ffb3e44e0b33b9b6e07d4c7  runner-results.json
```
