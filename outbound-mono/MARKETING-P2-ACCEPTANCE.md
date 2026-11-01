# 营销中台 P2：短链与短信回执 验收记录

分支 `feat/marketing-p2-links`，包 `com.duozhi.outbound.marketing.link`，迁移 `V32__marketing_links.sql`，页面 `frontend/src/pages/MarketingLinks.vue`（路由 `/admin/marketing/links`，已在底座中注册）。

## 范围

| 能力 | 实现 |
| --- | --- |
| 域名池 | `mk_link_domain`：域名、ICP 备案号、证书到期、状态（active / standby / blocked / disabled）、权重、域名池、探测结果与拦截原因 |
| 落地页白名单 | `mk_landing_domain`：精确域名或 `*.example.com`；生成短链时只接受 http/https、不带 userinfo、主机在白名单内的地址，杜绝任意跳转 |
| 生成短链 | `Links implements LinkMinter`：7 位 Base62，`SecureRandom` 生成（62^7 ≈ 3.5 万亿），主键冲突自动重抽；只在池内 active 且权重 > 0 的域名中按权重抽取；`bindMessage` 在消息创建后写入 `message_id`；活动公共链接 `mintPublic` 只统计 PV/UV |
| 跳转 | `GET /s/{code}`，无需登录。先查进程内缓存（默认 60 秒、10 万条），再查 MySQL；正常 302；UA 含 `MicroMessenger` 返回“请在浏览器中打开”引导页；过期返回 410“活动已结束”；所在域名已拦截的旧链接照常跳转 |
| 点击采集 | `Clicks`：有界队列（默认 20000），满则丢弃并计数；单写线程每 200ms 批量写 `mk_link_click`；`flush()` 供测试同步调用。IP 仅保留 IPv4 /24（IPv6 保留 /48） |
| 点击有效性 | 无 UA、已知扫描器 UA、距 `msg_message.submitted_at` 不足 10 秒 → 机器点击；同一短链 30 分钟内重复点击只计一次（公共链接按访客计）；首次有效点击经 `TouchEvents` 写 `LINK_CLICKED`，归因取 `Attribution.find(MESSAGE, messageId)`，未关联消息时用短链自身记录的快照 / 活动 / 成员 |
| 转化回传 | `POST /api/v1/marketing/links/conversions`，`MarketingAccess.operator`（管理员或带 `admin:marketing` 的 API Key），写 `LINK_CONVERTED`，同一短链只记一次 |
| 域名探测 | `DomainProbe`：`MARKETING_WORKERS_ENABLED=true` 时每 `MARKETING_LINK_PROBE_MS`（默认 10 分钟）运行；单步方法 `probeDue(limit)`、`probe(tenant,id)`。检查 DNS、证书到期（已过期即拦截，15 天内预警）、拦截检测（`InterceptionDetector` 可插拔）。失败即置 blocked、记录原因、给租户管理员发站内通知（`ops_notice`，路径 `/marketing/links`）并写 WARN 日志；池内无 active 域名时自动启用权重最高的健康备用域名 |
| 短信回执 | `SmsReceipts`：对 `msg_event` 做水位扫描（`mk_link_cursor`），只处理有 `mk_dispatch` 的消息。`message.submitting` → `SMS_SUBMITTED`；`delivered/rejected/blocked/expired` 先按 payload 中的 `provider_code`、再按 `platform:<状态>` 查 `mk_sms_status_map`（租户+通道 > 租户 > 平台+通道 > 平台），得到 `SMS_DELIVERED / SMS_FAIL_INVALID / SMS_FAIL_BLOCKED / SMS_FAIL_TEMP / SMS_SUBMIT_FAIL`；`message.interaction` 中 `kind=unsubscribe` → `SMS_REPLY_TD`；提交 72 小时（`MARKETING_SMS_UNKNOWN_HOURS`）后仍无最终结果 → `SMS_UNKNOWN`。未修改 `Messaging.java` |
| 页面 | 域名池（含探测结果、立即探测）、落地页白名单、效果统计（按活动 / 快照 / 模板：发送、送达、有效点击、机器点击、点击人数、点击率）、点击明细（号码脱敏为 `138****8000`）、活动公共链接、回执映射 |

