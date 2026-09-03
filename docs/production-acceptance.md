# 生产发布验收门禁

以下状态必须分开记录：源代码与 CI 通过、测试环境发布、真实线路验收、真机验收、生产发布。任一项未执行时，结论必须标记为“未验证”。

## 自动化门禁

- Python 静态编译和 backend/agent/voice-gateway/recording_adapter 全部测试通过。
- 后端测试在 SQLite 以及真实 PostgreSQL/Redis 两种组合下通过。
- 前端 TypeScript、生产构建、Vitest 覆盖率门禁和 Playwright admin/agent 验收通过。
- Compose 镜像从空缓存可构建，所有服务进入 healthy；Prometheus三个业务采集目标均为up，Grafana和Alertmanager进入ready，版本化迁移重复执行为 `none`。
- SIPp镜像按精确源码版本和SHA-256构建，测试场景XML通过解析；真实发包只能在已批准的测试线路执行。
- 无 `dead` 任务、无超时 `processing` 任务、无录音删除失败。
- `scripts/check-version-constraints.py --production-env <受保护env>` 通过：生产镜像digest、真实Provider、允许号段、租户日限额、Webhook签名、外部告警、录音HTTPS和外部LLM数据策略全部满足。
- CI安全任务无高危依赖漏洞，并保存四个Python服务的CycloneDX SBOM。

## 数据库发布步骤

生产应用必须设置 `AUTO_MIGRATE=false`，应用和Worker启动时只校验表结构，不执行DDL。首次部署在维护窗口显式执行：

```bash
python -m app.schema_bootstrap
python -m app.migration_runner
python -m app.migration_runner  # 第二次必须输出 none
```

升级只执行 `migration_runner`。执行前必须完成数据库备份、恢复点确认和锁表影响评估；应用启动不能代替发布迁移。

## 回调与批量导入安全

- 运营商和短信回调必须同时携带共享令牌、`X-Webhook-Timestamp` 和 `X-Webhook-Signature`。签名为 `HMAC-SHA256(secret, "<timestamp>.<raw_body>")`，默认只接受前后五分钟。
- 联系人导入在生产必须携带 `Idempotency-Key`；同一个文件重试必须复用同一个Key。平台保存处理状态和原始结果，避免客户端超时后重复导入。
- 外部LLM必须在租户配置中显式启用，目标域名进入 `LLM_ALLOWED_HOSTS`，生产发布保持 `LLM_SEND_PII=false`。
- 到期清理必须同时验证临时转写、最终转写、通话号码/摘要/事件/分析脱敏和录音删除；运营商原始录音仍需供应商侧删除策略。

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
  --expected-asr-provider pipecat:aliyun-nls \
  --confirm-dial
```

脚本对 AI 模式会另外读取结构化转写和阶段指标：`ai_only` 必须至少有 3 个非空 ASR final，其他 AI 模式至少 1 个；指定 `--expected-asr-provider` 后还会校验供应商名、置信度、句子时间戳以及 `asr.final` 失败指标。报告中的 `asr_final_latency_ms` 是语音网关观测到的“云端句末位置到 final 到达网关”延迟，不是句子音频时长，也不是客户端到端延迟。

执行人需依次完成：

1. `human_only`：人工接听、双向语音、挂机和双声道录音。
2. `mixed_human_first`：录音播放后转人工，验证桥接不丢音。
3. `ai_only`：至少三轮对话，覆盖静音、口音、插话打断和正常挂机。
4. `ai_handoff`：用明确转人工语句触发转接，座席接听后继续通话并挂机。

每通需核对 provider call id、接听与终态回调、ASR final、AI 决策、TTS 播放、转人工、录音 URL、签名业务回调及相关阶段耗时。

### 云 ASR 准确率、延迟、并发和费用

- 准确率：对同一批真实线路录音做人工标注，固定文本归一化规则，同时计算整体 CER、关键词召回率，并按性别、口音、噪声、车载/室内、快慢语速分层；不得用云端转写自身作为标准答案。
- 延迟：汇总 `asr.final` 指标的 `duration_ms`，计算 P50/P95/P99；同时用录音与事件时间线抽样核对端到端话轮延迟。
- 并发：按候选上线并发的 10%/25%/50%/100% 递增，记录网关活跃会话、云 ASR 限流/断线、final 成功率、P95 延迟和 CPU/内存；`PIPECAT_MAX_ACTIVE_SESSIONS` 不得高于已购并发和实测安全容量。
- 费用：以云厂商账单的实际计费时长/请求数为分子，以完成通话、有效接通和有效线索分别为分母，报告每分钟、每完成通话、每有效线索成本；免费额度和促销价需单列，不得当作长期单价。
- 上述阈值必须在测试前由业务、线路和合规负责人签字，结果与原始通话 ID、脱敏录音、转写、指标和账单快照一起归档。

## 故障和容量

- 分别注入 PBX 超时、ASR/TTS/LLM 5xx、Redis 断连、回调接收方 5xx 和 Worker 执行中重启。
- 确认任务能重试或进入死信，重复 webhook 不会重复修改通话，客户业务回调可重放。
- 按候选上线并发的 10%、25%、50%、100% 逐级压测，再进行不少于一个完整业务高峰的长稳测试。
- 延迟、成功率、资源上限和报警阈值由线路、业务和合规负责人在测试前签字确认，不在代码中伪造统一指标。
- 可先用 `scripts/run-sipp-acceptance.sh` 执行OPTIONS可达性，再执行基础UAC场景；脚本要求显式设置
  `SIPP_CONFIRM_RUN=RUN_CONTROLLED_SIPP_TEST`，所有结果写入 `artifacts/sipp/`。
