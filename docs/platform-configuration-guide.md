# AI 外呼平台配置手册

> 适用版本：本仓库当前本地部署；核对日期：2026-09-03
>
> 适用对象：部署人员、系统管理员、线路对接人员和验收人员
>
> 本手册说明“在哪里配置、参数从哪里取得、何时生效、怎样验收”。完整低层参数仍以 [`.env.example`](../.env.example) 为准。

## 1. 先看结论

平台配置分为四层，不能混填：

| 层级 | 填写位置 | 负责内容 | 是否保存密钥 |
|---|---|---|---|
| 部署环境 | 受保护的 `.env` 或密钥文件 | 数据库、Redis、服务鉴权、PBX、ASR、TTS、LLM、短信、录音、监控 | 是，必须限制权限 |
| FreeSWITCH | FreeSWITCH 配置目录 | SIP 注册、运营商网关、拨号计划、RTP、录音、WSS | 是，不能提交仓库 |
| 管理中心 | `/admin/lines`、`/admin/settings` | 线路选择、并发、业务服务商名称、合规、回调、短信模板 | 否，只保存非敏感配置或凭据引用 |
| 外呼任务 | `/admin/campaigns` | 名单、话术、模式、任务并发、重试、录音和短信策略 | 否 |

核心调用链为：

```text
运营商 SIP → FreeSWITCH → 双向媒体 → ASR → 规则/大模型 → TTS → FreeSWITCH → 客户
                                          └────────→ 转人工/短信/业务回调
```

只有该链路全部接通并实拨通过，才能称为真实外呼可用。容器健康、Mock 呼叫和合成音频测试不能代替真实线路验收。

## 2. 当前本地部署状态

2026-09-03 只读检查结果：

- `control-api`、`task-worker`、`ai-agent`、`voice-gateway`、PostgreSQL、Redis、SeaweedFS、录音适配器等本地服务正在运行；业务容器健康。
- 本地另有 `freeswitch-media` 和 `media-probe`，用于 VoiSmart 双向媒体合成验收；它们不是运营商线路。
- Control API 当前通过 HTTP 调用内部 Voice Gateway，但 Voice Gateway 的实际驱动仍为 `mock`。
- 当前语音流水线为 `legacy`，大模型为本地 `rule`，短信为 `mock`。
- 运行容器没有注入可用的云 ASR、云 TTS、外部 LLM 凭据；真实主叫号码也未配置。

因此当前状态是：**本地系统和测试基础设施已运行，真实电话、云语音、真机坐席和生产发布未完成配置与验收。**

## 3. 配置文件与安全规则

### 3.1 建立独立环境文件

不要修改或提交 `.env.example`。新环境应在确认目标文件不存在后复制为已被 Git 忽略的 `.env`；已有 `.env` 时先备份并逐项合并，不能覆盖：

```bash
test ! -e .env
cp .env.example .env
chmod 600 .env
```

在 `.env` 中增加：

```dotenv
APP_ENV_FILE=.env
```

启动时显式指定：

```bash
docker compose --env-file .env \
  -f docker-compose.yml \
  -f docker-compose.observability.yml \
  up -d --build
```

如需本机 VoiSmart 合成媒体测试，再追加 `-f docker-compose.media.yml`。该文件不会把业务 Voice Gateway 自动切换成真实线路。

### 3.2 密钥规则

- 密钥只放受保护的环境文件、只读秘密文件或正式密钥管理服务。
- 不在管理页面、话术、工单、截图、日志和聊天中填写明文密钥。
- API、JWT、服务调用、Webhook、ESL、安全管理等密钥必须彼此不同。
- 真实语音网关要求关键凭据至少 32 字符；示例值、默认密码和重复密钥会被拒绝。
- `.env` 和 `.secrets/` 已被 Git 忽略，但仍需检查文件权限和备份范围。

## 4. 选择运行模式

| 场景 | `TELEPHONY_PROVIDER` | `VOICE_GATEWAY_DRIVER` | `VOICE_AI_PIPELINE` | 说明 |
|---|---|---|---|---|
| 本地页面/业务联调 | `http` | `mock` | `legacy` | 当前本地业务模式，不拨真实号码 |
| 单网关真实线路 | `http` | `freeswitch_esl` | `legacy` | FreeSWITCH 真实拨号；ASR 回调和 TTS 由外部能力提供 |
| 多租户/多线路 | `tenant` | `freeswitch_esl` | `legacy` 或受控 `hybrid` | 管理中心选择已启用线路 |
| Pipecat 真实 AI 语音 | `http` 或 `tenant` | `freeswitch_esl` | `pipecat` | 双向媒体、流式 ASR/TTS；必须先完成真实验收 |
| Pipecat 灰度 | `tenant` | `freeswitch_esl` | `hybrid` | 按租户/任务比例灰度并保留回退 |