### 新增表

`mk_link_domain`、`mk_landing_domain`、`mk_short_link`、`mk_link_click`、`mk_sms_status_map`（含平台默认映射种子）、`mk_link_cursor`（初始水位为安装时 `msg_event` 的最大序号）。

### 新增接口（均在 `/api/v1/marketing/links` 下，`MarketingAccess.operator`）

`GET /overview`；`GET|POST /domains`、`PUT /domains/{id}`、`POST /domains/{id}/probe`；`GET|POST|DELETE /landing-domains`；`GET|PUT|DELETE /status-map`；`GET /performance?dimension=campaign|snapshot|template&days=`；`GET /clicks?code=&verdict=`；`GET|POST /public`；`POST /conversions`。公开接口：`GET /s/{code}`。

### 配置项

`MARKETING_LINK_SCHEME`（https）、`MARKETING_LINK_TTL_DAYS`（30）、`MARKETING_LINK_CACHE_SECONDS`（60）、`MARKETING_LINK_CACHE_SIZE`（100000）、`MARKETING_LINK_CLICK_QUEUE`（20000）、`MARKETING_LINK_CLICK_FLUSH_MS`（200）、`MARKETING_LINK_PROBE`（mock / network）、`MARKETING_LINK_INTERCEPTION`（mock）、`MARKETING_LINK_PROBE_MS`（600000）、`MARKETING_SMS_RECEIPT_MS`（5000）、`MARKETING_SMS_RECEIPT_SETTLE_SECONDS`（5）、`MARKETING_SMS_UNKNOWN_HOURS`（72）。外部适配器默认全部为 mock，只有显式配置才会访问网络。

## 对公共 / 既有文件的修改

- `auth/config/JwtSecurityConfiguration.java`：在 Shiro 过滤链中加入 `chain.put("/s/*", "noSessionCreation,anon")` 一行（加注释）。原因：`/**` 默认走 `authcBasic`，短信收件人无法登录；该路由不读取身份，只做 302。未改动其他路径。
- `frontend/src/pages/MarketingLinks.vue`：替换底座占位页（本模块所有）。
- 既有测试的期望值（基线在底座提交后已经失败，`mvn -B verify` 需要它们通过）：
  - `frontend/tests/client-browser.mjs`：侧边栏分组期望加入底座新增的“营销中台”。
  - `integration/test/DataLayerIT.java`：表清单加入 V30 的 `mk_dispatch`、`mk_touch_event` 与 V32 的 6 张表，总数 105 → 113。
  - `integration/test/DataLayerReplayIT.java`：迁移执行数 29 → 31、11 → 13、7 → 9，当前版本 "29" → "32"，表总数 105 → 113 并断言营销表存在。
  - 这几处数字与 P1/P3/P4 的迁移叠加相关，合并时按最终迁移数调整即可（每个模块各加自己的表一行）。
- 未修改 `marketing.common`、`router.js`、`i18n.js`、`Messaging.java`。

## 测试

测试目录 `src/test/java/com/duozhi/outbound/marketing/link/`（继承 `MarketingTestBase`）：

