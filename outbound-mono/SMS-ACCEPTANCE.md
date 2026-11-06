# Austin＋SMSGate 短信模块功能验收

2026-11-06；集成基线为 GitHub main `76920050367c11a504d55d8a02da80d247d08b5b`。实现、配置和回退步骤见 [集成说明](../docs/architecture/austin-smsgate-sms.md)。

## 交付范围

- 现有 Java AI 外呼平台的短信工作台增加 Austin＋SMSGate 首选产品、Austin 模板关联、发送模式和分片回执查询，保留既有签名模板、应用授权、单发、定时、人群批次和外呼结束联动。
- Austin 原生 `/send` 协议适配器及可重复安装的 `SmsHandler` 桥接；SMSGate 使用 `sms-core:2.1.13.6` 的真实 CMPP 3.0 客户端，支持登录、长短信拆分、提交响应及送达报告。
- Flyway V46 增加发送去重、分片和提前回执表，消息及批次持久记录模拟标记。新状态接入现有营销回执归因。
- 专用桥接鉴权；发送前复核租户、资源授权、联系人同意与有效期。重复任务不重发，超时或未知结果不自动切换供应商，全部分片确认后才标记送达。

## 功能验证方式

使用隔离 MySQL 8.4 Testcontainers、Spring Boot 真实 HTTP 服务和系统 Chromium。联系人、令牌与运营商消息编号均为合成测试数据，没有使用真实客户或发送真实短信。

| 验证层 | 覆盖内容 |
| --- | --- |
| Austin HTTP 合约 | 原生请求和 64 位模板编号、业务编号关联、Bearer 鉴权、仅受理、异常响应、重定向不跟随、无隐式重试 |
| SMSGate 协议 | 本地 CMPP 服务端与真实客户端登录、中文长短信拆分、SUBMIT_RESP 和 DELIVER 回执关联 |
| 消息与账务 | 重复提交、跨租户隔离、撤销授权、退订、重复/提前/迟到回执、未知结果不重发、分片归并、停用租户后仍接收已发消息回执 |
| 外呼联动 | 通话结束 → Austin HTTP 桩 → 平台桥接 HTTP → 测试 transport；迟到的 Austin 成功响应不能覆盖最终回执 |
| 现有功能 | 幂等键、HMAC 防重放、批次、定时取消、WebHook、隐私保留、营销状态归因、数据库结构及工作台浏览器交互 |

Austin 上游 `8379c029e0ba3c541f1e34cfc9c8401a4355b2f6` 已安装本仓库桥接并通过 `austin-handler -am package` 编译。本环境为 Java 21，编译该 Java 8 上游时临时使用 `-Dlombok.version=1.18.40`。这项验证不代表已启动或联调其完整 MQ/Redis 服务。

## 运行记录

- 全量 382 项单元测试通过（零失败、零跳过）。
- 短信消息 19 项、营销回执 3 项、数据库结构 121 项、整站浏览器测试 1 项，共 144 项重点集成测试通过。浏览器内部 65 项检查通过，未捕获错误为零。
- V46 增量升级及重放的 4 项检查通过。
- 消息 SDK 签名、规范化查询和写入保护脚本通过。Austin 上游桥接编译及安装器重复执行验证通过。
- 最终复验：4 项通道单元测试、4 项增量迁移测试、19 项外呼/短信工作流测试、1 项整站浏览器测试全部通过；浏览器内部仍为 65 项检查、零未捕获错误。两轮去重后共覆盖 167 项重点集成测试。
- Vue 构建、Java Spotless、前端 Prettier、Mapper XML 和 Maven 依赖策略检查全部通过。本地未运行全部容量/故障注入验收，完整功能回归仍由 GitHub 现有 CI 执行。

复验（Java 21、Maven 3.9、Node/pnpm、Docker 和 Chromium）：

```sh
cd outbound-mono
pnpm --dir frontend install --frozen-lockfile
PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium mvn -B \
  -Dit.test=MessagingIT,SmsReceiptIT,DataLayerIT,DataLayerReplayIT,WorkspaceWorkflowIT,FrontendBrowserIT verify
```

## 尚未验收的外部条件

没有现成 Austin/SMSGate 运营环境与运营商凭据，因此未进行真实手机送达、上行 MO/短信回复、供应商账单或生产部署验收。本次接入为单账户 CMPP 3.0；SMPP、SGIP、SMGP 尚未接入平台配置。

平台继续使用模拟账本，真实通道产品的平台模拟单价为零，不能用于结算运营商费用；原有生产模式发送限制保留。通道开关默认关闭。启用隔离测试需管理员提供 Austin 地址、专用桥接凭据和 CMPP 测试账号/白名单/网络通路；真实测试发送可能产生运营商费用。
