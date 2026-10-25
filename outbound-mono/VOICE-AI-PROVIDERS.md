# 六套语音方案接入与对比

入口：管理员后台 → **语音方案对比**（`/admin/voice-ai`），系统设置也有直接入口。需要管理员 `settings:write` 权限。

## 现有接入核对

修改前 NativeMedia 仅支持旧版阿里 NLS／OpenAI 实时转写以及 OpenAI HTTP 合成；文本模型通过 OpenAI 兼容端点调用，后台 ASR／TTS 名称没有逐供应商映射到媒体协议。没有证据表明以下六套组合已经完成真实账号或电话验收。

本次新增三段式：阿里 Fun-ASR 8k＋千问＋CosyVoice；火山流式识别＋豆包文本模型＋双向流式合成；腾讯实时识别＋TokenHub 混元＋流式文本合成。端到端：豆包实时语音、千问 Omni Realtime、百度 Flex。

六套共用实际电话媒体与对比测试的协议适配代码，不以一个通用 OpenAI 地址冒充不同语音协议。旧链路保留，默认仍为 `legacy`，不会自动切换现有部署。

## 账号与服务开通清单

申请核对日期：2026-10-24。六套组合对应四家云账号；阿里的两套可共用同地域、同业务空间下有对应模型权限的百炼 API Key。火山需要区分豆包语音凭据与火山方舟文本模型凭据；腾讯需要区分语音服务的 SecretId／SecretKey 与 TokenHub API Key。

建议统一用企业管理的账号申请，记录账号负责人、所属项目、地域、服务状态、试用／计费状态、实际并发及 RPM／TPM 限额。认证材料及审批要求以各控制台提示为准。先完成试用或按量 API 开通，联调确认后再确定正式用量。

