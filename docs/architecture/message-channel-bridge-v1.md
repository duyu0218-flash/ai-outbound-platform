# HTTP / CMPP 消息通道接入与验收

本模块位于现有 Java AI 外呼平台 `outbound-mono` 内，复用企业、联系人授权、短信营销任务、外呼联动、消息账本和事件回调。无需 Austin，也没有新增独立消息编排服务。SMSGate 的 `sms-core` 仅作为进程内 CMPP 协议库使用。

当前供应商尚未选定。本次验收范围是平台功能和本地协议联调。HTTP 按下述平台接入规范实现，**不能把任意供应商 HTTP 地址填进来就认为已经兼容**。选定供应商后须在供应商侧实现本规范，或为其原生接口增加适配器。5G 消息、阅信、厂商 IP 消息的商业权限、原生模板、终端支持与显示效果须分别联调验收。标准 CMPP 3.0 只承载普通短信；CMPP 2.0、SGIP、SMGP 未实现。

## 已有模块上的操作路径

1. 系统 → 消息通道与素材：新增 HTTP / CMPP 通道版本。配置不可原地修改；账号、地址变更需新版本。密钥加密保存，查询不回显。
2. 连接测试：HTTP 检查规范版本和产品能力；CMPP 实际登录。成功只记为连接已验证，不发送营销内容。
3. 短信营销 → 签名与模板：创建签名、普通文本或富媒体模板，内部批准；模板内容不原地修改。富媒体支持标题、正文、主素材、最多 10 张轮播卡片及每卡 5 个链接/电话/回复按钮。变量支持正文、标题和卡片；按钮目标固定审核。
4. 通道详情 → 渠道审核：登记签名、模板、素材的供应商编号、审核状态、凭证及可选到期时间。内部批准和渠道审核分别检查。
5. 短信概览 → 签名与模板页签：创建产品，选择消息形态、通道、单价和单人每日触达上限。一个产品绑定一个通道版本；不做隐式自动降级。
6. 媒体素材：上传 PNG/JPEG/MP4，单个最多 5 MiB，企业总额 200 MiB；图片尺寸最多 8192×8192；文件私有存储，批准的供应商素材编号才进入发送请求。平台不替供应商自动上传或转码。
7. 独立发送、名单任务、外呼挂机/通知、营销旅程和 `/openapi/v1/messages` 使用同一发送服务。产品与模板形态必须一致；原有联系人授权、退订、预算和幂等规则继续生效。存量旧 HTTP 外呼路径的迁移仍由既有开关控制，不能两条路径重复发送。
8. 发送记录：区分模拟/通道，查询提交、供应商受理、分片、最终回执、计费及查询次数。未知结果只查询，不重新发消息。

素材有 MIME 头与图片尺寸校验，不含视频转码或供应商内容审核。素材根目录和数据库须一起备份；本版保持现有单实例部署，不宣称分布式对象存储或高吞吐集群能力。

## 启用配置

默认开关关闭。先配置平台主密钥（32 字节随机值 Base64），不要使用测试中的固定密钥。

```dotenv
MESSAGING_ENABLED=true
MESSAGING_WORKERS_ENABLED=true
MESSAGING_MASTER_KEY=<通过密钥配置注入>
MESSAGING_TRANSPORT_ENABLED=true
MESSAGING_WEBHOOKS_ENABLED=true
MESSAGING_MIGRATE_CALL_SMS=false
MESSAGING_RETENTION_ENABLED=true
MESSAGING_ASSET_ROOT=/app/data/message-assets
MESSAGING_CMPP_ALLOWED_HOSTS=operator-approved-host.example
MESSAGING_PRODUCTION_ENABLED=false
```

- 生产使用 `ENV=production`，只接受 production 通道；测试环境只接受 test 通道。生产禁止 mock 和模拟充值。不得将存在 SANDBOX_CREDIT 的数据库改成生产账本，应建立独立生产数据库。
- 生产通道还需管理员登记真实终端及回执验收凭证，达到 `PROVIDER_VERIFIED`，然后启用通道及 `MESSAGING_PRODUCTION_ENABLED`。登记是管理员提供的证据，不是平台自行检测到手机实收。
- 仅 `ENV=internal-test` 的 test 通道允许 `http://127.0.0.1:port` 和 CMPP 回环服务器。正常 HTTP 出网仅公网 HTTPS 443，禁止跳转；解析地址检查、TLS 主机验证和实际连接固定复用平台安全传输。
- CMPP 主机必须在全局运营商地址白名单中，不能由租户任意探测网络。最多 64 个 CMPP 账号连接，每账号一个会话；连接、响应、回执队列有界。账号连接断开后库可重连，但禁止对未知提交重发。
- 通道每秒上限按逻辑消息计，日额度按计费单位计；CMPP 线速另外受库的分片 TPS 控制。UTC 日界限。发送 worker 单线程，与语音线程分开；配置 TPS 是上限，不是已验证吞吐承诺。
- 手动入账 `POST /api/v1/admin/messaging/funding` 需管理员、稳定幂等键、正整数微元金额和已核实支付凭证；不是支付网关、发票或代理商分账系统。