- `ShortLinkIT`：400 次生成无重复且均为 7 位 Base62、按权重分布、不使用 standby / 权重 0 域名；白名单拒绝非白名单域名、伪造后缀、`javascript:`、userinfo、非 http(s)、无协议地址，跨租户白名单不生效；跳转 302、微信引导页、域名拦截后仍跳转、过期 410、不存在 404，IP 只存 /24；无 UA / curl / Java 客户端 / 10 秒内点击判为机器点击，30 分钟内重复只计一次、31 分钟后重新计数；首次有效点击只产生一条 `LINK_CLICKED`，带 dispatch、快照、活动、成员、联系人；转化回传幂等、坐席 403、未登录 401；公共链接 PV/UV 且不产生个人触达事件；队列满时丢弃并计数；效果统计与脱敏点击明细。
- `DomainProbeIT`：探测正常、拦截检测失败 → blocked + 原因 + 管理员通知 + 备用域名自动启用、新链接只用备用域名、旧链接仍跳转；DNS 失败、证书过期 → blocked；证书 15 天内到期 → 预警不拦截；池内无可用域名时告警且生成短链报错；人工恢复。
- `SmsReceiptIT`：提交、送达、空号、拦截、临时失败、无运营商码的被拒、租户自定义映射、退订回复各自映射正确；非营销消息不处理；重置水位重扫不重复入库；未过稳定窗口的事件留待下次；72 小时无回执记 `SMS_UNKNOWN`，10 小时内不记，非营销消息不记，重复执行不重复。

命令与结果：

```
mvn -B -Dtest=NoSuchTest -Dit.test='ShortLinkIT,SmsReceiptIT,DomainProbeIT' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false verify
  → Tests run: 9, Failures: 0, Errors: 0
mvn -B verify
  → BUILD SUCCESS：单元测试 338 个、集成测试 364 个，失败 0、错误 0、跳过 0（含 Spotless、Prettier、前端构建与浏览器验收）
```

同期另一次全量运行中 `VoiceAiHttpIT`（语音 AI 页面浏览器用例，等待下拉框文字超时 5 秒）失败一次，单独重跑通过，随后全量重跑全部通过；判断为多个代理并行测试时的负载抖动，与本模块无关。

## 未验证 / 后续

- 真实域名 ICP 备案、短信签名与运营商报备未验证；页面只记录备案号，不校验真伪。
- 真实拦截检测 API（微信、运营商、手机厂商）未接入，只有 `MockInterceptionDetector`；`NetworkDomainNetwork`（真实 DNS + 443 证书握手）已实现但未在测试中访问外网。
- 真实短信通道的状态码未联调：现有消息模块只产生平台状态（mock 通道），`provider_code` 映射已按 payload 字段预留，默认映射中的运营商码为示例，需按通道文档维护。
- 性能目标“跳转 P99 < 50ms”未压测。跳转路径只有一次缓存读取（未命中时一次主键查询）和一次非阻塞入队。
- 本地缓存按实例存在：多实例部署时 `bindMessage` 只清除本实例缓存，其他实例最长 60 秒后才看到 `message_id`（影响该时段内点击的“10 秒内”判定与归因回退），可调小 `MARKETING_LINK_CACHE_SECONDS`。
- 生产环境 `TRUSTED_HOSTS` 需要加入短链域名，否则 `HttpBoundary` 会以 400 拒绝；如经反向代理，需要配置真实客户端 IP 透传（当前取 `getRemoteAddr()`）。
- 水位扫描假设 `msg_event` 事务在稳定窗口（默认 5 秒）内提交；P1 需在消息创建后立即调用 `Attribution.register`（`message.submitting` 由消息 worker 稍后产生，一般晚于登记）。
- 页面未在浏览器中逐项点验，只通过前端构建与接口测试。
- 时间约定：本模块写入的 DATETIME 均为 UTC（与消息模块一致）。底座 `TouchEvents.record` 在 `occurredAt` 为空时使用 `LocalDateTime.now()`（JVM 本地时区），本模块总是显式传入 UTC 时间；建议底座统一。
- 跳转服务（`Redirect` + `Links.resolve` + `Clicks`）只依赖 `mk_short_link`、`mk_link_click`、`msg_message.submitted_at` 与 `TouchEvents`，后续可拆为独立部署（只读副本 + 点击写入队列），管理接口留在主应用。
