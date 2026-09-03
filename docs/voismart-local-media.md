# VoiSmart 本机双向媒体接入

## 范围

保留 `control-api → voice-gateway` 控制接口和业务 webhook 格式，不改客户、任务、报表口径或数据库结构。
在 ARM64 Docker Linux 上使用真实 FreeSWITCH、VoiSmart 模块和 Pipecat WebSocket transport。
输入探针和 TTS 正弦波是合成测试处理器，**不是云 ASR/TTS，也不是运营商 SIP/RTP 验收**。

`docker-compose.media.yml` 增加 `freeswitch-media`、`media-probe`，不自动替换业务网关的 mock/legacy 模式。
probe 使用项目原 `/v1/pipecat/media/{token}` 入口和当前网关源码，不能拨真实号码、不连接业务数据库，禁止作为正式业务网关发布。

## 固定源码及许可

- [VoiSmart v2.5.1](https://github.com/VoiSmart/mod_openai_realtime/releases/tag/v2.5.1)：`4c5f5b3000636edf6f5b08a482d62ec8d620bb06`，实际模块名 `mod_openai_audio_stream`。
- [MIT 许可证](https://github.com/VoiSmart/mod_openai_realtime/blob/4c5f5b3000636edf6f5b08a482d62ec8d620bb06/LICENSE)没有“10 并发流通道”条款；保留版权和许可，不意味着任意并发已验证。
- IXWebSocket 固定 `dfa10df5ae89697d5dad56e1845aee64d2334d70`。
- FreeSWITCH 复用上游测试环境 commit `ac9b8c621b3ac362ea1e153a929aaee6902a1b2d`，运行版本 `1.10.13-dev`。这是兼容性验证基线，**不是已审核生产稳定版**。
- 上游 integration 镜像无法匿名拉取，本机从固定源码构建。修正 ARM64 下 spandsp 安装在 `/usr/lib`，但上游复制使用 `/usr/lib/*/` 导致的漏装。
- 镜像保留模块源码与许可。FreeSWITCH、其依赖和分句资源各有许可；完整 SBOM、CVE、许可审计尚未完成。

## 两处协议衔接

1. 打断：先清 Pipecat 输出队列，再经同一 WebSocket 发送 `{"type":"input_audio_buffer.speech_started"}`，清 VoiSmart 队列和播放缓冲。ASR/VAD speech-start 也转成真实 InterruptionFrame；模块正在播放时，stop-speaking 等实际停止事件，超时不返回虚假成功。
2. 播放完成：TTSStoppedFrame 只表示生成结束。非 urgent 消息在尾部 PCM flush 后发送 `{"type":"response.output_audio.done"}`；收到 `mod_openai_audio_stream::openai_speech_stop` 才报告 listening。speaking 同样来自实际播放事件，ESL 显式订阅 CUSTOM 子类。

原始模式事件没有逐句 response ID，因此一通电话暂只允许一条未完成话术；排空或打断确认前再次 speak 返回 HTTP 409，避免把旧完成事件记给新话术。

另修复：认证并原子占用令牌后完成 WebSocket.accept；拒绝重复/过期令牌，异常/挂断撤销会话。
镜像预装 `punkt_tab`，避免通话期间联网下载分句资源或写入只读 home。

## 启停与安全

设置 `PIPECAT_MEDIA_PROTOCOL=voismart` 后，空启动模板使用内置命令：

```text
uuid_raw_audio_stream {uuid} start {media_ws_url} mono {sample_rate} {sample_rate}
```

告知语播放完成后设置 `STREAM_DISABLE_AUDIOFILES=true`、`STREAM_NO_RECONNECT=true`、`STREAM_SUPPRESS_LOG=true`，启动流并在客户腿播放 `silence_stream://-1` 提供写时钟。
停止/转人工/挂断执行 `uuid_raw_audio_stream {uuid} stop`。generic raw PCM 和 legacy 路径保留。

`PIPECAT_MEDIA_CONNECT_TIMEOUT_SEC=15` 限定媒体命令成功到 Pipecat transport 就绪的等待时间（必须大于 0 且不超过会话超时）。
未就绪超时、模块异常断连或媒体 worker 退出会停止媒体、撤销令牌并挂断通话，状态回调使用固定错误码，不写入可能带媒体令牌的原始异常命令。
这不是云 ASR/TTS 就绪检查，也不代表已经收到客户 RTP。

每通电话的媒体启动、告知语等待和断连监督任务均受生命周期管理。主动挂断或转接先标记停止、取消待启动任务，再停止模块和转接；迟到的事件不得恢复 AI。
转接中的新话术被拒绝，正常停止不会上报意外媒体断连。取消告知语不会在稍后悄悄启动录音。
对端不经过业务 API 直接挂断时，处理 `CHANNEL_HANGUP` 取消监督并标记关闭；若 WebSocket 更早关闭，还会查询 `uuid_exists` 核对真实通道，而不依赖两个连接的事件到达顺序。最终通话状态由 `CHANNEL_HANGUP_COMPLETE` 的原因决定。单凭 socket 关闭不把媒体 closed 回调标为错误；实际故障由监督任务产生明确 failed 状态。
媒体控制接口对失效会话返回 HTTP 404，播放冲突返回 409，操作超时返回 504，不伪报操作成功。
ESL 网络本身不可用时，无法保证远端通话已挂断，仍需生产侧断网/重连与状态对账验收。

ESL 不映射宿主机端口，使用随机秘密文件及明确的项目私网白名单；测试 HTTP 仅绑定 `127.0.0.1:18002`，写操作必须认证。probe 移除 `/v1/call/dial`。FreeSWITCH 未配置运营商 gateway、SIP profile 或真实账号。

## 构建与运行

仓库根目录执行：

```sh
sh scripts/build-voismart.sh
# 仅首次创建，原文件不得覆盖；.secrets 目录应保持私有。
test -e .secrets/voismart-esl-password || openssl rand -hex -out .secrets/voismart-esl-password 32
chmod 444 .secrets/voismart-esl-password
docker network inspect ai_default --format '{{json .IPAM.Config}}'
# 将实际网段填写到 .env 的 MEDIA_ESL_ALLOWED_CIDR，不可使用 0.0.0.0/0。
docker compose build voice-gateway
```

将 `docker-compose.media.yml` 追加到原 Compose 列表，保留原数据卷覆盖文件。本机交付已写入 `.env`；其他环境须按实际项目与网段配置。

```sh
docker compose up -d --no-deps freeswitch-media media-probe
docker compose ps
docker compose exec -T media-probe python /opt/media-test/verify_voismart_media.py
docker compose exec -T media-probe python /opt/media-test/verify_media_lifecycle.py
# 对端正常挂断重复性验证；不是并发或长稳验收。
docker compose exec -T media-probe python /opt/media-test/verify_media_lifecycle.py --peer-repeats 20
```

probe 只读挂载 `voice_gateway/app`，以 `PYTHONPATH=/app` 保证测试当前源码。修改后重启 probe；业务网关无测试挂载，须重新构建。

## 验收

- 构建时运行 2 个上游 CTest 可执行套件，启用 ASan/UBSan；模块构建启用 TLS。
- 上游真实集成：`docker run --rm ai-freeswitch-voismart:2.5.1 sh tests/integration/run.sh`，9 项。
- 项目验收：原令牌媒体入口、HTTP speak/stop/hangup、真实 ESL 事件和真实 HTTP webhook。
- 正常排空：FreeSWITCH WAV 中检出完整 1.2 秒 1000Hz 音；完成事件晚于生成约 1.2 秒。
- 输出打断：生成 6 秒音，检查提前终止及录音末尾无残留。
- 模块积压打断：一次压入 6 秒 PCM 后打断，检验实际清缓冲，不是仅停发后续音频。
- 同时双向：内部 loopback 上行 600Hz、下行 1000Hz；上行以 600Hz 为主，未混入机器人下行。
- 挂断后 channel/session/binding 归零。合成测试录音留本机专用卷，未上传真实客户录音。
- 故障注入：WebSocket 断开、模块停止、连接未就绪超时、内部转接、告知语期间转接/挂断，以及故障后的新通话恢复。确认会话、令牌、任务、套接字和等待器均释放。
- 内部 `handoff-probe` 只停泊在本机测试 dialplan，用于确认 AI 媒体已撤销且客户腿仍存在；没有真人坐席接听，不作为转人工验收。

## 正式业务前置条件与回退

仍需稳定 FreeSWITCH 生产版本/镜像审核、线路、授权号码、告知语 TTS、真实 ASR/TTS 凭据、TLS/WSS、实际 SIP/RTP、转人工、录音入库/播放/删除、并发长稳和恢复验收。浏览器座席另需域名证书、WebRTC、TURN 和耳机真机验证。

业务切换配置：`VOICE_GATEWAY_DRIVER=freeswitch_esl`、`VOICE_AI_PIPELINE=pipecat` 或 hybrid、`PIPECAT_MEDIA_PROTOCOL=voismart`、真实 `FREESWITCH_ESL_*`、`FREESWITCH_GATEWAY`、`PIPECAT_MEDIA_WS_BASE`，以及现有 Settings 要求的供应商/告知语配置。不要复制 probe 合成凭据。

本机停止 `media-probe` 和 `freeswitch-media` 可关闭测试层，不删除卷。业务网关保留回退镜像 `ai-voice-gateway:pre-voismart-20260902`；不要执行 `compose down -v`。