生产不能使用 `mock`。`pipecat`/`hybrid` 不能搭配 `mock` 或未经安全验收的 `pbx_http`。

## 5. 基础平台参数

### 5.1 环境、账号和访问

| 参数 | 用途 | 本地建议 | 生产要求 |
|---|---|---|---|
| `ENV` | 运行环境 | `dev` | `production` |
| `SECRET_KEY` | 应用内部安全密钥 | 独立随机值 | 必须替换示例值 |
| `JWT_SECRET` | 登录令牌签名 | 独立随机值 | 不能与其他密钥复用 |
| `API_KEY` | 默认租户机器 API Key | 仅受控测试 | 多租户优先使用专属 Key |
| `TENANT_API_KEYS_JSON` | 租户与机器 Key 映射 | 可空 | 按租户配置 |
| `TENANT_API_SCOPES_JSON` | 机器 Key 写权限 | `{}` | 最小授权，如 `calls:dial` |
| `DEMO_USERS_ENABLED` | 演示账号 | 本地可为 `true` | 必须为 `false` |
| `TRUSTED_HOSTS` | 接受访问的域名 | 本机地址 | 只填正式域名 |
| `CORS_ALLOW_ORIGINS` | 浏览器跨域白名单 | 本机来源 | 只填正式前端来源 |

演示账号不能带入生产。正式管理员应先在 `/admin/users` 创建并验证，再关闭演示账号并重启。

### 5.2 PostgreSQL 和 Redis

| 参数 | 说明 |
|---|---|
| `POSTGRES_PASSWORD` | Compose 中 PostgreSQL 用户密码 |
| `DATABASE_URL` | 应用数据库连接字符串 |
| `REDIS_PASSWORD` | Redis 密码 |
| `REDIS_URL` | 应用 Redis 连接字符串 |
| `AUTO_MIGRATE` | 本地可为 `true`；生产必须为 `false` |
| `DATABASE_POOL_SIZE`、`DATABASE_MAX_OVERFLOW` | 数据库连接池容量，按并发测试调整 |

生产发布前必须先备份，再显式执行迁移；应用启动成功不等于数据库发布完成。

## 6. 电话线路与 FreeSWITCH

### 6.1 向线路供应商索取的资料

| 资料 | 示例含义 |
|---|---|
| SIP Server/Proxy、端口 | 运营商 SIP 接入地址 |
| 传输协议 | UDP、TCP 或 TLS |
| 鉴权方式 | 注册账号密码，或固定公网 IP 鉴权 |
| Username/Auth ID、Password | SIP 注册凭据 |
| SIP Domain、Realm | 线路域和认证域 |
| 主叫号码 | 已报备且允许显示的号码 |
| 拨号格式 | 是否带国家码、是否允许 `+` |
| 编码和 DTMF | PCMA/PCMU/Opus、RFC2833 等 |
| SIP/RTP 白名单 | 双方公网 IP 和端口范围 |
| 并发、CPS、日量 | 线路合同和平台风控共同使用 |
| 资费、计费粒度、封顶 | 用于拨前预算和运营商侧防盗打 |
| 失败码/CDR/录音规则 | 状态映射、对账与录音治理 |

### 6.2 平台环境变量

```dotenv
TELEPHONY_PROVIDER=http
TELEPHONY_PROVIDER_ENDPOINT=http://voice-gateway:8002
VOICE_GATEWAY_DRIVER=freeswitch_esl

FREESWITCH_ESL_HOST=freeswitch
FREESWITCH_ESL_PORT=8021
FREESWITCH_ESL_PASSWORD=<独立强密码>
FREESWITCH_GATEWAY=<FreeSWITCH中的网关名称>
FREESWITCH_CALLER_ID=<运营商批准的主叫号码>
FREESWITCH_DIALPLAN_CONTEXT=agent-restricted

TELEPHONY_SERVICE_TOKEN=<内部调用强Token>
TELEPHONY_WEBHOOK_TOKEN=<回调共享Token>
TELEPHONY_WEBHOOK_SECRET=<回调HMAC密钥>
```

