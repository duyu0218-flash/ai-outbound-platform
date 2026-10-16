# Java 核心后端静态安全加固与回归验收

日期：2026-10-16。本轮基线为 PR #52 合并后的 main `eb2b099bbd4be5c338fd92884410c3426d991c52`。用户已批准从 JDK 8 升级至 JDK 17 和受支持的 Spring。此记录不代表生产切换批准。

## 环境与制品

- Spring Boot 4.1.1、Spring Framework 7.0.9、Jackson 3.1.7、Tomcat 11.0.27。
- 本机实际构建/测试使用 Zulu JDK 17.0.20.1、Maven 3.9.11；83 个应用 class 的 major version 均为 61。
- 最终本机制品 SHA-256：`94d95c8af51029df86d4b9163e3b8c79933307c927846c739cc2a43f73fdfccc`。Linux CI 会独立构建镜像，不用本机 JAR 摘要冒充镜像摘要。
- SQLite、专用 PostgreSQL 16、Redis 7、Chromium；合成历史数据与 mock 外呼。未连接业务数据库或真实电话线路。

## 页面、入口和状态清单

| 页面或入口 | 验证的状态与动作 | 验证边界 |
| --- | --- | --- |
| 管理员/座席登录、退出、返回 | 正确和错误登录、退出撤销、返回保护、座席访问管理员页面重定向 | 浏览器及 API；真实 SIP 登录未验证 |
| 管理首页及全部菜单 | 管理路由加载，16 个菜单逐项点击并返回首页 | 验证入口，不代表每页全部操作覆盖 |
| 客户管理、CSV | 新建、搜索、刷新回显、双租户缓存隔离；导入去重、错误行、导出和公式转义 | 浏览器表单；文件处理为 API 测试 |
| 设置、用户、线路、话术模板 | 录音告知保存回显；权限/停用、配置校验、模拟线路和模板 CRUD | 设置已点测；用户和线路所有表单操作未验证 |
| 外呼任务 | 表单填写、选择客户、编辑、准备、启动、暂停/恢复、停止、查询 | 浏览器覆盖准备/启动/停止；部分状态由 API 覆盖 |
| 通话记录、分析、报表、账单、分组 | 模拟拨号完成、状态回调、事件抽屉、分析生成、报表页及返回首页 | mock 闭环；真实渠道账单未对账 |
| 产品策略、预约、知识和待办 | 策略字段/FAQ 保存、意图模拟、过期版本拒绝；知识导入搜索、待办完成、预约改期取消 | 浏览器及 API；全部业务组合未穷举 |
| 话术流程编辑器 | 收集变量保存、新节点及刷新；发布和自动节点环路校验 | 浏览器及服务测试；实话术音频未验证 |
| Webhook、转人工、媒体回调 | 签名、去重、旧轮次失效、事务回滚、背压、转接无人接听恢复、快速播放回调竞态 | HTTP 模拟和集成测试；真实 PBX/WebRTC 未验证 |
| 网关、稳定性及后台维护 | 节点授权、容量/CPS、健康时效、地址变更保护；Redis 压力控制、消费者心跳；到期脱敏/删除、幂等压缩 | 隔离集成测试；生产规模、故障恢复及公平性未验证 |
| 数据库维护入口 | plan/apply/verify、旧版本历史复用、重复执行、冲突阻断、失败版本事务、备份恢复、历史字段保留 | SQLite/PostgreSQL 合成历史副本；真实旧库未验证 |
| 健康、指标、HTTP 和流式连接 | 初始化就绪门槛、依赖探针、指标鉴权、限流、主机名/CORS、JWT 到期断流 | 本地测试；分布式流量及生产故障未验证 |

本项目没有门店、购物车、支付页面。适用链路为：登录 → 客户 → 话术 → 外呼任务 → 拨号 → 回调 → 通话记录 → 分析/报表 → 返回首页。

## 本轮涉及入口和异常状态

| 入口 | 已执行验证 | 范围 |
| --- | --- | --- |
| 登录、API 鉴权、租户头、Webhook | 8 类安全头重复/异常拒绝；有效用户正常登录；请求未写入业务事件 | 单测、MockMvc；实际 Tomcat HTTP 另见 security-http-smoke.json 与 java-image CI |
| Host、CORS、错误响应 | Unicode Host 拒绝、明确 origin、禁止 Cookie 跨域凭据、错误 JSON 及 nosniff | 定向回归和实际 HTTP；真实反向代理未验证 |
| 线路及安全审批 | 非法凭据引用/URL 不落库；管理员伪造独立审批不能启用真实线路 | API 及服务测试；真实线路表单所有操作未验证 |
| Redis、业务回调、健康 CLI | 生产明文 pre-connect 拒绝；真实 TLS 正常/负例；RESP 深度/总量；健康进程不跟随跳转 | 本机合成证书和独立子进程；目标环境 CA 未验证 |
| 网关文件及指标令牌 | 规则文件、大小上限、缺失文件/目录拒绝、Secret 符号链接 | 文件单测；部署 Secret 挂载仍需现场验收 |
| 安全 CI | 全部提示及 112 个路由登记、83 个 class 名称覆盖、96 个源码资源摘要 | 15 项门禁故障测试；新增调用方改动也阻断 |

