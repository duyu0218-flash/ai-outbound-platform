# 全 Java 后端候选验收记录

日期：2026-10-17。统一版本 **JDK 17 + Spring Boot 4.1.1**。本轮修改位于 `migration/java-services-20261017`，没有替换现有部署。

## 状态分层

| 层级 | 本轮状态 |
| --- | --- |
| 源代码 | 四个辅助角色已有独立 Java 实现；核心继续使用 `backend-java`。旧 Python 业务实现保留为基准/回退参考 |
| 静态检查 | 编译、差异检查、四种 Java Compose 组合、32 个旧显式路由及 8 个文档入口、158 个配置字段清单通过；两模块 OSV/FindSecBugs 门禁通过 |
| 开发环境 | Java 测试、三服务真实进程链路、媒体独立进程重启、浏览器及四角色 Linux 临时容器通过，范围见下文 |
| 测试环境发布 | 未发布；一次性本机测试进程/容器不等于持续测试环境 |
| 真机测试 | 未验证真实 SIP/RTP/WebRTC、外呼号码、云语音/模型 |
| 生产环境发布 | 未发布；没有停止、重启或替换业务进程/数据库 |
| GitHub/远端 CI | 新增了 `java-services.yml`；以对应提交的实际运行结果为准，本地检查不替代远端 CI |

核心既有验收见 `../backend-java/ACCEPTANCE.md`，其历史测试数字不计入本轮新增测试。

## 涉及的入口、状态与实际验证

| 页面/服务入口 | 涉及状态 | 已实际验证 | 未验证边界 |
| --- | --- | --- | --- |
| 管理登录、16 个菜单、返回首页、退出 | 正确/错误密码、权限跳转、退出后后退 | Chromium 实际点击、内容可见、登录失败/退出保护 | 真机浏览器及生产域名 |
| 客户表单、客户搜索、录音告知设置 | 新增、搜索、刷新、保存回显 | 实际浏览器填写及保存/刷新回显 | 每个管理表单的全部字段和异常组合 |
| 任务 → 网关 → 话单 → 分析 → 报表 | 启动、in_ai、签名终态、completed | Java 核心/Agent/网关分别运行；浏览器任务启动及话单/报表返回 | 真实运营商、人工坐席、长通话 |
| Agent `/agent/start`、`/agent/turn`、`/readyz` | 中英文、否定转人工、短信、外部授权、额度、追踪 | 520 个原 Python 策略样例；真实本机 HTTP 模型/OTLP 夹具；鉴权、脱敏、共享额度 | 真实 LLM/Langfuse 账号、HTTP/2 性能和生产额度 |
| 录音 ingest/delete、签名源下载/清理 | 存取、重复、删除标记、校验失败、源回收 | 实际 SeaweedFS SigV4；Java 源下载 → S3 → 回读校验 → 清理；重复确认/墓碑 | 外部 S3/TLS、业务历史文件、断电/磁盘故障 |
| 网关命令/预算/队列 | 签名、防重放、跨租户、未知结果、精确 200 ACK、保序 | 本机 HTTP/TCP、持久化回归；SQLite 提交失败整体回滚 | 实际 FreeSWITCH 进程、真实转人工/计费/网络抖动 |
| 录音告知和媒体启动/停止 | 12 路等待告知、挂断并发、迟到响应、启动失败、超时 | 告知等待不占启动线程；挂断围栏；确认清理后显式回退；慢挂断不堵全局超时线程 | 实际 PBX 的全部事件顺序和资源耗尽 |
| 原生 PCM/ASR/TTS | 合成语音、最终转写、播放事件、打断、关闭 | 真实本机 ASR WebSocket/TTS HTTP 夹具；重采样/滤波、全域 G711 对照；ASR 未应答时关闭 | 云 OpenAI/阿里云及真实电话音频、本地 VAD 校准 |
| 媒体 `/internal/state`、`/internal/command` | 认证、epoch/session/attempt/语句/期限围栏、关闭、重启 | 实际独立 Java 进程强制结束并重启，旧 epoch 被拒绝 | 控制器账本与多工作器同时崩溃的完整接管 |
| 四角色 `/docs`、`/openapi.json` | 加载、参数展开、筛选、JSON 链接、返回、鉴权不变 | 四角色实际容器 HTTP；Agent/网关实际 Chromium CSP/筛选/展开/返回 | 全接口每个字段/响应组合 |
| 网关/录音监控、媒体状态 | 有效计数、凭据、无事件、工作器不可用 | 实际容器鉴权/JVM指标；TTS 首包/PCM计数、根事务回滚、HTTP聚合sum/max/缺失 | 目标监控平台、自定义告警迁移与长期时序 |
| Java 执行器与准入 | 真实通道进展、停止/超时、Redis发布、共享状态 | 本机线程/数据库及独立 Redis；API不执行任务、唯一调度者配置 | 11工作器合并部署与吞吐/长稳 |
| HTTP/容器边界 | 缺失/错误凭据、重复头、坏 JSON、超大/分块请求、角色隔离 | 实际 Tomcat HTTP；非 root、只读 root、noexec 临时目录的四角色容器 | 完整渗透测试和目标 Linux 主机 |