`FREESWITCH_GATEWAY` 是 FreeSWITCH 内部网关别名，不是 IP 或账号；必须与 FreeSWITCH `<gateway name="...">` 一致。`FREESWITCH_CALLER_ID` 不能随意填写，必须是线路允许使用的主叫号码。

### 6.3 FreeSWITCH SIP 网关配置

参考 `deploy/freeswitch/sip_profiles/external/ai_carrier.xml.example`，将供应商资料写入服务器上的 FreeSWITCH 私有配置目录：

- `proxy`：SIP 服务器；
- `register`：是否注册；
- `username`、`password`：SIP 凭据；
- `from-user`、`extension`：批准的主叫号码；
- `from-domain`、`realm`：供应商域；
- `register-transport`：供应商指定的传输协议。

仓库示例只是片段，不是完整生产 SIP Profile。ESL 端口 `8021` 不得暴露公网，SIP 端口只允许运营商白名单访问。

### 6.4 真实拨号安全路由

即使 `ENV=dev`，只要使用真实网关，也必须配置以下安全项：

```dotenv
VOICE_COMMAND_SECRET=<独立强密钥>
VOICE_SECURITY_ADMIN_TOKEN=<紧急停拨管理密钥>
OUTBOUND_SECURITY_APPROVAL_TOKEN=<线路及合规变更审批密钥>
VOICE_SECURITY_DB_PATH=/var/lib/voice-security/ledger.sqlite3
VOICE_SECURITY_ROUTES_FILE=/run/secrets/voice-routes.json
VOICE_CALLBACK_BASE_URL=http://control-api:8000
VOICE_CALLBACK_ALLOW_PRIVATE_HTTP=true
```

路由文件按 `tenant_id:line_id` 建立，每条线路必须明确：网关、主叫号码、允许/禁止号段、并发、CPS、日量、小时/日预算、保守分钟费率、计费倍数和最大通话时长。空路由表会拒绝全部真实拨号，这是正常的安全默认。

不要直接使用 `deploy/security/voice-routes.example.json` 中的虚构号码和费率。

## 7. ASR、TTS 与实时媒体

### 7.1 Pipecat 和 VoiSmart 基础参数

```dotenv
VOICE_AI_PIPELINE=pipecat
PIPECAT_VERSION=1.8.1
PIPECAT_MEDIA_PROTOCOL=voismart
PIPECAT_MEDIA_WS_BASE=ws://voice-gateway:8002/v1/pipecat/media
PIPECAT_SAMPLE_RATE=8000
PIPECAT_CHANNELS=1
PIPECAT_MAX_ACTIVE_SESSIONS=<不高于已购ASR和实测容量>
MEDIA_ESL_ALLOWED_CIDR=<当前Docker项目私网CIDR>
```

`PIPECAT_SAMPLE_RATE` 必须与媒体模块、ASR 和 TTS 的实际格式一致。电话场景优先以真实线路的 8 kHz 样本验证，不能只听 24/48 kHz 本地音频。

### 7.2 阿里云 NLS 实时 ASR

```dotenv
PIPECAT_STT_PROVIDER=aliyun-nls
ALIYUN_NLS_GATEWAY_URL=wss://nls-gateway-cn-shanghai.aliyuncs.com/ws/v1
ALIYUN_NLS_APPKEY=<项目AppKey>
ALIYUN_NLS_TOKEN_FILE=/run/secrets/aliyun-nls-token
ALIYUN_NLS_VOCABULARY_ID=<可选，已发布热词ID>
ALIYUN_NLS_CUSTOMIZATION_ID=<可选，已发布定制模型ID>
ALIYUN_NLS_MAX_SENTENCE_SILENCE_MS=800
```

Token 是短时凭据，生产应由刷新进程原子更新文件。`ALIYUN_NLS_TOKEN` 只适合受控测试。热词 ID 是云控制台发布后的 ID，不是逗号分隔的自由文本。

### 7.3 OpenAI-compatible ASR/TTS

当前 Pipecat TTS 适配器使用 OpenAI-compatible 接口：

```dotenv
PIPECAT_TTS_PROVIDER=openai
PIPECAT_OPENAI_BASE_URL=<兼容服务的API根地址；官方服务可留空>
PIPECAT_OPENAI_API_KEY=<语音服务Key>
PIPECAT_TTS_MODEL=<TTS模型名>
PIPECAT_TTS_VOICE=<音色ID>
```

如 ASR 选择 OpenAI Realtime：

