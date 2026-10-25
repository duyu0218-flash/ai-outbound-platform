# 流式三段式与 S2S v2 实现及验收

日期：2026-10-26。对应《AI外呼系统_流式三段式与S2S架构优化技术方案_v2》。本次交付单进程实现与本地回归；真实供应商、电话和容量验收另行进行。默认 `VOICE_AI_PIPELINE=legacy`，不会因代码更新自动启用付费语音服务。

## 实现范围

| 方案要求 | 本次实现 | 限制 |
| --- | --- | --- |
| 三段式重叠 | UTF-8/SSE 增量→累计审核→完整语义句→同一 TTS task 多次 append→PCM 节奏播放；结束才 finish | 不对不稳定 partial 进行付费推测生成；只允许 final 驱动业务 |
| ASR 中间结果 | utterance/revision、稳定前缀、final 与时间字段；partial 不推进业务代际 | 各厂商时间字段以实际协议为准，待真实账号核对 |
| 话轮 | 冻结起声、短静音、数字未完句长静音、最长输入、自适应 RMS；手动提交模式保留单一提交者 | 本地能量检测不等于语义模型；腾讯静音参数最低 500ms |
| 取消与所有权 | ResponseScope 关闭 HTTP body、请求 future 与 TTS socket；SessionCoordinator 代际隔离旧 PCM；取消的 S2S 连接禁止复用 | context_truncate 能力未知，重建会话只带已确认历史 |
| 播放事实 | generated/sent/played 分开；带 playback_id、epoch、样本数的内部 playback_ack 才确认全段播放；无关联停止事件只确认排空 | 未实现逐字音文对齐，部分播放文本不推断为已听；媒体模块必须提供准确回执 |
| S2S | 原始音频直入模型；低风险 direct 模式按语音端点启动回复，旁路 ASR 保留拒呼/转人工控制；默认 reviewed | streaming 末端审核无法撤回已播音频；生产 direct 需平台验证开关，流程脚本禁止 direct |
| 接通分类 | 最终 ASR 证据一次分类 human/machine，超时 unknown 按真人；机器不启动 S2S；旁路 ASR 保留 | 当前是转写规则，不宣称有声纹/提示音分类模型；分类准确率待真实数据评测 |
| 首句告知 | 显式准备确定性 TTS，租户/音色/凭据修订隔离缓存；生产真实拨号前检查缓存 | 准备按钮可能产生供应商费用；本地测试仅模拟服务；发送与已播分别记录 |
| 资源 | AI/租户会话、ASR、TTS、LLM、S2S 并发准入，按供应商批准额度 90% 留余量；LLM/TTS 按回复释放 | 进程内额度，不能向多个媒体节点重复发放同一账号全额；不是分布式 2250 路验收 |
| 业务控制 | 复用现有租户/attempt/策略快照、命令幂等、转人工及拒呼控制；模型工具调用不受支持时拒绝 | 未授予模型直接写业务数据的能力；不把自然语言声称当作动作回执 |
| 测量及门禁 | G.711 双向 RTP PCAP 分析；有效回复需要独立标注；5 级路线门禁，证据缺失不通过 | 尚未产生真实 SIPp/运营商/终端测量结果；设计门槛不是实测成绩 |

## 页面、入口与状态清单

| 入口 | 必须验证的状态/动作 | 自动验收入口 |
| --- | --- | --- |
| 登录→后台菜单→语音方案对比 `/admin/voice-ai` | 管理员可达、有完整内容；未登录401、坐席403；错误登录、退出 | VoiceAiHttpIT / voice-ai-browser / browser-acceptance |
| 六套供应商配置 | 填写、加密保存、隐藏密钥、刷新回显、清空；跨租户隔离 | VoiceAiHttpIT / voice-ai-browser |
| 通话方案及判停配置 | 三段式/S2S切换；VAD、分类超时、告知文字保存回显；非法 direct 组合拒绝 | voice-ai-browser |
| 准备身份告知音频 | 显式点击、忙碌/错误提示；缓存租户隔离与复用 | voice-ai-browser（缺少配置异常）/ VoiceStreamingTest（本地协议成功） |
| 对比样本与结果 | 上传音频、固定话术与指纹、保存重载、缺少供应商配置状态、授权试听 | VoiceAiHttpIT / voice-ai-browser |
| 系统设置→语音方案→返回 | 正确页面及返回路径 | voice-ai-browser |
| 通话详情 | 冻结供应商/模型/话术指纹、回显 | voice-ai-browser |
| 关键业务回归 | 登录→首页→联系人搜索/保存→话术发布→任务启动→模拟呼叫→转写/录音/质检→报表→退出；坐席与主管权限 | FrontendBrowserIT、HTTP 与可靠性集成测试 |

项目没有门店、房型、购物车、下单、支付入口；用户验收规则中的这条电商链路不适用。对应的外呼链路按上表验证，不能据此宣称真实拨号通过。

## 本地验收记录

执行命令（JDK 21、Maven 3.9.11、已锁定前端依赖、Docker、Chromium）：

