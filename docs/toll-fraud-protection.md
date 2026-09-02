# 防盗打：代码控制、部署条件与验收

## 本轮范围

修复拨号网关绕过业务限制、重复 originate、超时误释放容量、回调签名断链、回调地址泄密，以及转接目标与安全配置权限问题。
没有连接运营商、拨打真实号码、采购服务或发布生产。现有业务实例仍不等同于已加载本轮代码；不能因为 Git 提交或单元测试通过就宣布生产防盗打验收完成。

## 已实现的控制

| 入口 / 状态 | 处理方式 |
| --- | --- |
| 真实网关启动 | 所有 ENV 强制服务、命令签名、安全管理、回调 Token、回调签名、ESL 六份不同的强凭据；缺失时启动失败 |
| 拨号与通话控制 API | 服务 Token + 60 秒有效命令签名；签名绑定 URL 路径和完整原始 JSON，nonce 持久化且只用一次 |
| 租户 / 线路 / 号码 | 网关独立按 `tenant_id:line_id` 配置授权；默认空表拒绝所有拨号；不接受未授权线路或主叫覆盖 |
| 号码格式 | 含国家码的 6–15 位 ASCII 数字，可带开头 `+`；不自动猜国家码、不接受 `00` 国际前缀或任意分隔符 |
| 重试 | 持久化 `(call_id, attempt)`，并校验租户和完整请求摘要；相同请求返回原 UUID，不再次 originate；变更内容返回 409 |
| 并发、CPS、日次数 | SQLite 原子事务同时检查网关总量、租户总量、单线路；计费意图先持久化再写 ESL |
| 费用 | 小时/日预算、拨前保守预占，PBX 结束事件按配置费率和 billsec 结算；无话单保留全额预占 |
| 跨小时 / 跨日 | 活跃通话及跨界结束通话保守计入当前窗口，不能靠跨整点清空额度 |
| 最长通话 | originate 时设置 `execute_on_answer='sched_hangup +N ALLOTTED_TIMEOUT'`，由 PBX 执行；转人工不移除原时限 |
| 超时 / 响应丢失 | 业务容量保持占用；先挂断并核对 PBX，不把数据库超时视为电话结束 |
| 未观察到的异步任务 | 即使 UUID 暂时不存在，也可能仍排在 PBX 队列中；不自动释放、不发起下一尝试，等待人工核对 |
| 已观察过的通道 | 有可信 PBX 事件或 UUID 存在证据；超过安全截止且确认 UUID 消失，或收到结束事件后才能释放 |
| 回调 | 固定受信任 origin + 固定回调路径；禁止查询串、凭据、fragment 和自动重定向；不向调用方任意 URL 发送 Token |
| 回调可靠性 | FreeSWITCH 与 Pipecat 共用签名发送端和持久化 outbox；稳定事件内容、重新签名重试；故障超过阈值拒绝新拨号 |
| 紧急停拨 | 独立管理凭据控制持久化开关，重启不清除；普通拨号凭据不能恢复拨号 |
| 转人工 | 后端核验同租户、启用的真实坐席；网关只接受 `agent:<id>`；拒绝自由形式号码、未知坐席及跨租户目标 |
| WebRTC | 用户进入 `browser-no-outbound`，禁止浏览器直接发起外线；ESL 转接走独立 `agent-restricted`；SIP REFER 禁用 |
| 租户设置 | 日量、目的号段、并发不能突破平台硬上限；真实线路和安全设置变更需要独立审批凭据 |
| 机器 API Key | 生产默认只读，写操作必须在 `TENANT_API_SCOPES_JSON` 显式授权 |
| 监控 | 持久化停拨/拒绝审计；输出活跃意图、回调积压、回调年龄、拒绝计数；新增 Prometheus 告警规则 |

## 配置与升级

