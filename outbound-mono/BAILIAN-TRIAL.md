# 百炼多模态交互套件：平台内业务试聊

本功能为独立的 `aliyun-multimodal-suite` 浏览器通道，入口 `/admin/bailian-trial`，不加入电话方案枚举，不创建 CallSession、外呼任务、拨号工作或 PBX 命令。原六套语音组合和电话开关保持原值。

## 范围与业务链路

管理员选择现有测试客户、填写或引用话术，单独确认本次外部供应商调用授权，创建试聊。支持文字输入、浏览器麦克风 16 kHz PCM 输入、持续 WebSocket 音频传输、点击开始/结束说话、手动打断和多轮会话。第一期采用供应商 `push2talk`，不是自动判停的连续双工。输入最长 30 秒、会话最长 5 分钟、单实例平台最多 4 路且每个管理员最多 1 路。当前会话调度按单实例设计，多实例需要另行补齐共享会话租约和路由。

百炼通过平台工具查询业务资料和本次用户转写证据，把意向、跟进时间、备注保存为试聊草稿。管理员核对录音与转写后，结束试聊，确认写入现有客户的线索跟进记录。无负责人时使用已有领取逻辑，再使用已有跟进逻辑；整个操作在同一平台事务中完成。重复确认返回原结果，内容不一致返回 409。不会自动进行真实预约、转人工、短信或下一次拨号。

结果必须标记为“试聊草稿 / 等待人工确认”，不允许模型声称已完成真实预约。用户拒绝可登记 `declined`，人工确认后跟进阶段为 `invalid`；这不自动替代平台 DNC 或联系授权管理。

## 用户需要准备的账号和配置

| 项目 | 提供/配置位置 | 用途 |
| --- | --- | --- |
| 阿里云百炼账号，已开通多模态交互开发套件 | 阿里云控制台 | 应用发布、后付费和并发配额 |
| 百炼 API Key | 试聊页面加密保存 `DASHSCOPE_API_KEY`，或服务端 `_FILE` | 套件及独立输出 ASR；不要发在聊天或提交 Git |
| Workspace ID | `VOICE_AI_BAILIAN_WORKSPACE_ID` | 账号实际支持的工作空间 |
| App ID | `VOICE_AI_BAILIAN_APP_ID` | 已发布、支持纯音频和自定义业务工具的应用；不是 Omni 模型 ID |
| 应用版本标记 | `VOICE_AI_BAILIAN_APP_REVISION` | 可追溯配置，需与控制台发布记录一致 |
| ASR、文本模型、TTS 型号 | 百炼应用控制台 | 套件内选型；第一期不要用不支持工具调用的轻量闲聊配置 |
| 音色 ID（可选） | `VOICE_AI_BAILIAN_VOICE` | 留空沿用应用音色；输出请求 PCM/24 kHz |
| 业务工具独立随机密钥 | `VOICE_AI_BAILIAN_TOOL_API_KEY` 与插件 `X-API-KEY` | 两端一致；不可使用百炼 API Key 代替 |
| 公网 HTTPS 工具回调域名 | 百炼插件配置 | 指向 `/api/v1/webhooks/bailian/trial-tool`；仅开放这一业务回调即可 |
| 输出独立 ASR 权限和模型 | 现有阿里三段式 ASR 配置 | 审核实际生成的音频，须有句子时间戳；不是直接相信套件返回文本 |
| 阿里云内容安全增强版权限 | 服务端 `VOICE_AI_SAFETY_ACCESS_KEY_ID`、`VOICE_AI_SAFETY_ACCESS_KEY_SECRET` 及对应 `_FILE` | `TextModerationPlus` 的 `llm_response_moderation`，使用最小权限 RAM 身份 |
| 审核地域/端点 | `VOICE_AI_SAFETY_REGION`、`VOICE_AI_SAFETY_ENDPOINT` | 默认上海，需与实际开通地域匹配 |
| 平台加密主密钥 | 运维配置 `VOICE_AI_CONFIG_MASTER_KEY_FILE` | 沿用现有 AES-256-GCM 配置体系，用户无需提供明文给开发者 |
| 测试业务资料 | 测试客户、话术、意向分类和跟进时间规则 | 建议独立测试租户；不填真实客户敏感资料 |

`App ID` 和本地版本标记不会自动锁住百炼控制台的远端配置。对比测试期间禁止原地发布新配置；需要变更时使用单独应用/版本并更新标记。平台保存本地话术及加密配置版本，供应商实际接受情况必须联调核对。

服务端默认套件地址为 `wss://dashscope.aliyuncs.com/api-ws/v1/inference`。`VOICE_AI_BAILIAN_URL` 仅允许服务端设置；浏览器不能设置任意供应商 URL。本地协议夹具使用回环 `ws://`，生产要求 WSS。

## 百炼应用和工具配置

