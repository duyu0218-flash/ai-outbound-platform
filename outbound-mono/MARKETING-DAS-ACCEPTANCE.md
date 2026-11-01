# DAS 直连与跨任务查询去重

日期：2026-11-02（北京时间）。分支：`codex/das-dedup-20261102`。本次保留 `4a5844c` 的发送/归因同事务修复，在其上实现 DAS 直连与查询防重。

## 网关接入

原 `DAS_GATEWAY_URL` 指向的中转服务并不存在于仓库。本次改为按蚂蚁官方 `openapi-das:1.1.123` 与 `antchain-util:0.1.9` 的公开协议直接请求 `/gateway.do`，不再依赖该中转服务。官方 SDK 的传递依赖包含项目禁止的 fastjson，因此没有把 SDK 加入应用依赖，也没有放宽 Maven Enforcer。

- 请求：`antchain.das.application.unifiedentrance.query`、版本 `1.0`、UTF-8 表单、HmacSHA1、UTC 时间和请求流水；产品、数据集、授权凭证、`phone` 与 `topN` 按官方请求字段发送。
- 请求流水由本地持久化 request ID 派生，便于核对。这里不假设供应商按该流水提供计费幂等。
- 成功响应必须通过原始 `response` JSON 字节对应文本的签名校验；重复 JSON 字段、篡改或缺签名成功回复拒绝使用。已知非成功结果码按官方 SDK 的错误响应约定处理，不编造分数。
- 不跟随重定向，没有应用层自动重试；POST 正文只允许订阅一次，防止传输层重放。超时、断连、HTTP 非 200、无效响应或签名不能确认时保留待核对状态。
- 默认 `DAS_ENABLED=false`。本地测试只用 127.0.0.1 的 HTTP 模拟服务和合成密钥；HTTP 仅在 `ENV=test` 且 `DAS_ALLOW_LOCAL_HTTP=true` 时允许，生产配置拒绝明文 HTTP。

未来真实联调需要显式配置以下变量；本次不读取或启用真实 DAS 凭证：

```text
DAS_ENABLED=false
DAS_ENDPOINT=https://openapi.antchain.antgroup.com
DAS_ACCESS_KEY_ID=<由管理员配置>
DAS_ACCESS_KEY_SECRET=<由管理员配置>
DAS_DATASET_ID=<已开通数据集>
DAS_PRODUCT_INSTANCE_ID=<已开通产品实例>
```

协议核对来源：[官方 DAS SDK 源码包](https://repo.maven.apache.org/maven2/com/antgroup/antchain/openapi/openapi-das/1.1.123/openapi-das-1.1.123-sources.jar)、[官方签名工具源码包](https://repo.maven.apache.org/maven2/com/antgroup/antchain/openapi/antchain-util/0.1.9/antchain-util-0.1.9-sources.jar)。只下载公开源码核对，未调用业务接口。

## 并发去重与恢复

V35 新增 `mk_match_flight`，唯一键为 **tenant_id + partner_id + product + phone_hash**；`mk_match_request.shared_request_id` 记录共享查询来源。发送许可和 request 的 pending 状态在同一数据库事务提交，提交后才允许网络调用。凭证在 MySQL 中持久化，跨 worker、进程及应用重启生效。

- 首个请求取得发送许可；其他任务记为 `waiting`，不增加 `charged`，页面显示“共享等待”。
- 成功或无数据结果原子写入 request、缓存和凭证；等待任务复用同一次查询结果。缓存更新后，旧等待任务仍读取原查询的结果，不被新一轮分数改写。
- pending 超过 5 分钟只变为待核对，不授予另一任务发送许可。failed 也不能被新任务绕过，因为错误响应是否计费尚不能确认。
- 外部返回后数据库保存失败，事务回滚会保留已经提交的 pending 凭证；任务暂停，后续任务继续等待或待核对，不重发。
- 确认过的 ant-platform 历史结果可以导入并协调已阻断凭证，无需重新查询。原始任务记录保留审计历史。
- 只有已确认 success/no_data 且 30 天复用窗口已过，新的任务才能取得新一轮许可。缓存 topN 较小时不为补齐时段自动发起额外调用。
- 升级时把旧的已发送 pending/uncertain/failed 请求纳入防重表，避免上线后被新任务绕过。

`charged` 在这里表示已取得发送许可、可能计费的调用次数，不等同于供应商实际结算金额。测试中的计数均为本地模拟查询，不是实际扣费凭证。

## 页面、入口与状态

| 入口 | 验证范围 |
| --- | --- |
| `/admin/marketing/datasets` 数据包/合作方/撞库任务 | 创建并导入本地号码、创建模拟合作方任务、点击立即执行、完成状态与共享等待计数展示；刷新、明细、表单和掩码 |
| `/api/v1/marketing/match-jobs` 及 run/resume | 未启用拒绝、并发提交、共享等待、成功/无数据复用、错误/待核对防重、过期后新一轮 |
| `/api/v1/marketing/match-imports/ant-platform` | 导入已确认旧结果，恢复结果复用，不发起新查询 |
| 既有人群、短链、判定、活动、档案与看板 | 随完整营销浏览器与后端回归验证 |
| 登录/退出、坐席权限、原外呼/消息链路 | 随完整回归验证；仅模拟通道 |

本项目无门店、房型、购物车或支付页面。对应关键业务链路为“数据包 → 人群快照 → 短信/外呼任务 → 触达结果 → 档案 → 营销看板”。

## 验证结果

最终 `mvn -B verify`：**BUILD SUCCESS**（2026-11-02T07:38:56+08:00）。

| 检查 | 结果 |
| --- | --- |
| 单元测试 | 344 项，0 失败、0 错误、0 跳过 |
| 集成测试 | 420 项，0 失败、0 错误、0 跳过 |
| 原有浏览器检查 | 43 组通过 |
| 营销浏览器检查 | 7 组通过，含本次模拟任务执行和进度展示 |
| 构建与静态 | 前端构建、打包启动、Spotless、Mapper XML、Prettier、Maven Enforcer 均通过 |

源码已修改，静态检查和本地开发环境功能测试通过。测试环境发布按后续部署报告记录；真机未验证；生产未发布。本轮未调用真实付费接口。

专项包含：官方格式的请求签名与响应验签、拒绝重定向/断连/超时重放、真实 HTTP 传输接入本地模拟网关、8 个任务跨独立连接的重复号码并发、部分重叠号码、3 个独立 JVM 竞争发送许可、保存失败、待核对结果导入、租户/产品隔离、V34→V35 历史请求保护，以及浏览器模拟任务操作。

## 已知边界、未验证项与上线条件

- 去重范围为同租户、同合作方、同产品、同号码的在线撞库。不同租户、合作方或产品不会共享私有结果；手工哈希文件交付给合作方的外部计费不由该在线发送许可控制。
- 真实鉴权、供应商服务权限、真实分值量纲、实际扣费/对账、真实超时语义未验证。真实短信/外呼、真机、长稳、规模性能和供应商 SLA 未验证。
- 已发生的历史重复计费不会因代码升级自动退款。待核对请求必须通过已有流水核对，不允许把超时当成可安全重试。
- 生产上线前需要备份、V35 升级演练、真实接口小样本与对账验收；每次真实付费验证必须先单独说明用途、预计费用并取得授权。
- GitHub 合并和 111 发布按后续部署报告独立记录。111 保持隔离、workers 关闭和 DAS_ENABLED=false；生产环境不在本次发布范围内。