1. 在受控配置/密钥系统生成不同的至少 32 字符随机值。`VOICE_COMMAND_SECRET` 只用于后端到网关；`TELEPHONY_WEBHOOK_SECRET` 由 Compose 映射为网关 `WEBHOOK_SECRET`。其他字段见 `.env.example`。真实环境禁止沿用测试密钥。
2. 将号码统一为包含国家码的格式，并与业务后端 `OUTBOUND_ALLOWED_PHONE_PREFIXES`、网关白名单同时核对。未迁移的国内号码将被安全拒绝，不能放宽为任意号码来解决。
3. 按 `deploy/security/voice-routes.example.json` 建立真实授权路由；示例仅有虚构号码和不存在的线路，不可直接用于真实外呼。
   - `1:0` 表示租户 1 的默认线路；数据库线路则用真实 `tenant_id:telephony_line_id`。
   - 设置 `VOICE_SECURITY_ROUTES_JSON`，或把独立文件只读挂载并设置 `VOICE_SECURITY_ROUTES_FILE`。文件优先。建议部署阶段校验后原子替换，不在管理页面暴露此配置。
   - 费率和预算均用同一种货币的整数最小单位；所有线路须统一币种。费率必须包含供应商计费粒度、双腿、附加费用的保守上界。默认乘数至少 2，不是从运营商读取的实时资费。
4. 挂载持久化本地卷 `/var/lib/voice-security`，UID/GID 10001 可写。更新后的 Dockerfile/Compose 已声明。不得用 tmpfs/NFS，不得删除账本来“重置”额度。
   - 当前是单宿主机、单媒体 worker 方案；启动锁拒绝第二个共享账本的 worker。
   - 不允许给同一线路的多个副本分配各自独立账本，否则每份预算各算一份。扩容前必须迁移到共享事务式准入服务并重新验收，不得只增加容器副本。
   - 账本包含号码和待发回调正文，按敏感数据保护、加密备份并限制文件访问；备份恢复不得回滚到早于仍在 PBX 运行的意图。
5. 将 `deploy/freeswitch/dialplan/agent_restricted.xml` 放入 FreeSWITCH 顶层 dialplan 目录，不是 `default/` 子目录。真实网关固定使用 `agent-restricted`。
   - WebRTC 配置采用更新后的 profile；禁止把浏览器用户放进通用 `default` 上下文。
   - 坐席分机模板与受限拨号计划须一致。示例匹配 `agent_<数字>`；不要新增外线路由或通用 fallback。
   - 承载运营商线路的 Sofia profile 同样须配置 `disable-transfer=true`、`manual-redirect=true`，并验收 REFER / 302 不会绕过号段策略。仓库中的 gateway 注册片段不等于完整 profile。
6. 真实线路及合规/容量变更需普通管理员身份加 `X-Security-Approval`，其值与 `OUTBOUND_SECURITY_APPROVAL_TOKEN` 一致。该凭据由安全运维保管，不保存到浏览器。
   - 这是独立第二凭据门禁，不冒充已集成的 MFA 或双人审批工作流；可接入现有运维审批入口。
   - 生产机器写权限示例：`TENANT_API_SCOPES_JSON={"1":["contacts:write","campaigns:write","calls:dial","calls:control"]}`。按实际需要最小授权。
7. 按匹配版本同时更新后端与网关。旧客户端没有签名/租户身份会被拒绝。未经验证的 `pbx_http` 透传驱动暂禁止启用真实拨号，以免绕过 PBX 硬时限和恢复约束。
8. ESL 只允许网关控制服务，不公开 8021；SIP 仅允许运营商及授权接入。不同服务不要共享整份含所有密钥的环境文件；使用服务专属配置及独立网络。

## 紧急停拨与故障处理

- `POST /v1/admin/security/stop`，Bearer 使用 `VOICE_SECURITY_ADMIN_TOKEN`，正文 `{"stopped":true,"reason":"异常话费排查"}`。恢复使用 `false`，同样需要独立凭据并留审计。
- `GET /v1/admin/security` 查看停拨、活跃意图、回调积压和拒绝计数。
- 停拨阻止新意图；已受理通话仍受原 PBX 最大时长约束。如需立刻结束已有电话，逐通执行授权挂断并核对运营商侧话单/余额，不能只看业务数据库。
- 回调不可用时自动暂停新拨号；修复网络、签名或接收端后 outbox 自动重试。禁止通过关签名或清空队列恢复。
- 未观察过的异步 PBX 任务保持隔离：人工核对该 UUID、PBX 排队任务和话单，必要时安排受控 PBX 恢复。不要仅凭“查询不到通道”直接释放账本记录。
- 平台预算是基于配置上界的本地风控，不是运营商结算账本；必须另设运营商话费封顶、IP 白名单、国际/高资费目的地限制。代码无法阻止泄露的 SIP 凭据被拿到本系统之外使用。
- Prometheus 规则已补齐；Alertmanager 外部接收方仍需由部署方配置，不代表通知已送达。