1. 创建并发布支持自定义工具的应用。提示词显式引用自定义变量 `business_script`，将其作为本次业务约束；不要开放无关联网搜索或知识工具。
2. 从 `docs/bailian-trial-tool.openapi.yaml` 配置工具，替换示例域名，设置 `X-API-KEY`。应用中绑定并发布该工具。
3. `trial_id`、`trial_token` 必须映射为**业务透传参数**，从本次 Start 的 `parameters.biz_params.user_defined_params` 取得，不能让模型生成或向用户询问。参数的配置位置及实际透传行为需在当前控制台联调确认。
4. 调用 `business_context` 获取业务话术及 `user_evidence`（当前会话最终转写，每项带 `seq`）。需要时重新读取，不能缓存其他会话的证据。
5. 调用 `save_intent`：`event_id` 是本次工具操作唯一编号，`intent` 为 `interested / declined / uncertain`，`note` 是备注，`evidence_seq` 引用上一步用户转写。`next_at` 可为空；填写时必须为未来的 UTC ISO-8601 时间。
6. 同一 `event_id`、同一内容幂等返回；同一编号不同内容返回 409。收到不确定响应不能声称保存成功。结束、过期、退出登录后的工具请求被拒绝。
7. 成功响应表示**试聊草稿已保存**，后续由管理员确认。工具没有拨号或任意数据库访问能力。

账号就绪后先测文字和工具，再测麦克风、打断与审核。只运行页面发起的测试，不开启 `JAVA_DISPATCH_ENABLED`、`JAVA_WORKERS_ENABLED` 或真实电话驱动。

## 音频与生命周期

浏览器先通过 JWT 管理接口取得 30 秒有效的一次性连接票据，再通过同源 WebSocket 的第一条消息提交票据；票据不在 URL 中。WS 5 秒内未认证即关闭。服务端周期核对用户、租户启用状态和 token_version；退出登录会终止试聊。供应商 Key 不下发浏览器。

Vite 开发代理和仓库 Nginx 示例已补齐该精确路径的 WebSocket 升级。部署时需保持同源 HTTPS/WSS，代理长连接超时至少覆盖五分钟会话；实际反向代理发布仍须验证。Nginx 终止 TLS 后，后端必须正确识别原始 HTTPS 才能通过 WebSocket 同源校验：部署环境设置 `SERVER_FORWARD_HEADERS_STRATEGY=native`，并将 `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` 限制为实际可信代理 IP（仓库示例为回环地址），后端端口不直接对公网开放。不要通过允许任意 Origin 来绕过同源校验。

Start/Listening、SendSpeech/StopSpeech、RequestToRespond、RequestToSpeak、LocalRespondingStarted/Ended 和 HeartBeat 按套件协议映射。只有 Listening 才发送收音。音频队列、WS 发送缓存、录音总量均有上限；积压或协议异常终止会话，不静默丢帧后宣称成功。

输出持续接入现有 SentenceSafetyGate：实际 PCM → 独立 ASR 时间边界 → 累积文本审核 → 已审核片段播放。未知、超时、未覆盖音频、审核失败均终止；不提供跳过审核的开关。打断立即清本地播放器并关闭旧响应审核；旧响应不能继续放行。浏览器播放确认不代表声卡、扬声器或人耳实际收听。

输入录音和已审核输出按片段保存在 MySQL V22 新表（打断和关闭时保留已接收的输入及已审核输出），下载走管理员认证及租户边界。保存的输出不是未审核原始模型录音。单会话录音上限 12 MB。历史页只显示最近 100 条，数据不会因为列表分页自动删除；测试结束后的保留/清理须纳入运维策略，当前未新增自动删除任务。

服务重启将未结束试聊标为 `interrupted`，不自动重连、重放工具动作或重新计费。普通关闭、网络中断和失败分别保留状态。数据库结果和业务跟进记录可刷新查看。

## 测量口径

保存服务端单调时钟事件、供应商 task/round/request ID、输入结束、首包音频、审核事件及浏览器报告的播放开始/结束。`since_input_end_ms` 起点为服务端收到结束指令/文字或云端语音尾点，不是人工标注的声学结束点；不能用于宣称用户感知或电话端 P95。

费用目前为“待账单对账”，不把未知金额记为零。账号具备后需要核对交互轮次、额外 ASR 和内容审核、失败/打断的计费规则，再补齐估算及账单差额。该入口不是已经完成的多供应商成本排行榜。

## 验收清单

页面：语音方案对比入口、独立试聊菜单、加密配置、客户选择、话术引用与填写、试聊控制、结果确认、历史与录音、客户跟进、返回路径。

状态：缺配置/无权限/匿名/跨租户/重复请求/连接失败/收音/生成/审核/播放/打断/关闭/退出/过期/重启中断/工具幂等/结果冲突。

主业务回归：登录 → 客户搜索与维护 → 话术 → 外呼任务草稿 → 语音配置与平台试聊 → 通话记录（无拨号） → 线索跟进 → 退出。项目没有商城页面，门店/购物车/支付链路不适用。

验证状态、执行记录和未验证项见 `BAILIAN-TRIAL-ACCEPTANCE.md`。模拟协议通过只证明代码在所覆盖契约下的行为，不能证明百炼账号权限、真实工具透传、音质、费用或延时。

官方协议依据（核对日期 2026-10-27）：
- https://help.aliyun.com/zh/model-studio/multimodal-interaction-protocol
- https://help.aliyun.com/zh/model-studio/custom-plug-ins
- https://help.aliyun.com/zh/model-studio/product-billing
