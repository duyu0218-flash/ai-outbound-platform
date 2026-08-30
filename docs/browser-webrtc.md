# 浏览器 WebRTC 坐席部署与验收

## 1. 已实现范围

本仓库已经实现以下代码能力：

- 坐席浏览器通过 SIP.js 使用 SIP over WSS 注册到 FreeSWITCH；
- 浏览器麦克风授权、输入/输出设备选择和设备变化检测；
- 来电提醒、接听、拒绝、静音、保持/恢复、DTMF 和挂机；
- TURN REST API 短期凭证，不把永久 TURN 密码放入前端；
- SIP 短期随机密码，仅保存在 Redis，由 FreeSWITCH `mod_xml_curl` 动态查询；
- 坐席只有在麦克风可用且 SIP 注册成功时才能进入 `ready`；
- AI 转人工前停止播放和可配置的 media-bug，随后转到精确的坐席分机；
- FreeSWITCH `CHANNEL_BRIDGE` 事件确认真实媒体桥接，通话进入 `in_human`；
- 坐席 25 秒未接或桥接失败时，保持客户通道、释放坐席并将转人工任务放回队列；
- 媒体状态心跳、平台事件流、通话时长、转写摘要及 WebRTC RTT/抖动/丢包指标；
- 纯人工外呼先呼叫浏览器分机，再由 FreeSWITCH 桥接运营商号码；
- coturn、Nginx 和 FreeSWITCH 的单机 Compose 扩展及配置片段。

## 2. 运行架构

```text
浏览器座席
  ├─ HTTPS 443 ───────────────> Nginx -> control-api
  ├─ SIP over WSS 7443 ───────> FreeSWITCH
  └─ STUN/TURN 3478/5349 ─────> coturn

FreeSWITCH
  ├─ ESL 8021（仅本机）───────> voice-gateway
  ├─ SIP/RTP ─────────────────> 企业 SIP Trunk
  └─ mod_xml_curl ────────────> control-api 动态坐席目录
```

## 3. 前置资源

必须准备：

1. Linux服务器、固定公网 IPv4 和域名；
2. 浏览器信任的 TLS 证书；
3. 已审计并固定到版本或 digest 的 FreeSWITCH 镜像；
4. 企业 SIP Trunk、号码、白名单和并发额度；
5. FreeSWITCH完整配置目录，包含运营商网关、ESL和基础模块；
6. Redis，生产环境不能使用进程内媒体状态降级；
7. 防火墙开放 HTTPS、WSS、TURN、SIP和各自不重叠的RTP端口。

不要把 5432、6379、8001、8002、8021 暴露到公网。

## 4. 配置步骤

从 `.env.example` 创建独立生产环境文件，至少填写：

```env
WEBRTC_ENABLED=true
WEBRTC_WSS_URL=wss://voice.example.com:7443
WEBRTC_SIP_DOMAIN=voice.example.com
TURN_URLS=stun:voice.example.com:3478,turn:voice.example.com:3478?transport=udp,turns:voice.example.com:5349?transport=tcp
TURN_SHARED_SECRET=<强随机值>
FREESWITCH_DIRECTORY_TOKEN=<不同的强随机值>
PUBLIC_IPV4=<服务器公网IPv4>
TURN_REALM=voice.example.com
TLS_CERT_FILE=/etc/letsencrypt/live/voice.example.com/fullchain.pem
TLS_KEY_FILE=/etc/letsencrypt/live/voice.example.com/privkey.pem
FREESWITCH_WSS_PEM=/etc/letsencrypt/live/voice.example.com/wss.pem
FREESWITCH_IMAGE=<固定版本或digest的镜像>
FREESWITCH_CONFIG_DIR=/srv/ai-outbound/freeswitch
```

FreeSWITCH 的 `wss.pem` 是证书链与私钥的合并文件。文件权限必须只允许运行 FreeSWITCH 的账号读取。

把仓库提供的配置片段写入已经准备好的完整 FreeSWITCH 配置目录：

```bash
FREESWITCH_DIRECTORY_TOKEN='<强随机值>' \
CONTROL_API_INTERNAL_URL='http://127.0.0.1:8000' \
./scripts/render-freeswitch-webrtc-config.sh /srv/ai-outbound/freeswitch
```

确认镜像已启用 `mod_sofia`、`mod_event_socket`、`mod_xml_curl`、`mod_opus`、录音模块和所选 ASR media-bug 模块。然后启动：

```bash
docker compose --env-file .env.production \
  -f docker-compose.yml \
  -f docker-compose.webrtc.yml \
  up -d --build
```

## 5. 端口规划

| 端口 | 协议 | 用途 | 公网 |
|---|---|---|---|
| 80/443 | TCP | HTTP跳转、后台和API | 是 |
| 7443 | TCP | FreeSWITCH SIP over WSS | 是 |
| 3478 | UDP/TCP | STUN/TURN | 是 |
| 5349 | TCP/UDP | TURNS/DTLS | 是 |
| 49152–55000 | UDP | TURN relay | 是 |
| 20000–30000 | UDP | FreeSWITCH RTP | 按运营商和WebRTC网络要求 |
| 5060/5061 | UDP/TCP | SIP/SIPS | 仅运营商白名单 |
| 8021 | TCP | FreeSWITCH ESL | 否 |

TURN 和 FreeSWITCH RTP 端口范围不能重叠。

## 6. 必须执行的验收

1. 坐席登录后初始状态为离线；
2. 未授权麦克风时不能切换为空闲；
3. 启用软电话后能够看到 `registered`；
4. FreeSWITCH `sofia status profile internal-webrtc reg` 能看到唯一坐席注册；
5. AI 通话发起转人工，空闲坐席实时收到队列；
6. 坐席接受后浏览器真实振铃，拒绝和超时不会误报已接通；
7. 接听后客户与坐席双向有声，后台状态变为 `in_human`；
8. 静音、保持/恢复、DTMF和挂机实际生效；
9. 切换耳机、拔出耳机、页面刷新和网络断开时状态正确；
10. TURN强制中继测试、企业网络测试和移动热点测试通过；
11. 录音在AI转人工前后连续，双声道和保留策略符合配置；
12. 逐级完成10、30、50、100路真实线路压测并保存CPU、丢包、抖动和带宽证据。

Mock、单元测试和浏览器假设备只能证明代码路径，不能替代真实线路、真实耳机和公网TURN验收。