## 验收入口与状态清单

| 入口 | 本轮验证方法 | 边界 |
| --- | --- | --- |
| 登录 / 退出 / 账号锁定 | 后端 TestClient 回归 | 浏览器点击未验证 |
| 线路新增 / 修改 / 停用 | API 保存、回显、权限拒绝、审计回归 | 仅合成配置 |
| 合规设置 / 并发容量 | API 保存回显、独立凭据、越上限拒绝 | 浏览器点击未验证 |
| 直接呼叫 / 任务 / 重试 | mock 后端链路与网关安全测试 | 无真实外呼 |
| 转人工 / WebRTC 目录 | 同租户坐席校验、自由目标拒绝、受限上下文配置 | 真实坐席/SIP REFER/302 未验证 |
| 网关鉴权 / 签名 / 防重放 | ASGI 与跨服务生产 HMAC 协议测试 | 使用合成密钥 |
| 幂等 / 配额 / 重启 / 停拨 | 持久化账本、并发竞争、跨窗口与故障测试 | 单宿主机方案 |
| PBX 硬挂断 | 实际 FreeSWITCH null 通道，控制服务退出后观察挂断原因 | 不是运营商 SIP/RTP 验收 |
| 页面入口 | 隔离实例已启动；浏览器返回 `ERR_BLOCKED_BY_CLIENT` | 无法据此声称页面点击验收通过 |

外呼项目不包含“首页→搜索→门店/房型→购物车→支付”购物链路；此项不适用。对应业务回归是登录→客户/任务→呼叫→媒体/转人工→结束记录→退出。

可复现命令（使用各服务已安装依赖的环境）：

```sh
(cd voice_gateway && python -m pytest -q)
(cd backend && python -m pytest -q)
(cd frontend && pnpm exec tsc -b && pnpm test)
```

在隔离的 `media-probe` 容器运行 `scripts/verify_toll_fraud_freeswitch.py`：脚本强制把唯一允许的拨号目标替换为 `null/security-qa`，拒绝任何 Sofia/bridge 目标；测试 100 次请求只 originate 一次、控制器停止后 PBX 自动挂断、重启不重拨、已确认结束后释放容量。使用临时账本及合成凭据，不接触业务数据库。

## 发布状态

2026-09-02 本地验证：网关 112 项、后端 77 项、AI agent 6 项、录音适配 9 项、前端 6 项通过；后端 1 项 PostgreSQL 专属 advisory-lock 测试在 SQLite 环境跳过。TypeScript、Python AST、XML、Compose 配置及版本约束检查通过。

FreeSWITCH 实测：100 次请求只产生 1 个 null 合成通道；控制服务停止后 PBX 按 2 秒配置自动挂断，观察到 `ALLOTTED_TIMEOUT`；重启未重拨，终止确认后释放容量。VoiSmart 双向合成音频、播放排空、两种打断及挂断清理也已回归。以上不是实际 SIP/RTP、运营商计费或云语音验收。

源代码与配置已更新；测试环境、生产环境、真机、真实线路和并发长稳仍未验收。上线前须完成真实授权号码、运营商资费/封顶、路由和密钥配置、受限拨号计划、异常恢复、通知送达及安全验收。

实现参考：[FreeSWITCH 定时挂断](https://developer.signalwire.com/freeswitch/dialplan/dptools/)、[Sofia REFER 与重定向控制](https://developer.signalwire.com/freeswitch/users-and-endpoints/sip-profiles/)。
