# AI 外呼平台消息模块：开发与本地验收

日期：2026-11-06。基线：`main@76920050367c11a504d55d8a02da80d247d08b5b`。分支：`feat/messaging-http-cmpp`。

本次按“供应商暂未选定，先完成平台功能和本地验收”的范围交付。实现位于既有 Java AI 外呼系统内，独立短信营销、外呼后消息和开放 API 共用发送、授权、退订、预算、账本和回执服务。没有引入 Austin，也没有部署到生产。

## 完成的功能

- HTTP 接入规范 v1 和 SMSGate CMPP 3.0 实际协议客户端；加密通道账号、不可变连接版本、连接探测、审核映射、禁用、环境与生产准入控制。
- 普通短信、5G/RCS、阅信、厂商 IP 四种内容类型；富媒体标题、卡片、按钮和私有素材。CMPP 限普通短信，增强形态通过具有对应能力的 HTTP 通道发送。
- 网络调用前持久化发送边界；接收人/内容加密快照、签名和长短信计费、分片结果聚合、签名回执、重复/早到回执、上行回复/退订、未知结果查询补偿。不会因超时重新发消息。
- 原有名单任务和外呼规则扩展多形态，发送前重新检查授权、资源、审核、频控、预算和应用限额。
- 对外 HMAC API：单发、批次、查询、取消、事件游标、授权资源、用量账务、回复正文/互动查询、回调订阅与失败补发；应用密钥轮换、旧钥宽限及撤销。
- 销售预占、按受理/送达口径结算、部分分片结算、退款、成本事件去重、供应商账单核对和人工凭证入账。没有采购成本事件时标为待核实，未做支付网关或自动差异调账。
- 既有界面内新增通道与素材管理、富媒体编辑和预览、渠道审核、发送分片详情、应用限额与轮换、回调重试及对账。

## 验收结果

所有下表项目均通过，最终打包及 Java、Mapper XML、前端格式检查通过。环境为 Java 21、MySQL 8.4（Testcontainers）、本机 Chromium。机器可读证据及测试时间见 [acceptance.json](evidence/20261106-message-channels/acceptance.json)。

| 验收项 | 结果与覆盖 |
| --- | --- |
| 全量单元测试阶段 | 382 项通过；后续修改的消息路径按下项重新验证 |
| 消息相关单元复验 | 8 项通过：内容/Unicode边界、真实 CMPP 登录分片回执、回执数据库失败继续返回可重试服务端错误、公网回调地址约束 |
| 新增通道集成 | 11 项通过：HTTP 真实本地 socket、签名拒绝/重复冲突、早到回执、丢响应后查询且不重发、四形态内容、素材、回复退订、权限隔离、密钥轮换/日限额、成本对账、部分计费、营销与外呼共用通道、4000 字内容快照边界 |
| 原消息模块 | MessagingIT：13 项通过 |
| 原外呼/短信工作台 | WorkspaceWorkflowIT：19 项通过 |
| 营销回执桥接 | SmsReceiptIT：2 项通过 |
| 数据层与迁移 | DataLayerIT：121 项通过；DataLayerReplayIT：4 项通过；V46 首次执行及重复运行通过 |
| Chromium 页面操作 | 67 项通过，含通道配置持久化与密钥不回显、素材上传、富媒体模板编辑/批准/重载；同时覆盖原外呼、名单、短信和权限边界 |

HTTP 测试运行独立的本地供应商服务，CMPP 测试运行真实 CMPP 服务端和客户端协议栈；不是仅调用 MockMessageAdapter。测试证据说明平台实现能完成协议和业务闭环，**不代表真实运营商或手机已经验收**。

典型验收事实：

1. 通道返回 ACCEPTED 后，发送状态保持“已受理”；最终送达回执才进入 DELIVERED。重复回执不重复结算。
2. HTTP 响应丢失后，状态为 UNKNOWN、费用仍预占；查询成功恢复送达，供应商仅收到一次提交。
3. 长短信一片送达、一片失败时，整条失败，按计费口径结算可计费分片并释放其余额度；退款只退实际结算额。
4. 受理计费已经结算后，失败回执仍能更新发送状态，不因账务状态锁死业务状态。
5. 发送使用受理时的内容快照，包含签名恰好 4000 字的合法消息不会因为预览签名再次渲染而误判未知。
6. 已退订号码、停用通道、失效渠道审核、撤销应用及无授权资源不能继续发送。其他企业或应用不能读取不属于自己的消息。
7. 外部应用能查询自己的回复正文和互动来源；回调签名正确但数据库失败时返回服务端错误，不能错误地返回“JSON 无效”导致供应商停止重试。

复现检查：

```sh
cd outbound-mono
mvn test
mvn verify \
  -Dtest=MessageContentTest,MessageProviderCallbackTest,CmppMessageProtocolTest,WebhookSafetyTest \
  -Dit.test=MessageChannelsIT,MessagingIT,WorkspaceWorkflowIT,SmsReceiptIT,DataLayerIT,DataLayerReplayIT,FrontendBrowserIT
```

需要本地 Docker、Java 21、Maven、Node/pnpm 以及 Playwright 可用 Chromium；使用系统 Chromium 时设置 `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH`。CMPP 的 JVM 参数已纳入 Maven 测试及 Dockerfile.runtime。

## 选定供应商后仍需完成

- 供应商原生 HTTP 接口与本平台规范的映射，或单独原生适配器；不能任意填一个 HTTP 地址就视为接通。
- 各产品的账号权限、模板/签名/素材审核、回执字段、终端能力和真实价格口径。CMPP 2.0、SGIP、SMGP 不在本次实现内。
- 每种产品的真实手机实收、长短信、失败回执、退订、已读/点击能力核实、成本账单和生产目标吞吐/长稳验收。

开启生产必须使用独立生产账本、有效的通道审核、管理员登记的真实终端验收证据及生产开关。本次没有替代这些验收。上线、配置与回退见 [接入说明](../architecture/message-channel-bridge-v1.md)；开放接口见 [OpenAPI](../architecture/messaging-implemented.openapi.json)。
