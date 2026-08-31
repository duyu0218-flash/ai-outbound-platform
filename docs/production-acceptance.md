# 生产发布验收门禁

以下状态必须分开记录：源代码与 CI 通过、测试环境发布、真实线路验收、真机验收、生产发布。任一项未执行时，结论必须标记为“未验证”。

## 自动化门禁

- Python 静态编译和 backend/agent/voice-gateway/recording_adapter 全部测试通过。
- 后端测试在 SQLite 以及真实 PostgreSQL/Redis 两种组合下通过。
- 前端 TypeScript、生产构建、Vitest 覆盖率门禁和 Playwright admin/agent 验收通过。
- Compose 镜像从空缓存可构建，所有服务进入 healthy；Prometheus三个业务采集目标均为up，Grafana和Alertmanager进入ready，版本化迁移重复执行为 `none`。
- SIPp镜像按精确源码版本和SHA-256构建，测试场景XML通过解析；真实发包只能在已批准的测试线路执行。
- 无 `dead` 任务、无超时 `processing` 任务、无录音删除失败。

## 经营指标口径

- 管理首页的“有效接通”按当前状态为 `answered`、`in_ai`、`waiting_human`、`handoff_transferring`、`completed`，或分析/人工复核结果为 `rejected`、`completed` 及意向类结果统计；供应商若在接通后直接回传失败终态，需在正式对接时补充独立的 answered 时间字段或标准事件再校准口径。
- “意向线索”按自动分析或人工复核结果为 `interested`、`qualified_lead`、`positive_lead`、`appointment` 或 `converted` 统计。
- 人工校正应由管理员或班组长执行；市场复盘前需关注“待复核”数量，避免把未经抽检的自动分类直接当作最终成交口径。

## 真实语音验收

运营商、PBX、ASR、TTS、LLM、短信和对象存储必须使用候选生产服务，不得使用 mock。验收号码由测试人员控制，并已获得接听与录音同意。

```bash
python3 scripts/real_voice_acceptance.py \
  --base-url https://staging.example.com \
  --api-key "$TEST_TENANT_API_KEY" \
  --tenant-id 1 \
  --phone "$CONTROLLED_TEST_PHONE" \
  --confirm-dial
```

执行人需依次完成：

1. `human_only`：人工接听、双向语音、挂机和双声道录音。
2. `mixed_human_first`：录音播放后转人工，验证桥接不丢音。
3. `ai_only`：至少三轮对话，覆盖静音、口音、插话打断和正常挂机。
4. `ai_handoff`：用明确转人工语句触发转接，座席接听后继续通话并挂机。

每通需核对 provider call id、接听与终态回调、ASR final、AI 决策、TTS 播放、转人工、录音 URL、签名业务回调及相关阶段耗时。

## 故障和容量

- 分别注入 PBX 超时、ASR/TTS/LLM 5xx、Redis 断连、回调接收方 5xx 和 Worker 执行中重启。
- 确认任务能重试或进入死信，重复 webhook 不会重复修改通话，客户业务回调可重放。
- 按候选上线并发的 10%、25%、50%、100% 逐级压测，再进行不少于一个完整业务高峰的长稳测试。
- 延迟、成功率、资源上限和报警阈值由线路、业务和合规负责人在测试前签字确认，不在代码中伪造统一指标。
- 可先用 `scripts/run-sipp-acceptance.sh` 执行OPTIONS可达性，再执行基础UAC场景；脚本要求显式设置
  `SIPP_CONFIRM_RUN=RUN_CONTROLLED_SIPP_TEST`，所有结果写入 `artifacts/sipp/`。