本项目不是电商；本轮关键业务链路采用“首页 → 客户搜索 → 任务 → 拨号/回调 → 通话记录 → 分析/报表 → 返回/退出”。未执行购物车、下单或支付链路。

## 可复查证据

- `validation/java-stack.json`：5 项服务/浏览器链路检查，记录核心及辅助 jar SHA-256。
- `validation/ci-harness-regression.json`：旧对照 CI 出现过导航总时限和启动/突发叠加竞态；补齐实际页面标题/退出 HTTP 200，以及两进程就绪同步，保留精确 500/800 预算和遇锁失败关闭。本地旧 Agent 39 项及两项三轮重复通过；不修改 Python 业务实现。
- `validation/java-services-browser.json`：8 项 Chromium 用例，无跳过或失败。
- `target/surefire-reports/`：38 项 Java 用例，包含独立 S3 夹具；报告由测试重新生成。
- `../backend-java/validation/java-migration-close-tests.json`：SQLite 与 PostgreSQL 各 113 项核心回归，无失败或跳过；包含实际 Java AI 通道进展、停止/陈旧信号及隔离 Redis 发布。
- `../backend-java/validation/java-close-container.json`：本轮核心 Linux 镜像的登录/前端/凭据/CORS/发布门禁/模拟外呼回调和分析通过。
- `../backend-java/validation/worker-delay-regression.json`：1100ms 延迟读取会使旧算法的回归断言失败；新算法及完整核心回归通过，心跳保留实际进展年龄。
- `../backend-java/validation/java-security.json`：91/91 Maven 包，公告 0；84 个主类、149 条原始提示全部纳入门禁。
- `validation/java-single500.json`：Java 单机静态预算及 10 项 Java/Python 配置回归；不表示 500 路容量通过。
- `validation/java-media-process.json`：5 项独立工作器 RPC/崩溃重启检查，与 jar 摘要绑定。
- `validation/java-containers.json`：四个 Java 角色和 S3 的隔离容器结果，记录实际镜像 ID；实际删除/重建网关后，同一命名卷中的安全停止标记保留，readiness 仍拒绝，显式恢复后才就绪。
- `validation/java-compose.json`：默认、兼容覆盖、compact 和单机 500 四种配置组合的 Java 命令、健康检查、host 端口唯一性和可写录音挂载；旧 Python 任务进程不在默认集合内。
- `validation/routes.json`、`validation/configuration-mapping.json`：静态映射，不能当作所有路由或配置组合已执行。
- `validation/java-security.json`：73/73 Maven 依赖包，公告为 0；51 个主类全部分析，47 条原始提示保留，18 条分析提示和 29 个路由观察逐项复核，未复核为 0。提示不等于已确认漏洞。

原始 SBOM、OSV、SpotBugs XML 均保留在 `target/` 并由 CI 上传。复核记录绑定完整源码摘要、方法/位置及出现次数；不得仅刷新摘要来放行变化。

## 旧状态兼容