`Dockerfile.runtime`、Surefire 与 Failsafe 已配置 SMSGate 在 Java 21 上需要的模块开放参数。直接运行 jar 也需传入：

```sh
java --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.math=ALL-UNNAMED \
  --add-opens=java.base/java.util=ALL-UNNAMED \
  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
  --add-opens=java.base/java.net=ALL-UNNAMED \
  --add-opens=java.base/java.text=ALL-UNNAMED \
  --add-exports=java.base/sun.security.x509=ALL-UNNAMED \
  -jar outbound-mono.jar
```

## HTTP 接入规范 v1

全部操作 POST 到通道 `config.endpoint`，`Content-Type: application/json`，`Authorization: Bearer <api_key>`。响应只能 200 或 202；非成功、超时、格式错误均不能推定短信未发送。响应体最多 64 KiB。平台不记录密钥或完整消息日志。

探测请求：`{"version":"1","operation":"probe"}`。响应：`{"version":"1","status":"OK","forms":["sms","rcs","yuexin","oem_ip"]}`。forms 必须覆盖配置的形态。

发送请求：

```json
{
  "version": "1",
  "operation": "submit",
  "message_id": "msg_0123456789abcdef0123456789abcdef",
  "form": "rcs",
  "to": "+8613800000000",
  "sender_ref": "approved-sender-id",
  "template_ref": "approved-template-id",
  "content": {
    "form": "rcs",
    "text": "【企业签名】您好，客户",
    "rich": {
      "title": "预约提醒",
      "asset_id": "asset_0123456789abcdef0123456789abcdef",
      "actions": [{"code":"book","label":"查看预约","type":"url","value":"https://example.com/booking"}]
    }
  },
  "asset_refs": {"asset_0123456789abcdef0123456789abcdef":"approved-provider-media-id"}
}
```

头 `Idempotency-Key: submit:<message_id>`。供应商/桥接实现必须持久化该业务编号的幂等关系，包括在超时后按编号查询原提交；不允许在重复请求时再次投递。平台本身也在网络调用前提交 `msg_delivery` 边界，不会因重启或 HTTP 超时重发。

响应示例：`{"version":"1","message_id":"msg_...","provider_id":"opaque-provider-id","status":"ACCEPTED"}`。状态允许 ACCEPTED / DELIVERED / REJECTED / FAILED / UNKNOWN；ACCEPTED、DELIVERED、FAILED 必须包含 provider_id。ACCEPTED 只是通道受理，不能标成最终送达。REJECTED 表示明确未受理；FAILED 表示已受理后的终态失败。

查询请求：`{"version":"1","operation":"query","message_id":"msg_...","provider_id":"可能为空"}`，幂等头 `query:<message_id>`，响应同发送。配置 `query_supported=true` 才会查询。默认首次一分钟后查询，退避至每小时，最多 24 次；耗尽或不支持则标记人工核查，可手动安排重新查询。CMPP 没有通用按平台 ID 查询能力，只靠真实分片回执与运营商核查。

普通短信使用签名后的 UCS-2 文本计单位：70 字以内一段，超过按 67 字一段；当前不接受非 BMP 字符（例如 emoji）。HTTP 供应商须按此产品合同对齐计费，若口径不同须增加适配及资费策略。富媒体当前按一条逻辑消息一个单位，不代表所有供应商资费均相同。

## 上游回执与事件

POST `/api/v1/webhooks/messaging/{channel_id}`，原始 UTF-8 JSON 最多 16 KiB。头 `X-Message-Timestamp` 为 Unix 秒，允许时差 300 秒；`X-Message-Signature` 为以通道 callback_secret 计算的 HMAC-SHA256 十六进制值，原文为：

```text
channel_id + LF + timestamp + LF + sha256(raw_body) + LF
```

先验证签名再解析，拒绝 JSON 重复键。event_id 在同一通道永久幂等：重复同内容成功，重复异内容 409。回执数据库提交成功才确认；CMPP DELIVER_RESP 也在提交后返回。早到的送达报告按供应商分片编号暂存，在提交响应到达后关联。