```dotenv
PIPECAT_STT_PROVIDER=openai-realtime
PIPECAT_OPENAI_REALTIME_BASE_URL=<Realtime WebSocket地址>
PIPECAT_OPENAI_API_KEY=<支持Realtime和TTS的Key>
```

阿里云 NLS 与 OpenAI Realtime ASR 二选一即可；TTS 仍须单独配置。若同一 Key 不同时覆盖 ASR/TTS，应通过适配器拆分凭据，不能假设通用。

### 7.4 录音告知语和 Legacy TTS

FreeSWITCH 在录音和实时媒体启动前播放告知语，因此还必须二选一：

```dotenv
# FreeSWITCH原生TTS
FREESWITCH_TTS_ENGINE=<引擎>
FREESWITCH_TTS_VOICE=<音色>

# 或HTTP TTS适配器
FREESWITCH_TTS_HTTP_ENDPOINT=<返回media_uri或url的接口>
FREESWITCH_TTS_HTTP_TOKEN=<鉴权Token>
```

只配置 Pipecat 对话 TTS 仍不足以启动真实 FreeSWITCH 模式。

## 8. 大模型参数

固定规则话术使用：

```dotenv
LLM_PROVIDER=rule
```

需要根据客户实际表达生成回答时，使用：

```dotenv
LLM_PROVIDER=openai-compatible
OPENAI_BASE_URL=<大模型接口地址>
OPENAI_API_KEY=<大模型Key>
OPENAI_MODEL=<模型名>
LLM_ALLOWED_HOSTS=<批准的服务域名>
LLM_SEND_PII=false
LLM_REQUIRE_HTTPS=true
```

然后在 `/admin/settings` 的“AI 与语音”中开启“允许外部大模型”。环境变量负责连接和密钥，管理页面负责租户是否允许使用及选择的模型；两者缺一不可。

大模型不得自行决定价格、资格、合规承诺或实际预约结果。此类信息应来自已审核知识库或业务接口，并保留固定兜底话术和转人工策略。

## 9. 短信、录音和业务回调

### 9.1 短信

```dotenv
SMS_PROVIDER=http
SMS_PROVIDER_ENDPOINT=<短信适配器地址>
SMS_API_KEY=<发送凭据>
SMS_CALLBACK_URL=<平台短信状态回调地址>
SMS_WEBHOOK_TOKEN=<回调Token>
SMS_WEBHOOK_SECRET=<回调HMAC密钥>
```

供应商还需提供短信签名、已报备模板、号码格式、回执字段、失败码、限流和计费规则。当前平台使用通用 HTTP Bridge；供应商协议不同需由适配器转换。

### 9.2 录音

本地默认使用 SeaweedFS，不需要外部对象存储：

```dotenv
RECORDING_STORAGE_SERVICE_TOKEN=<内部强Token>
RECORDING_S3_ACCESS_KEY_ID=<本地S3访问ID>
RECORDING_S3_SECRET_ACCESS_KEY=<本地S3密钥>
RECORDING_S3_BUCKET=ai-outbound-recordings
RECORDING_S3_KEY_PREFIX=recordings
```

生产必须更换开发凭据、启用 HTTPS 来源校验、限制录音下载域名，并验证入库、播放、到期删除、备份和恢复。运营商侧原始录音的删除仍需单独对接供应商。

### 9.3 业务系统回调

在 `/admin/settings` 的“接口与回调”中填写非敏感参数：回调地址、超时、重试次数、退避时间和凭据引用。例如页面填写 `PRIMARY_CALLBACK`，实际密钥通过：

```dotenv
BUSINESS_WEBHOOK_SECRET_PRIMARY_CALLBACK=<真实密钥>
```

必须验证签名、幂等、超时重试、失败队列和接收方对账；保存成功不等于业务系统已收到数据。

## 10. 浏览器座席与人工接管

纯 AI 且不转人工时可暂不启用 WebRTC。需要浏览器坐席接听时，至少配置：

```dotenv
WEBRTC_ENABLED=true
WEBRTC_WSS_URL=wss://<正式域名>:7443
WEBRTC_SIP_DOMAIN=<正式域名>
TURN_URLS=<STUN/TURN地址列表>
TURN_SHARED_SECRET=<独立强密钥>
FREESWITCH_DIRECTORY_TOKEN=<独立目录Token>
PUBLIC_IPV4=<服务器固定公网IPv4>
TURN_REALM=<正式域名>
TLS_CERT_FILE=<证书链路径>
TLS_KEY_FILE=<私钥路径>
FREESWITCH_WSS_PEM=<FreeSWITCH合并PEM路径>
FREESWITCH_IMAGE=<审核后的固定digest镜像>
FREESWITCH_CONFIG_DIR=<完整私有配置目录>
```