```sh
cd outbound-mono
mvn verify
mvn enforcer:enforce
node ../tools/test-voice-measure.mjs
```

完整测试结果以本次 `target/surefire-reports`、`target/failsafe-reports`、`target/*browser*result.json`、`target/fatjar-smoke/result.json` 为准。原始本地运行日志保存在忽略目录 `verification/voice-streaming-v2-20261026/`，不上传私有运行信息。

最终源码执行 `mvn verify` 成功：263 项单元测试 + 320 项集成测试，共 583 项；失败 0、错误 0、跳过 0。主业务浏览器检查 35 组，语音页 9 组，均通过；打包 Jar 独立启动/健康检查通过；Spotless、Mapper XML、前端格式检查及依赖 Enforcer 通过。电话测量/门禁工具的合成自测通过，已接入 GitHub CI。机器摘要见 `voice-streaming-v2-validation.json`。

验证环境为本机 JDK 21、Docker MySQL 8.4/Redis 与 Chromium，供应商均为本地模拟端点。前次验收发现的准备音频接口通用 500 已修复并重新通过页面异常验收；当前本地回归未发现已知失败。

## 电话侧测量使用

复用根目录 `docker-compose.sipp.yml` 与 `deploy/sipp/scenarios`，先在隔离实验环境打通 PBX→SIPp UAS 双向 G.711 RTP；同主机抓取完整 classic PCAP（例如 `tcpdump -s 0 -w call.pcap udp`）。SIPp 播放已授权客户样本；录音、抓包与标注保留在受控目录。以下工具分析已有抓包，不发起呼叫：

```sh
node tools/voice-phone-measure.mjs call.pcap labels.json measured.json
node tools/voice-route-gates.mjs gate-input.json gate-result.json
```

`labels.json` 示例（所有偏移相对该通话 INVITE 200 OK 抓包时间，毫秒）：

```json
{
  "call_id": "actual-sip-call-id",
  "customer_ssrc": 123,
  "assistant_ssrc": 456,
  "profile": "aliyun-cascade",
  "model": "fixed-model-version",
  "policy_version": "actual-frozen-policy",
  "turns": [{
    "id": "normal-1", "start_ms": 1000, "end_ms": 3000,
    "response_until_ms": 7000,
    "effective_segments": [{"start_ms": 4200, "end_ms": 5600}],
    "interrupt_ms": 4800, "old_response_end_ms": 5000
  }]
}
```

`effective_segments` 与旧响应结束由独立人工/标注确认；无标注只输出能量首音代理值，`effective_reply_ms=null`。解析器支持 classic PCAP、IPv4 UDP、Ethernet/Linux cooked/raw、G.711 PT0/PT8；PCAPNg、IPv6、Opus、VLAN、SSRC切换需转换或扩展后另验，不默认为成功。混合多通抓包须逐 Call-ID 分离并核实 SSRC，防止串流。

门禁输入需提供 route、model_tier、model_version、policy_version、cases；S2S 推理档需 model_reasoning_accuracy≥0.95，快速档需 low_risk_notification_only=true。每个案例含 expected/actual、expected_answer_class/actual_answer_class、disclosure_played、cancel_violations、stop_path_verified、effective_reply_ms、answer_to_open_audio_ms、evidence_level。插话案例还需 interruption_case、interrupt_stop_ms、first_interruption_word_preserved。业务与成本审批用 business_noninferiority_passed、cost_gate_passed；真实供应商/线路证明用 real_vendor_verified、operator_line_verified。成为主路线还须 business_superiority_passed 或 cost_superiority_passed。缺失证据会阻断，不用本地 mock 自动填 true。输入输出示例的自测是合成数据，不是电话验收。

## 未验证项、已知问题与正式上线条件

- 真实供应商六套 profile 的版本权限、取消/断连、计费用量、额度及合同限制未验证；capabilities 默认 unknown。
- 真实 FreeSWITCH/SIP/RTP/媒体模块准确播放及停止回执、运营商线路、真机听感、首字保留未验证。
- 关键意图98%召回/95%精确、真人误判≤0.5%、业务词95%召回、200段+50组多轮评测未验证。
- 首句p95≤500ms、插话停止p95≤300ms及两路线有效回复目标未验证；500CPS、2250AI并发、8/24小时长稳未验证。
- 部分播放缺少可信音文对齐时，不把模型全文带入已听上下文；可能需要澄清或转人工。reviewed 会增加端到端等待。
- 控制面保持现有单写者限制。分布式共享语音额度、远程执行服务、跨进程迁移/恢复仍属于后续部署改造，不能直接横向扩容。告知缓存也需向各媒体执行节点预分发或挂载同一受控目录，目前没有自动跨节点同步。
- 上线前需：批准账号额度与固定模型→准备并确认告知播报→单会话真实双路线→电话测量和准确性门禁→逐级压力和长稳→同人群业务/成本对照试点。满足后才启用新通话路由。

状态分别报告：源代码已修改；静态检查及开发环境测试见最终记录；测试环境未发布；真机未测试；生产环境未发布。GitHub 更新仅表示代码交付。
