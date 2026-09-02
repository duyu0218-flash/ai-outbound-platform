# Pipecat 混合语音流水线

VoiSmart 双向 PCM、实际播放结束与清缓冲接入，见[本机媒体部署及验收](voismart-local-media.md)。本机合成测试不代替真实云语音或线路验收。

## 目标与边界

语音网关现在支持两条显式可选链路：

```text
control-api
    ↓
voice_gateway
    ↓ VOICE_AI_PIPELINE
    ├─ legacy  原有 ASR 回调 → control-api/agent → FreeSWITCH TTS
    └─ pipecat Pipecat STT → control-api/agent → Pipecat TTS
```

Pipecat只接管实时音频、STT、TTS和打断帧。话术、知识库、合规策略、幂等、重试、任务状态和转人工仍由现有 `control-api` 和 `ai-agent` 处理。因此切换媒体流水线不会绕过现有业务闭环。

## 运行方式

1. `control-api` 按原有 `/v1/call/dial` 合约请求 `voice_gateway`。
2. FreeSWITCH 接通后，网关为该通话生成高强度、有限时且不可重用的 WebSocket 令牌，并执行已验收的媒体模块启动命令。
3. FreeSWITCH 向 `/v1/pipecat/media/{session_token}` 双向传输无头 PCM16。
4. Pipecat STT 把中间/最终转写送入现有 speech webhook。OpenAI Realtime 的 completed 事件或阿里云 NLS 的 `SentenceEnd` 才作为最终转写，并继续触发现有持久化 `ai_turn` 任务。
5. `ai-agent` 产生回答后，`control-api` 仍调用 `/v1/call/speak`。网关在 Pipecat 模式下将文本注入该通话的 TTS worker，语音再通过同一 WebSocket 返回 FreeSWITCH。
6. TTS 开始、结束、打断和媒体关闭状态会回传现有 media webhook。

## 版本和生产门禁

- 依赖锁定为 `pipecat-ai[openai,websocket]==1.8.1`，并与 `compatibility-matrix.toml` 和 `PIPECAT_VERSION` 一致。
- 生产配置默认必须保持 `VOICE_AI_PIPELINE=legacy`。新版本只能自动进入候选测试，不能自动切换生产。
- 真实灰度使用 `VOICE_AI_PIPELINE=hybrid`：租户设置默认值与稳定百分比，活动可强制选择，最终选择持久化到通话记录。
- 只有在 FreeSWITCH、媒体模块、真实线路、双向音频、打断、录音、转人工和并发容量完成回归，且受控灰度通过后，才能在生产将值改为 `pipecat`。
- `hybrid` 网关会拒绝控制面与网关模式不一致的请求，避免实际链路与审计记录发生漂移。
- 安全修复可走加急升级，但不跳过最小回归和受控灰度。

## 必需配置

```dotenv
VOICE_GATEWAY_DRIVER=freeswitch_esl
VOICE_AI_PIPELINE=pipecat
PIPECAT_VERSION=1.8.1
PIPECAT_MEDIA_WS_BASE=wss://voice-gateway.internal/v1/pipecat/media
PIPECAT_SAMPLE_RATE=8000
PIPECAT_CHANNELS=1
PIPECAT_MAX_ACTIVE_SESSIONS=100
PIPECAT_STT_PROVIDER=openai-realtime
PIPECAT_TTS_PROVIDER=openai
PIPECAT_OPENAI_API_KEY=<secret>
PIPECAT_OPENAI_REALTIME_BASE_URL=wss://api.openai.com/v1/realtime
PIPECAT_TTS_MODEL=gpt-4o-mini-tts
PIPECAT_TTS_VOICE=alloy
PIPECAT_FALLBACK_TO_LEGACY=false
FREESWITCH_PIPECAT_START_COMMAND_TEMPLATE=<approved module command>
FREESWITCH_MEDIA_STOP_COMMAND_TEMPLATE=<approved module stop command>
```

`PIPECAT_OPENAI_API_KEY`、ESL密码和 SIP凭据不得写入仓库或日志。WebSocket 路径含有限时、按通话作用域的令牌；反向代理和应用访问日志必须对该路径做脱敏或关闭记录。内网之外的媒体 WebSocket 必须使用 `wss://`。

### 阿里云实时 ASR

