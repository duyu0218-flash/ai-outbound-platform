# 实时语音网关契约（P0/P1）

控制服务不直接处理 SIP/RTP 音频包。PBX、SIP 中继、WebRTC、ASR 与 TTS 由可替换的语音网关承接，平台通过以下 HTTP 契约完成控制与事件回传。

## 平台调用语音网关

- `POST /v1/call/dial`：发起呼叫并传入平台 webhook 地址。
- `POST /v1/call/speak`：播放 TTS；建议返回 `playback_id`。
- `POST /v1/call/stop-speaking`：用户插话时立即取消当前 TTS。
- `POST /v1/call/transfer`：桥接人工座席或技能组。
- `POST /v1/call/hangup`：结束通话。

## 语音网关回传平台

- `POST /api/v1/webhooks/telephony/status`：拨号、接听、结束状态。
- `POST /api/v1/webhooks/telephony/media`：媒体会话的 listening、thinking、speaking、interrupted、closed 状态与阶段耗时。
- `POST /api/v1/webhooks/telephony/speech`：ASR 临时/最终转写。必须提供稳定的 `event_id`；最终转写才触发 AI 决策。
- `POST /api/v1/webhooks/telephony/recording`：录音 URL、对象存储 URI、格式、时长与校验值。

所有 webhook 在生产环境必须携带 `x-webhook-token`。重试时保持同一 `event_id`，平台会按呼叫维度去重。`barge_in=true` 会触发 `/v1/call/stop-speaking`。

## 明确边界

仓库实现的是生产可替换的控制面、状态机、数据落库和验收接口，不包含运营商账号、SIP 中继、媒体服务器或云 ASR/TTS 凭证。上线前必须用选定网关完成双向音频、打断延迟、录音双声道与高并发压测。