```json
{"event_id":"receipt-0001","kind":"delivery","provider_id":"opaque-provider-id","state":"DELIVERED"}
```

- kind：delivery / display / read / click / reply / unsubscribe / cost。通道配置须声明相应事件能力（cost 独立接受）。未声明能力返回 422；不把供应商没有的“已读/点击”伪造成平台功能。
- delivery：可用 message_id 或 provider_id 关联；状态同上。CMPP 只在所有分片送达后整条 DELIVERED；部分已送达而另一片失败则 FAILED。明确终态不被迟到的非终态覆盖。
- reply：携带 text，最多 2000 字；unsubscribe：按 message_id 或 E.164 recipient 关联并加入企业禁发列表；CMPP 上行 TD / T / 退订自动转换。回复落入既有线索历史，保留原负责人。未能关联的上行保留在加密回执表，不能臆测归属应用。
- display / read / click：携带 message_id / provider_id，可附业务数据；与发送状态独立保存为 PROVIDER 来源的互动；点击可用 action_code 对应模板按钮。
- cost：携带 amount（整数微元）和 units，**每个唯一 event_id 是新增成本分录**，不是覆盖前次累计值；重复事件不重复入账。没有成本事件时采购成本尚未核实，不得把数据库默认零当作真实零成本。

销售报价在受理时冻结。delivered 口径只对明确送达的分片结算；accepted 口径在全部受理后结算，后续送达失败不自动退掉已经发生的受理费。部分明确失败的最终结果按可计费分片结算并释放其余预占。UNKNOWN 保留冻结，不按时间释放；人工退款只能对已结算金额且幂等。供应商账单核对只生成 MATCHED / DIFFERENCE / UNMATCHED / SEGMENT_REVIEW，分片采购成本需人工核查，不自动改客户余额。

## 对外 API

以 [当前 OpenAPI 契约](messaging-implemented.openapi.json) 和 [签名规则](messaging-implementation.md#下游接入) 为准。企业管理员批准资源后创建应用；一个应用只能发送获授权的产品、签名和模板，查询自己的消息、批次和事件。同企业应用共享钱包；不同企业的数据、主密钥上下文与手机哈希隔离。联系人授权通过现有平台名单管理完成，开放 API 不提供匿名或自助创建授权证据。

| 路径（前缀 /openapi/v1） | 能力 |
| --- | --- |
| POST /messages；GET /messages/{id}；POST /messages/{id}/cancel | 幂等受理、状态查询、取消未提交消息 |
| POST /message-batches；GET /message-batches/{id} | 最多 100 项，逐项结果；取消已提交消息不等于供应商撤回 |
| GET /events?after=sequence；GET /messages/{id}/events | 顺序游标补偿，最多 200 条 |
| GET /resources/{product\|sender\|template} | 应用获授权资源，不返回上游密钥 |
| GET /usage；GET /ledger；GET /balance | 近 30 天应用用量、最近 200 笔应用关联分录、企业共享余额 |
| POST /messages/{id}/interactions；GET /messages/{id}/interactions | 下游上报；读取本应用消息的回复正文与互动。区分 PROVIDER / CONSOLE / REPORTED 来源 |
| POST /webhook-subscriptions；GET /webhook-subscriptions/{id} | HTTPS 挑战验证和签名事件回调 |
| GET /webhook-deliveries；POST /webhook-deliveries/{id}/retry | 本应用投递查询及 DEAD/PENDING 手动补发；不重新发手机消息 |

默认每应用每分钟 120 请求、每天 10000 条、日预算 1000000000 微元，可在应用接入页调整。密钥轮换最多保留旧钥 24 小时，页面默认 5 分钟；撤销应用立即拒绝新请求，并阻止尚未提交的消息。金额预算、频控、应用限额在租户数据库锁内校验。

## 待供应商选定后的验收

每种产品分别完成：原生接口/模板/素材审核与映射、CMPP 参数及运营商接入码、终端覆盖、真实发送与长短信、真实送达/失败/退订、已读点击能力核实、超时查询及账单核对。以实际 Android/iOS/运营商终端证据记录能力；HTTP 接口返回成功不等同于手机实收。吞吐及长稳另按目标并发做容量验收。

停用通道可阻止新发送；回退时停止 worker/新准入，保留不可变账本、未知结果、回执和密钥。V46 为增量迁移，不通过删表或回退 UNKNOWN 状态来恢复。