将 STT 改为阿里云 NLS 时，TTS 仍可继续使用已配置的 OpenAI-compatible 服务：

```dotenv
PIPECAT_STT_PROVIDER=aliyun-nls
PIPECAT_SAMPLE_RATE=8000
ALIYUN_NLS_GATEWAY_URL=wss://nls-gateway-cn-shanghai.aliyuncs.com/ws/v1
ALIYUN_NLS_APPKEY=<project-appkey>
ALIYUN_NLS_TOKEN_FILE=/run/secrets/aliyun_nls_token
ALIYUN_NLS_VOCABULARY_ID=<optional-hotword-vocabulary-id>
ALIYUN_NLS_CUSTOMIZATION_ID=<optional-custom-model-id>
ALIYUN_NLS_MAX_SENTENCE_SILENCE_MS=800
```

- 输入必须是无 WAV 头、16-bit、单声道 PCM，且必须与 `PIPECAT_SAMPLE_RATE` 一致。阿里云该协议只允许 8000 或 16000 Hz，配置门禁会拒绝其他值。
- `TranscriptionResultChanged` 只是句内 partial；`SentenceEnd` 是唯一 final，其 `message_id`、`confidence`、`begin_time` 和 `time` 会映射到现有 speech webhook。网关还会计算 `latency_ms`，后端将它存为 `asr.partial`/`asr.final` 指标耗时；句子音频时长单独保存在指标详情中。
- `ALIYUN_NLS_VOCABULARY_ID` 和 `ALIYUN_NLS_CUSTOMIZATION_ID` 必须是阿里云控制台已发布的 ID，不是自由文本热词列表。
- Token 是短时凭证。进程每次新建/重连 ASR 会话都重新读取 `ALIYUN_NLS_TOKEN_FILE`；生产应由独立刷新进程原子替换该文件。`ALIYUN_NLS_TOKEN` 只适合受控测试。
- `PIPECAT_MAX_ACTIVE_SESSIONS` 是本进程硬门禁，应设为不高于已购云 ASR 并发和实测容量的值。
- 真实线路运行 `scripts/real_voice_acceptance.py --expected-asr-provider pipecat:aliyun-nls`，会强制验收 final 数量、供应商、置信度、时间戳和失败指标；准确率、并发和费用口径见 `docs/production-acceptance.md`。

## FreeSWITCH 媒体模块合约

仓库不猜测生产使用的非核心 FreeSWITCH 模块，所以不提供默认命令。现场选定的 media-bug/WebSocket 模块必须满足：

- 支持同一连接的双向音频，不是只上传录音；
- 上下行都使用无 WAV 头的 little-endian PCM16；
- 与 `PIPECAT_SAMPLE_RATE` 和 `PIPECAT_CHANNELS` 一致；
- 支持按 UUID 启停，且转人工/挂断时能可靠关闭；
- 记录精确模块版本，并与 FreeSWITCH 版本做联合兼容验收。

启动命令模板可使用：`{uuid}`、`{call_id}`、`{session_id}`、`{media_ws_url}`、`{sample_rate}`、`{channels}`、`{codec}`。具体命令只能根据现场已安装模块的官方语法填写。

## 失败和回滚

- `PIPECAT_FALLBACK_TO_LEGACY=false` 是默认值。Pipecat 启动失败会显式失败，避免静默改变语音行为。
- 只在已同时配置、验收 legacy 媒体启动命令时，才能设置 `PIPECAT_FALLBACK_TO_LEGACY=true`。配置校验会拒绝无可用 legacy 命令的伪降级。
- 生产回滚是将 `VOICE_AI_PIPELINE` 恢复为 `legacy`，重启语音网关，并对受影响通话做审计。

## 当前已知限制

- 候选链路已实现 `openai-realtime` 和 `aliyun-nls` STT，TTS 目前仍是 `openai`；其他供应商需新增适配器和兼容矩阵后才能使用。
- 代码层重连能重建 ASR 会话，但断线前已经发送、尚未 final 的当前句无法从云端恢复；必须通过失败事件、话术重试或转人工收口，不得默默丢字。
- WebSocket serializer、配置门禁、partial/final 映射和容错可在仓库内验证；真实 SIP carrier/PBX、非核心媒体模块、云 ASR/TTS 账号、双向媒体、AEC 回声效果和真机通话必须在目标环境独立验收。