生产还需开放 HTTPS、WSS、STUN/TURN 和指定 RTP 端口；`5432`、`6379`、`8001`、`8002`、`8021` 不得暴露公网。必须用真实浏览器、麦克风、耳麦、企业网络和移动热点验收。

## 11. 管理中心配置顺序

### 11.1 用户与座席

入口：`/admin/users`

1. 创建正式管理员、班组长和座席。
2. 验证登录、角色权限、停用、改密和退出。
3. 生产关闭演示账号。

### 11.2 外呼线路

入口：`/admin/lines`

| 页面字段 | 填写内容 |
|---|---|
| 名称 | 运营可识别的线路名称 |
| 服务商 | `HTTP Bridge`；`Mock` 仅测试 |
| 网关地址 | 内部 Voice Gateway 或经批准的语音桥接地址 |
| 凭据引用 | 如 `PRIMARY_PBX`，对应 `TELEPHONY_SECRET_PRIMARY_PBX` |
| 主叫号码 | 运营商批准的号码 |
| 并发数 | 不超过已购和实测容量 |
| 优先级、权重 | 多线路选路策略 |
| 启用 | 完成配置和受控验证后再开启 |

页面线路记录不会替代 FreeSWITCH SIP 配置，也不会保存 SIP 密码。

### 11.3 系统配置

入口：`/admin/settings`

1. **并发容量**：设置租户上限；实际值取租户、任务、线路和平台硬上限的最小值。
2. **AI 与语音**：选择规则/大模型、ASR/TTS 名称、音色、语言、历史轮数、回复长度、禁用表达和兜底话术。
3. **短信配置**：启用状态、服务商、发送方、接口和挂机模板。
4. **合规策略**：DNC、明确同意、允许时段、时区、日次数、触达间隔、录音告知和数据保留期。
5. **接口与回调**：回调地址、凭据引用、超时和重试。

页面保存后必须刷新或离开再返回，确认数据回显。提高页面并发不会自动购买运营商、ASR、TTS 容量，也不会扩容服务器。

### 11.4 业务内容

按以下顺序配置：

1. `/admin/knowledge`：知识库；
2. `/admin/scripts`：话术和流程版本；
3. `/admin/contacts`：已获得合法授权的客户；
4. `/admin/campaigns`：模式、名单、线路、并发、重试、录音和短信策略。

任务启动前必须检查话术已发布、客户未进入 DNC、当前时间允许外呼、线路启用且容量足够。

## 12. 参数如何生效

| 修改类型 | 生效方式 |
|---|---|
| 管理中心容量、AI、短信、合规、回调 | 保存后由新请求读取；应刷新页面核对回显 |
| `.env`/密钥文件 | 排空业务后重新创建相关容器 |
| FreeSWITCH SIP/Dialplan | 在维护窗口加载配置并核对网关状态；关键变更建议受控重启 |
| ASR Token 文件 | 新建或重连会话重新读取；由刷新程序原子替换 |
| 路由安全文件 | 按发布流程校验后原子替换，并重新检查准入状态 |

不要在活跃通话期间切换语音流水线、线路或密钥。先暂停新任务、等待通话排空、保存状态，再变更并回归。

## 13. 启动与只读检查

### 13.1 服务状态

```bash
docker compose --env-file .env \
  -f docker-compose.yml \
  -f docker-compose.observability.yml \
  ps

curl --fail http://127.0.0.1:8000/health
curl --fail http://127.0.0.1:8000/readyz
curl --fail http://127.0.0.1:8003/readyz
```

### 13.2 FreeSWITCH 只读检查

```text
status
sofia status gateway <FREESWITCH_GATEWAY>
show registrations
```

预期：FreeSWITCH 正常运行、目标网关为可用状态、注册模式线路有有效注册。就绪通过仍不代表客户电话双向有声。

### 13.3 生产配置门禁

```bash
python scripts/check-version-constraints.py \
  --production-env /secure/path/production.env
```

只有受控测试目标确认后，才可执行 SIPp 或 `real_voice_acceptance.py --confirm-dial`；这些操作会产生真实呼叫，不能作为普通健康检查运行。

## 14. 真实上线验收清单

### 14.1 必须实测的业务链路