Java 网关保留 Python 表结构、中文 payload 指纹、指令的 ASCII JSON 指纹、额度和回调字节。新增事务式升级补齐早期 `outbox.stream_key` 及 `voice_action_commands` 归属字段，补齐索引后再提交；无法解析的旧队列导致整次升级回滚。

`LegacyLedgerTest` 除早期表结构夹具外，还读取原 Python `Ledger`、`VoiceQuota`、`ActionCommands`、`CallbackSender` 实际生成的合成库转储，检查重试不重复拨号、中文摘要、未知物理额度、未知播放结果和队列归属不丢失。夹具的源文件摘要位于 `src/test/resources/legacy-python-ledger.json`。它不包含业务客户数据，也不能替代真实旧库副本验收。

Python 的 flock 与 Java 的 FileChannel 使用不同 owner 文件，不能互相排斥。必须先排空并停止所有旧控制器/执行器/工作器，备份 SQLite 主文件及 WAL/SHM、额度文件、录音生命周期、对象和 PBX 源目录，再在离线副本上运行迁移与回退演练。未知拨号、媒体归属及语音额度不得按 TTL 清空或因换语言而释放。

## 已知问题与环境限制

1. 完整 Codex Security 差异扫描尚未完成。旧扫描 `be158319-0569-403d-b6fa-1d10ddcf40fa` 固定在 `dd735c6`；最新扫描 `7e460d74-706b-4eff-b78a-412b872f2cfa` 固定在 `8cf9db8`，新架构模型已保存。两次清单工具均因宿主 Apple Git 2.39.5 不支持 `git cat-file --batch -Z` 而失败，没有封存为通过结果。可用新版 Git 已存在于仓库外，但未擅自更改 MCP 宿主环境或用户配置。随后默认网关账本挂载和慢数据库心跳时间修复有独立配置、实际重建容器与时序负向对照验证，位于固定扫描范围之后；不能以该修复或 OSV/SAST 门禁代替完整差异审查。
2. 文档页和 OpenAPI 已迁移；旧 Python writer/事件循环专属指标改用实际 JVM、事件积压和根事务指标，映射见 MONITORING.md。部署方自定义告警尚未接入验收；未知样本不当作 0。
3. 原 Python A-law 对 -7..-1 七个样本产生非法 byte；Java 使用合法零档值 0x55。其他 65,529 个输入及 256 个解码值已按原输出对照。这是有记录的行为修复。
4. OpenAI 路径保留 `gpt-realtime-whisper` 默认模型；原生本地 RMS VAD 需要真实音频校准。未通过云服务返回、电话录音和主观听感验收，不承诺识别质量/延迟。
5. 三套标准部署配置已直接选择 Java，旧文件单独保留为 python-reference。Java worker 统一执行租约任务/Inbox并上报真实准入信号；单机 11 条 AI 通道不能沿用旧 Python 640 异步槽位的吞吐结论。目标 Linux host 配置、Redis证书/truststore、全部进程的合并部署及 500 路性能尚未验证，详见 DEPLOYMENT.md。
6. ESL 是明文原生协议，仅允许可信私网/loopback 的部署边界；网络保密性不能由服务令牌代替。真实配置须提供独立强凭据、允许线路、回调地址、PBX 硬挂断和最小权限路由。
7. Docker Hub 访问超时；本轮隔离镜像改用 AWS 公共镜像副本构建，实际镜像 ID见验证报告。正式制品仍需固定并复验目标镜像摘要。测试环境资源不构成容量承诺。

## 正式切换前置条件

完成目标旧库/文件副本迁移及回退、持久卷/UID/磁盘容量核验、完整静态/差异安全复核、目标镜像/远端 CI、真实 PBX/ASR/TTS/模型/短信/S3/TLS 联调、录音清理和人工转接、控制器/工作器崩溃恢复、相同条件下的旧版对照与 500 总在途/长稳/积压门槛。生产还需通过核心 Redis TLS、CORS、演示账号关闭和凭据引用检查。

本轮通过的是已列出的源码与隔离开发验证，未声明全部业务状态、商用容量或生产发布已完成。