## 本次修改与检查

- 修复 Unicode 主机名折叠、重复安全请求头、CORS 通配符/跨域 Cookie 凭据、生产 Redis 明文；四项均保留修复前失败与修复后通过证据。
- 协议 ASCII 大小写、凭据引用、出站头、JSON 错误、配置文件读取和 Redis 响应边界加固；健康检查只访问有界数字端口的 loopback，禁止代理/跳转。
- 修复前端 4 条 moderate 告警（2 个独立公告），Vitest 4.1.11、brace-expansion 2.1.7/5.0.12；前端业务代码未改变，单测类型适配已更新。
- 回调摘要使用常量时间比较；HTTP 传输拒绝非 HTTP(S)、URL 凭据及异常地址；CORS Origin 增加 URI 校验。
- 完整安全范围、原始提示复核、CI 门禁及限制见 [SECURITY-REVIEW.md](SECURITY-REVIEW.md)。历史 JDK 8 结果见 [历史快照](ACCEPTANCE-JAVA8.md)，不能代替当前版本验证。

## 当前制品实测结果

- SQLite 和 PostgreSQL 各 111/111，0 失败、0 错误、0 跳过，包含真实隔离 Redis 测试。
- Chromium 管理端 13/13、模拟外呼 1/1，全通过无跳过；覆盖菜单点击、表单填写、保存刷新、返回、登录退出和权限异常。
- 前端 11 项单测、覆盖率门槛、TypeScript/Vite 构建通过；audit 各严重级别为 0。
- Java 91/91 依赖包 OSV 扫描无公告；83/83 应用 class 完成 SAST；原始提示 171 → 149，37 项安全观察和 112 项路由信息全部复核登记，未复核项 0；详见安全报告。
- 最终制品 Python/Java 行为对照 271/271、108 项路由边界和 98 项成功响应内容匹配；真实 Python 规则 Agent/mock 网关跨进程联调 4/4。
- PostgreSQL 22 个版本升级、重复执行、8 类业务数据摘要、pg_dump/pg_restore 恢复验证通过；全部使用合成历史副本。
- 门禁本身 15 项故障注入测试通过。所有结果仅覆盖本记录描述的开发环境和用例。

## 复现

```sh
JAVA_TEST_REDIS_URL='<isolated-redis-url>' bash backend-java/scripts/build-java17.sh package
JAVA_TEST_DATABASE_URL='<isolated-postgres-jdbc-url>' DATABASE_USER='<test-user>' DATABASE_PASSWORD='<test-password>' JAVA_TEST_REDIS_URL='<isolated-redis-url>' bash backend-java/scripts/build-java17.sh test
bash backend-java/scripts/security-check.sh
python3 -m unittest discover -s backend-java/scripts -p 'test_security_check.py'
```

设置真实 JDK 17 的 `JAVA_HOME`；可用 `MAVEN_BIN` 指定 Maven。PostgreSQL 使用可重建的专用空库。浏览器与 Python 联调复现方式见 Java README 和 `scripts/`；不得指向生产。当前复测摘要见 `validation/`，完整 CI 报告以实际远端运行结果为准。

## 未验证、已知约束及上线条件

- 真实旧库和现场自定义结构未验证；必须用真实脱敏副本演练迁移和恢复。
- 真实 SIP/RTP/WebRTC、录音适配器/对象存储、云模型、短信渠道及真实账单对账未验证。
- 500 总在途、长稳/突发、跨租户公平性、跨进程接管和进程崩溃恢复未验证。
- 生产 Java 拒绝明文 Redis，须先提供 rediss:// 和 JVM 信任证书。本机可信 TLS、错误主机名、未信任证书、凭据发送前拒绝已实测；真实部署 TLS 端点未验证。依赖与静态扫描不等同于渗透测试。
- `/readyz` 默认因 `JAVA_RELEASE_ACCEPTED=false` 保持 503；验收标记不能代替实际验收。无部署或生产切换。
- 正式上线前完成真实数据迁移恢复、目标环境发布、真机与真实通话链路、渠道对账、容量及故障恢复；切换时停止旧核心和 worker，防止双重消费。

## 六项交付状态

| 状态 | 结果 |
| --- | --- |
| 源代码已修改 | 是，协议/请求头/CORS/Redis/TLS/配置文件及安全门禁加固 |
| 静态检查通过 | 本机编译、Git 空白检查、依赖扫描及已说明范围的 SAST 门禁通过 |
| 开发环境功能测试通过 | 仅实际通过并保存在 validation 的用例范围 |
| 测试环境已发布 | 否；隔离测试容器不算发布 |
| 真机测试通过 | 未验证 |
| 生产环境已发布 | 否 |