- `human_only`：纯人工外呼、双向声音、DTMF、挂机和录音；
- `mixed_human_first`：录音播放完转人工；
- `ai_only`：至少三轮对话，覆盖静音、口音、插话和正常挂机；
- `ai_handoff`：AI 识别转人工、座席振铃、接听后双向有声；
- AI + 短信：挂机后发送、状态回执和失败重试。

### 14.2 每通电话核对

- 供应商 Call ID、拨号/接通/结束事件和失败原因；
- 客户与机器人双向声音，机器人声音不被自身 ASR 重复识别；
- ASR partial/final、AI 决策、TTS 开始/结束和打断；
- 转人工、座席状态、录音连续性和双声道；
- 业务回调签名、幂等、短信回执和供应商账单。

### 14.3 容量与故障

按目标并发的 10%、25%、50%、100% 逐级测试，再覆盖完整业务高峰。分别注入 PBX、ASR、TTS、LLM、Redis、Webhook 故障和 Worker 重启，确认限流、重试、停拨、回调补发和数据一致性。

## 15. 常见问题

| 现象 | 首先检查 |
|---|---|
| 页面正常但电话没拨出 | `VOICE_GATEWAY_DRIVER` 是否仍为 `mock`；安全路由是否为空；线路是否启用 |
| 网关启动失败 | 六份独立强凭据、ESL、告知语 TTS、路由文件和回调 origin |
| SIP 注册失败 | Proxy、端口、协议、账号、Realm、公网 IP 白名单和防火墙 |
| 接通但无声音 | RTP 白名单/NAT、端口范围、编码、媒体模块和采样率 |
| 客户说话没有文字 | ASR AppKey/Token、并发额度、PCM 格式和 WebSocket |
| 机器人不说话 | Pipecat TTS Key/Base URL/模型/音色；告知语 TTS 是另一条配置 |
| 只能按固定话术回答 | `LLM_PROVIDER=rule`；外部 LLM 未在环境和租户两层同时启用 |
| 转人工后无声 | 浏览器 SIP 注册、WSS、TURN、RTP、耳麦权限和 FreeSWITCH 桥接事件 |
| 并发低于页面数值 | 租户、任务、线路、平台、运营商和云语音配额中的最小值 |
| 有录音 URL 但不能播放/删除 | 来源白名单、对象存储凭据、录音入库状态和供应商原始录音策略 |

## 16. 对接资料收集表

对接第三方时，先让供应商按下面清单提供文档。密钥不得直接写进此表。

| 类别 | 待确认资料 | 状态/负责人 |
|---|---|---|
| 企业 SIP | 接入地址、协议、鉴权、域、批准主叫、号码格式、编码、RTP 白名单、并发/CPS、资费、CDR | 待填写 |
| ASR | 服务商、地域、项目/AppKey、Token 刷新、采样率、并发、热词、费用 | 待填写 |
| TTS | Base URL、接口协议、模型、音色、采样率、流式能力、并发、商用许可、费用 | 待填写 |
| LLM | Base URL、模型、上下文限制、并发、数据保留、内容合规、费用 | 按需 |
| 短信 | 接口、签名、模板、回执、限流、失败码、费用 | 按需 |
| WebRTC | 域名、公网 IP、证书、TURN、端口、防火墙 | 转人工时需要 |
| 业务回调 | URL、签名规则、幂等键、重试、接收方联系人 | 按需 |
| 录音 | 存储区域、保留/删除、加密、备份、供应商原始录音处理 | 必须确认 |

## 17. 当前交付边界

| 状态 | 结论 |
|---|---|
| 源代码 | 本次未修改业务代码 |
| 配置手册 | 已按当前源码和本地运行状态整理 |
| 静态检查 | 文档内部链接、配置参数名、Git diff 格式和常见密钥特征检查通过 |
| 本地服务 | 只读观察到业务容器健康；未因本手册重启 |
| 开发环境功能测试 | 本次未执行新增功能测试，文档变更不改变业务行为 |
| 测试环境发布 | 未验证 |
| 真实线路/云语音 | 未配置完成、未验证 |
| 真机测试 | 未验证 |
| 生产发布 | 未验证 |

进一步操作说明见：[安装部署](../INSTALL.md)、[FreeSWITCH 接入](freeswitch-integration.md)、[Pipecat 接入](pipecat-integration.md)、[本机媒体验收](voismart-local-media.md)、[浏览器坐席](browser-webrtc.md)、[防盗打](toll-fraud-protection.md)、[生产验收](production-acceptance.md)和[系统操作手册](operator-manual.md)。
