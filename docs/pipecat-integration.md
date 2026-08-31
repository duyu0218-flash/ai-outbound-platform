# Pipecat 混合语音流水线

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
4. Pipecat STT 把中间/最终转写送入现有 speech webhook。最终转写继续触发现有持久化 `ai_turn` 任务。
5. `ai-agent` 产生回答后，`control-api` 仍调用 `/v1/call/speak`。网关在 Pipecat 模式下将文本注入该通话的 TTS worker，语音再通过同一 WebSocket 返回 FreeSWITCH。
6. TTS 开始、结束、打断和媒体关闭状态会回传现有 media webhook。

## 版本和生产门禁

- 依赖锁定为 `pipecat-ai[openai,websocket]==1.8.1`，并与 `compatibility-matrix.toml` 和 `PIPECAT_VERSION` 一致。
- 生产配置默认必须保持 `VOICE_AI_PIPELINE=legacy`。新版本只能自动进入候选测试，不能自动切换生产。
- 只有在 FreeSWITCH、媒体模块、真实线路、双向音频、打断、录音、转人工和并发容量完成回归，且受控灰度通过后，才能在生产将值改为 `pipecat`。
- 安全修复可走加急升级，但不跳过最小回归和受控灰度。

## 必需配置

```dotenv
VOICE_GATEWAY_DRIVER=freeswitch_esl
VOICE_AI_PIPELINE=pipecat
PIPECAT_VERSION=1.8.1
PIPECAT_MEDIA_WS_BASE=wss://voice-gateway.internal/v1/pipecat/media
PIPECAT_SAMPLE_RATE=8000
PIPECAT_CHANNELS=1
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

- 候选链路当前实现 `openai-realtime` STT 和 `openai` TTS；其他供应商需新增适配器和兼容矩阵后才能使用。
- WebSocket serializer、配置门禁、FreeSWITCH 路由和容器依赖可在仓库内验证；真实 SIP carrier/PBX、非核心媒体模块、OpenAI 账号、双向媒体和真机通话必须在目标环境独立验收。