| 方案           | 需要开通的服务                                                            | 申请入口与操作                                                                                                                                                                                                                                                                                                                                                                            | 申请完成后保留                                                                                                              |
| -------------- | ------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| 阿里云三段式   | 百炼：Fun-ASR 8k 实时识别、千问文本模型、CosyVoice 合成                   | [百炼控制台](https://bailian.console.aliyun.com/) → 北京地域 → 确认模型权限；[API Key 管理](https://bailian.console.aliyun.com/cn-beijing/model/settings/api-key)创建按量 API Key                                                                                                                                                                                                         | API Key、地域、业务空间 ID、可调用模型 ID；CosyVoice 音色 ID                                                                |
| 阿里云端到端   | 百炼：Qwen Omni Realtime                                                  | 同一百炼账号 → 确认 `qwen3.8-omni-flash-realtime` 可调用；获取业务空间 ID 与对应 WebSocket 调用地址                                                                                                                                                                                                                                                                                       | API Key、WorkspaceId、地域、实际 WSS 地址、实时模型和音色                                                                   |
| 火山引擎三段式 | 豆包流式语音识别 2.0、豆包语音合成 2.0 双向流式；火山方舟豆包文本模型     | [豆包语音控制台](https://console.volcengine.com/speech) → 开通管理，分别开通 ASR／TTS；API Key 管理获取语音凭据。[火山方舟](https://console.volcengine.com/ark) → 开通文本模型 → API Key 管理；需要时创建在线推理接入点                                                                                                                                                                   | ASR、TTS 各自的凭据类型、API Key 或 AppID＋Access Token、资源 ID；TTS 音色；方舟 API Key、文本模型 ID 或 `ep-...` 接入点 ID |
| 火山引擎端到端 | 豆包端到端实时语音大模型，当前实现对应 O2.0                               | 豆包语音控制台 → 开通端到端实时语音服务；向供应商确认 O2.0 WebSocket 接口权限                                                                                                                                                                                                                                                                                                             | 对应 AppID＋Access Token（或实际发放的新版凭据类型）、O2.0 权限、音色、资源 ID、并发限额                                    |
| 腾讯云三段式   | 实时语音识别 WebSocket、流式文本语音合成 WebSocket、TokenHub 混元文本模型 | [ASR 控制台](https://console.cloud.tencent.com/asr)和[TTS 控制台](https://console.cloud.tencent.com/tts)分别开通；[CAM API 密钥](https://console.cloud.tencent.com/cam/capi)获取 SecretId／SecretKey；[账号中心](https://console.cloud.tencent.com/developer)获取 AppID；[TokenHub API Key](https://console.cloud.tencent.com/tokenhub/apikey?regionId=1)创建模型密钥并在启用管理开通混元 | 账号 AppID、SecretId、SecretKey；TokenHub API Key、地域、模型 ID（当前预设 `hy3`）；音色 VoiceType                          |
| 百度端到端     | 端到端语音语言大模型 Flex 公测接口                                        | [Flex 官方文档](https://ai.baidu.com/ai-doc/SPEECH/Wmtlcgi7c) →「合作咨询」申请公测权限；[语音控制台](https://console.bce.baidu.com/ai-engine/speech/overview/index)核对开通状态；[API Key 管理](https://console.bce.baidu.com/iam/#/iam/apikey/list)创建可调用该接口的 IAM API Key                                                                                                       | Flex 开通确认、支持 Bearer 的 API Key（文档示例为 `bce-v3/...`）、模型及音色、限流与计费说明                                |

控制台入口需要登录；本次仅核对公开官方文档，没有代注册、提交申请、购买服务或查看账号内权限。

### 每套方案对应的系统参数

下表的“账号侧获取”是需要申请后补充的内容；“当前预设”是代码默认配置，须以实际开通权限确认，不需要额外申请同名密钥。完整可填写模板见 [voice-ai-application.env.example](voice-ai-application.env.example)，模板不自动启用真实媒体。

| 方案       | 账号侧获取 → 系统变量                                                                                                                                                                             | 当前模型与音色预设                                                                                                                                                                                            |
| ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 阿里三段式 | 百炼 API Key → `DASHSCOPE_API_KEY`                                                                                                                                                                | `VOICE_AI_ALI_ASR_MODEL=fun-asr-flash-8k-realtime`；`VOICE_AI_ALI_LLM_MODEL=qwen-flash`；`VOICE_AI_ALI_TTS_MODEL=cosyvoice-v3-flash`；`VOICE_AI_ALI_VOICE=longxiaochun_v3`                                    |
| 阿里端到端 | 同一空间 API Key → `DASHSCOPE_API_KEY`；含真实 WorkspaceId 的地址 → `VOICE_AI_ALI_REALTIME_URL`                                                                                                   | `VOICE_AI_ALI_REALTIME_MODEL=qwen3.8-omni-flash-realtime`；`VOICE_AI_ALI_REALTIME_VOICE=Tina`                                                                                                                 |
| 火山三段式 | ASR Key → `VOICE_AI_VOLC_ASR_API_KEY`；TTS Key → `VOICE_AI_VOLC_TTS_API_KEY`；方舟 Key → `VOICE_AI_VOLC_LLM_API_KEY`；文本模型／接入点 ID → `VOICE_AI_VOLC_LLM_MODEL`；旧版凭据可分别配置，见下文 | `VOICE_AI_VOLC_ASR_RESOURCE=volc.seedasr.sauc.duration`；`VOICE_AI_VOLC_TTS_RESOURCE=seed-tts-2.0`；`VOICE_AI_VOLC_VOICE=zh_female_vv_uranus_bigtts`                                                          |
| 火山端到端 | 对应语音 Key → `VOICE_AI_VOLC_REALTIME_API_KEY`；或独立旧版 `VOICE_AI_VOLC_REALTIME_APP_ID`＋`VOICE_AI_VOLC_REALTIME_ACCESS_KEY`                                                                  | `VOICE_AI_VOLC_REALTIME_MODEL=1.2.1.1`；`VOICE_AI_VOLC_REALTIME_VOICE=zh_female_vv_jupiter_bigtts`；资源 ID 当前固定为 `volc.speech.dialog`                                                                   |
| 腾讯三段式 | AppID → `VOICE_AI_TENCENT_APP_ID`；SecretId → `VOICE_AI_TENCENT_SECRET_ID`；SecretKey → `VOICE_AI_TENCENT_SECRET_KEY`；TokenHub API Key → `VOICE_AI_TENCENT_LLM_API_KEY`                          | `VOICE_AI_TENCENT_ASR_MODEL=8k_zh`；`VOICE_AI_TENCENT_LLM_MODEL=hy3`；`VOICE_AI_TENCENT_VOICE=101001`；按当前国内文档显式设置 `VOICE_AI_TENCENT_LLM_URL=https://tokenhub.tencentmaas.com/v1/chat/completions` |
| 百度端到端 | IAM API Key → `VOICE_AI_BAIDU_API_KEY`                                                                                                                                                            | `VOICE_AI_BAIDU_REALTIME_MODEL=qianfan-realtime-flex-v1`；`VOICE_AI_BAIDU_REALTIME_VOICE=default`                                                                                                             |

阿里北京地域的实时地址填写为 `wss://实际WorkspaceId.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime`。系统会自行添加 `model` 参数，变量中不填写查询参数。阿里当前三段式默认域名和预设音色按北京地域准备，若申请其他地域，需一并调整对应端点和音色。[百炼密钥说明](https://help.aliyun.com/zh/model-studio/get-api-key/)、[Omni 地址要求](https://help.aliyun.com/zh/model-studio/realtime)、[CosyVoice 音色](https://help.aliyun.com/en/model-studio/cosyvoice-voice-list)。

腾讯语音 SecretId／SecretKey 用于签名，TokenHub 使用单独的 Bearer API Key；申请国内普通 API 服务并确认 `hy3` 或实际替代模型可调用，不能仅凭 Coding Plan／Token Plan 的专用 Key 判断该端点已开通。国内 TokenHub 官方当前默认域名为 `tokenhub.tencentmaas.com`，代码默认值与本模板均已更新为该地址；显式配置其他地址的部署继续使用其配置值。[语音识别准备](https://cloud.tencent.com/document/product/1093/48982)、[流式合成准备](https://cloud.tencent.com/document/product/1073/108595)、[TokenHub 密钥](https://cloud.tencent.com/document/product/1823/130090)、[当前域名与模型列表](https://cloud.tencent.com/document/product/1823/130078)。

百度须取得 Flex 接口权限以及适用于 Bearer 方式的 IAM API Key。传统应用界面的 API Key／Secret Key 是另一条 OAuth 凭据链，不能把其中的 API Key 直接当作当前系统的 Bearer Key。创建 IAM Key 时确认 AI 开放能力及 Flex 接口范围已经授权。[Flex 公测及鉴权](https://ai.baidu.com/ai-doc/SPEECH/Wmtlcgi7c)、[IAM Key 申请说明](https://cloud.baidu.com/doc/AI_REFERENCE/s/Hm5us339w)。

### 火山新版鉴权、独立配置与旧版兼容

已支持 ASR、TTS、O2.0 实时语音各自的 `X-Api-Key` 鉴权；API Key 方式不混入 AppID／Access Token 或端到端旧版固定 App-Key。仍携带对应 Resource-Id 和连接 ID。旧版 ASR 使用 `X-Api-App-Key` 承载 AppID；旧版 TTS／端到端使用 `X-Api-App-Id`，端到端另带官方要求的固定 App-Key。[API Key 使用说明](https://docs.volcengine.com/docs/DoubaoVoice/APIKeyUsage?lang=zh)、[ASR 鉴权](https://docs.volcengine.com/docs/DoubaoVoice/LargemodelstreamingautomaticspeechrecognitionAPI?lang=zh)、[TTS 鉴权](https://docs.volcengine.com/docs/DoubaoVoice/WebSocketBidirectionalStreaming-V3?lang=zh)、[O2.0 协议](https://docs.volcengine.com/docs/DoubaoVoice/End-to-endreal-timespeechlargemodelAPIaccessdocument?lang=zh)。

| 服务   | 新版 Key（推荐）                 | 独立旧版凭据（成对配置）                                             |
| ------ | -------------------------------- | -------------------------------------------------------------------- |
| ASR    | `VOICE_AI_VOLC_ASR_API_KEY`      | `VOICE_AI_VOLC_ASR_APP_ID`＋`VOICE_AI_VOLC_ASR_ACCESS_KEY`           |
| TTS    | `VOICE_AI_VOLC_TTS_API_KEY`      | `VOICE_AI_VOLC_TTS_APP_ID`＋`VOICE_AI_VOLC_TTS_ACCESS_KEY`           |
| 端到端 | `VOICE_AI_VOLC_REALTIME_API_KEY` | `VOICE_AI_VOLC_REALTIME_APP_ID`＋`VOICE_AI_VOLC_REALTIME_ACCESS_KEY` |

每个服务独立按以下顺序选取，允许 ASR 新版、TTS 旧版等混合配置：

1. 本服务 API Key。
2. 本服务 AppID＋Access Token。只配置其中一项时，明确报告另一项缺失，不从共享凭据拼接或回退。
3. 仅当本服务没有任何独立凭据时，回退到共享 `VOICE_AI_VOLC_API_KEY`。
4. 无共享 Key 时，兼容原来的共享 `VOICE_AI_VOLC_APP_ID`＋`VOICE_AI_VOLC_ACCESS_KEY`。

上述密钥均支持 `_FILE`。独立 Key 的密钥文件不可读或内容为空时报告该项缺失，不退回其他凭据；API Key 与 Token 不返回浏览器明文、不写入策略快照；后台保存的凭据使用独立主密钥加密存储。后台显示各服务选用的鉴权方式及缺失项。快照只保存鉴权方式和变量引用，后续新旧模式配置切换不改变已有快照；更早生成且没有鉴权引用的快照继续使用原共享旧版凭据。鉴权失败不自动换 Key 或换模式重试。

当前端到端仍为 O2.0，模型参数 `1.2.1.1`；新版 Key 不会把协议自动变成 Seeduplex。申请时仍须确认 O2.0 资源、模型和音色权限，真实服务接受情况待账号联调验证。

### 申请完成后的资料清单

- 四家账号的企业／项目归属、地域、联系人。
- 各服务开通确认、模型 ID、音色 ID、资源 ID、实际调用域名。
- 按上述表格分类保存的密钥；若控制台只展示一次，创建时保存完整值。
- 免费额度有效期、是否已启用后付费、各服务并发和模型 RPM／TPM 限额。
- 百度 Flex 审批结果；火山 O2.0 权限和凭据类型；千问 WorkspaceId。

可以向百度或火山商务这样描述申请用途：

> 我们自研 AI 电话外呼／客服系统，计划通过服务端 WebSocket 接入中文实时语音，用固定话术和相同音频做多供应商对比。首期小规模验证识别、语音回复、响应延迟、打断和通话录音。请确认对应服务／模型／协议权限，提供凭据获取方式、可用音色、测试配额、并发限制、计费说明及后续扩容流程。

密钥可在后台「供应商账号与密钥配置」填写，也可使用服务端未提交的环境变量或密钥文件；参数清单与代码仓库仅保留空白模板。拿到账号后先检查真实 API，再接专用测试号码，不把“创建了 Key”记为“服务已验证”。

## 配置

管理员进入「语音方案对比」→「供应商账号与密钥配置」，按六套方案选择并填写 API Key、AppID、Access Token 或 SecretId／SecretKey，以及对应模型、音色和资源 ID。这些是 API 凭据，无需填写云网站登录密码。阿里两套方案共用百炼 Key；火山 ASR、TTS、实时语音分别配置，方舟文本 Key 独立配置。

点击「加密保存供应商配置」后刷新核对。密钥输入框清空，仅显示已配置状态；留空保持原值，点击对应「清空」再保存可停用该字段。模型留空使用默认值。后台按租户保存 AES-256-GCM 加密版本；新测试和新通话固定当时的配置版本，之后修改不会切换已有会话使用的凭据。话术、结果、审计和快照不保存秘密明文，审计仅记录修改的字段名。

服务端先配置 `VOICE_AI_CONFIG_MASTER_KEY_FILE`，文件内容为 Base64 编码的 32 字节随机主密钥，也可使用 `VOICE_AI_CONFIG_MASTER_KEY`。此主密钥仅由平台部署人员管理，不能在浏览器修改；缺失时后台明确禁止保存。数据库和主密钥应分别安全备份，丢失主密钥将无法解密已有配置。111 测试环境已经配置独立主密钥。

租户后台配置优先于平台环境变量及 `_FILE`；未配置字段兼容原有服务端配置。显式清空的密钥会阻止该字段回退至环境变量或密钥文件，需重新填入有效值才能使用。阿里 Omni 工作空间地址允许在后台填写，严格限制为官方北京或新加坡 WSS 域名；其他端点由平台环境管理。配置齐全不代表权限、余额或供应商容量已经验证。

真实电话链路另需现有 FreeSWITCH ESL、PCM 媒体模块、回调签名与配额配置：

```dotenv
VOICE_GATEWAY_DRIVER=freeswitch_esl
VOICE_AI_PIPELINE=pipecat
VOICE_AI_PROFILES_ENABLED=true
# 沿用原部署的 PBX、签名、媒体 WS 可达地址与配额配置。
```

| 组合                | 必需配置                                                                                                                       | 模型／音色配置                                                                             |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------ |
| aliyun-cascade      | DASHSCOPE_API_KEY                                                                                                              | VOICE_AI_ALI_ASR_MODEL、VOICE_AI_ALI_LLM_MODEL、VOICE_AI_ALI_TTS_MODEL、VOICE_AI_ALI_VOICE |
| volcengine-cascade  | VOICE_AI_VOLC_ASR_API_KEY、VOICE_AI_VOLC_TTS_API_KEY（或各自完整旧版凭据）；VOICE_AI_VOLC_LLM_API_KEY、VOICE_AI_VOLC_LLM_MODEL | VOICE_AI_VOLC_ASR_RESOURCE、VOICE_AI_VOLC_TTS_RESOURCE、VOICE_AI_VOLC_VOICE                |
| tencent-cascade     | VOICE_AI_TENCENT_APP_ID、VOICE_AI_TENCENT_SECRET_ID、VOICE_AI_TENCENT_SECRET_KEY、VOICE_AI_TENCENT_LLM_API_KEY                 | VOICE_AI_TENCENT_ASR_MODEL、VOICE_AI_TENCENT_LLM_MODEL、VOICE_AI_TENCENT_VOICE             |
| volcengine-realtime | VOICE_AI_VOLC_REALTIME_API_KEY（或独立完整旧版凭据；兼容共享回退）                                                             | VOICE_AI_VOLC_REALTIME_MODEL、VOICE_AI_VOLC_REALTIME_VOICE                                 |
| aliyun-realtime     | DASHSCOPE_API_KEY、VOICE_AI_ALI_REALTIME_URL                                                                                   | VOICE_AI_ALI_REALTIME_MODEL、VOICE_AI_ALI_REALTIME_VOICE                                   |
| baidu-realtime      | VOICE_AI_BAIDU_API_KEY                                                                                                         | VOICE_AI_BAIDU_REALTIME_MODEL、VOICE_AI_BAIDU_REALTIME_VOICE                               |

- 阿里 Omni 默认选 `qwen3.8-omni-flash-realtime`，必须填写实际业务空间域名：`wss://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime`。禁止保留花括号占位符。北京／新加坡的 Key 与端点须匹配。
- 火山文本模型须填账号可调用的模型名或推理接入点 ID；不猜测账号中的 `ep-...`。
- 火山端到端使用公开文档中 O2.0 WebSocket 二进制协议，默认模型参数 `1.2.1.1`。端点／音色／资源需与实际开通产品匹配；本次没有切换至另一套 Seeduplex 协议。
- 百度默认 `qianfan-realtime-flex-v1`，官方说明为公测，需先取得权限。
- 阿里合成默认使用具有系统音色的 `cosyvoice-v3-flash`＋`longxiaochun_v3`；如改用其他型号或复刻音色，须按供应商授权配置对应音色。
- `VOICE_AI_{ALI,VOLC,TENCENT}_{ASR,TTS,LLM}_URL` 和 `VOICE_AI_{ALI,VOLC,BAIDU}_REALTIME_URL` 可由平台配置；生产必须使用 TLS，不允许 URL 凭据、查询参数或片段。浏览器不能设置任意服务地址。

## 通话切换

在对比页选择三段式或端到端、选择供应商，允许外部模型调用后保存。所选组合通过原生媒体链路执行，任务应使用“继承”或实时媒体，显式使用旧链路的任务会被拦截。缺少平台媒体开关或供应商配置时，在真实拨号前拦截。

每次通话尝试将组合 ID、模型、音色、端点及配置版本写入原有话术策略快照，再传给媒体层；凭据仅在服务端按引用读取。后台切换不改变已经开始的通话。固定开场／业务结束语继续按业务控制面指令播放，普通端到端回复由语音模型直接理解原音频生成。

三段式识别为持续流式；文本模型使用 SSE，当前协调器收齐回复并完成现有文字审核后再调用双向流式 TTS。端到端接口持续收发音频。后台可选两种播放方式：默认 `reviewed` 先收齐模型回复和对应转写、审核通过后播放；`streaming` 边生成边播放，整段审核不通过时会中止，但此前可能已经播出部分回复。切换播放方式也会固定到每次通话快照。不能把对比页原始 API 首包时间直接当成电话端实际出声时间。

## 同一批样本对比

1. 选已有话术或填写测试话术；准备相同的 1—10 条单声道 16bit PCM WAV（每条 0.2—30 秒）。
2. 保存样本组，系统固定话术全文、SHA-256 与原始音频。之后修改话术模板不改变此样本组。
3. 选择六套组合并运行，逐组执行、逐条记录，失败／缺少配置不会冒充成功，也不阻止其他组合执行。
4. 查看转写、回复、首音频时间、用户音频结束至首音频时间、总耗时及回复 WAV 试听。结果保存于 MySQL，刷新或重新登录后可查看。
5. 文字样本仅覆盖三段式的文本模型和合成，不覆盖 ASR；端到端需提供音频，不能使用文字绕过语音理解。

对比页调用真实供应商会产生费用。每个运行请求有幂等编号，网络失败不自动重试；运行中／结果未知时先刷新核对，避免重复提交。应用中断留下的 `running` 记录代表结果未确认，不代表仍在执行或已经成功。

当前对比为独立音频样本的单轮测试，不自动拨打客户号码、不自动计算转化率或宣称供应商优劣。电话线路、打断体验、业务多轮闭环、实际并发和费用仍须用真实账号／线路验证。

## 官方协议依据

- [Fun-ASR 客户端事件](https://help.aliyun.com/zh/model-studio/fun-asr-client-events)
- [CosyVoice WebSocket](https://help.aliyun.com/zh/model-studio/cosyvoice-websocket-api)
- [千问 Omni 实时模型](https://help.aliyun.com/zh/model-studio/realtime)及[客户端事件](https://help.aliyun.com/zh/model-studio/client-events)
- [火山流式识别](https://www.volcengine.com/docs/6561/1354869)、[双向流式合成](https://www.volcengine.com/docs/6561/1329505)、[实时语音协议](https://www.volcengine.com/docs/6561/1594356)
- [腾讯实时识别](https://cloud.tencent.com/document/product/1093/48982)、[流式文本合成](https://cloud.tencent.com/document/product/1073/108595)、[混元调用](https://intl.cloud.tencent.com/zh/document/product/1300/80695)
- [百度 Flex](https://ai.baidu.com/ai-doc/SPEECH/Wmtlcgi7c)

接入文档核对日期：2026-10-24。公开文档可证明接口存在，不能证明账号已开通、代码已通过真实供应商验证或电话容量达标。
