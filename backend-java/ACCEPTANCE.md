# Java 8 核心后端迁移开发验收

日期：2026-10-16。基线 `96d9bef44173612d85399792bf54c6399059b1e7`，分支 `feat/java-core-backend-20261015`。本记录不能作为生产替换批准。

## 环境与制品

- Spring Boot 2.7.18；实际构建、测试和浏览器服务使用 Zulu JDK 1.8.0_504；Maven 3.9.11。
- 80 个应用 class 的 major version 均为 52。制品 `target/outbound-control-0.1.0-SNAPSHOT.jar` 的 SHA-256 为 `6f287daa5681fcad1ac0aadfb60cf46d83d8156b4e5d3f11cfafc411b17fd2e7`。
- 内存 SQLite、专用 PostgreSQL 16、独立 Redis 7；浏览器使用本机隔离 SQLite 合成数据。未连接业务数据库或真实电话线路。
- 108 项基线接口均有 Java 实现：108 mapped、0 partial、0 pending。路由存在不等于所有行为组合完全一致；271 项对照用例、独立集成测试和浏览器链路分别验证。

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

## 已验证项

- Java 8 编译、打包和字节码检查通过。
- Java 测试共 82 项，两组均 0 失败、0 错误、0 跳过。新增数据库迁移 7、HTTP/生产配置 6、回调传输 3，以及指标/就绪、短信稳定幂等、慢 AI 与控制通道隔离、流式令牌到期、CSV 回导。隔离 Redis 显式启用。各套件计数保存在 `validation/sqlite-tests.json`、`validation/postgres-tests.json`。
- Python/Java 对照 271/271 匹配，覆盖 108 项基线接口的访问边界；98 项成功响应内容比较。包括历史读取、CRUD、策略/流程发布、参数缺失、查询范围、权限和终态回调。生成时间/令牌/UUID 归一化，固定历史时间仍比较；错误只比较状态，不强求框架错误文本相同。并非 108 个接口的全部组合穷举。
- 旧 PostgreSQL 21 个原版本加 Java 兼容版本共 22 个版本迁移成功；只读计划不改数据、重复执行无新增版本、实际 pg_dump/pg_restore 后旧结构可恢复。8 类业务表行数/内容摘要一致，见 `validation/postgres-migration-rehearsal.json`。全部数据为合成历史夹具。
- 实际 Python 规则 Agent、mock 驱动语音网关与 Java 跨进程 HTTP 联调通过：拨号、签名状态回调、AI 请求、播报指令、终态回调、结果分析。见 `validation/retained-services.json`。没有使用真实 PBX、云模型或外部号码。
- 最新制品模拟外呼浏览器场景通过，覆盖启动、后台拨号、状态完成、事件查询、分析、报表/账单/分组页和返回首页；回调心跳准入开启。
- 浏览器发现并修复：流程边缺少 `keywords` 默认数组导致编辑页异常；消费者首次心跳缺少 `processed` 字段导致调度停滞。后者补充首次注册、重复心跳和消费计数断言。
- 播放结束回调早于 speak 响应时仍可安排挂断；旧轮次回调失效；转人工无人接听后释放座席并重新启动等待计时。
- Git 空白检查通过；前端业务源码未修改。新增 Java Dockerfile、Compose 覆盖文件、JDK 8 的 SQLite/PostgreSQL/Redis CI 配置。

## 复现方式与证据

设置真实 JDK 8 的 `JAVA_HOME` 及 Maven 路径后，在仓库根目录执行：

```sh
JAVA_TEST_REDIS_URL='<isolated-redis-url>' bash backend-java/scripts/build-java8.sh package
JAVA_TEST_DATABASE_URL='<isolated-postgres-jdbc-url>' DATABASE_USER='<test-user>' DATABASE_PASSWORD='<test-password>' JAVA_TEST_REDIS_URL='<isolated-redis-url>' bash backend-java/scripts/build-java8.sh test
```

PostgreSQL 必须是可重建的专用空库。报告在 `target/surefire-reports`，后一次会覆盖前一次。

在 `frontend` 目录，对独立夹具分别执行：

```sh
E2E_JAVA_CORE=1 E2E_REVIEW_TENANTS=1 E2E_BASE_URL=http://127.0.0.1:18820 node node_modules/@playwright/test/cli.js test e2e/java-core.spec.ts e2e/portal.spec.ts e2e/product-capabilities.spec.ts
E2E_JAVA_DISPATCH=1 E2E_BASE_URL=http://127.0.0.1:18822 node node_modules/@playwright/test/cli.js test e2e/java-dispatch.spec.ts
```

18820 夹具关闭调度，包含第二租户及 `browser-appointment` 预约；18822 开启 mock、调度、执行器和收件箱。不得指向真实环境。

本轮本机日志：`/private/tmp/java-current-build.log`、`/private/tmp/java-current-pg.log`、`/private/tmp/java-browser-current-tests.log`、`/private/tmp/java-dispatch-current-tests.log`、`/private/tmp/java-python-parity.log`。长期摘要位于 `validation/`；临时日志不是长期归档。

## 未验证项、已知问题与环境限制

- 真实历史数据副本和现场自定义数据库结构未验证；现有迁移覆盖基线原版本及合成旧结构，不保证所有现场定制。
- 真实 SIP/RTP/WebRTC、Python 录音适配器与对象存储的完整联调、云模型和短信渠道未验证。实际规则 Agent/mock 网关联调不等同于上述链路。
- 500 总在途、长稳/突发流量、跨租户公平性、跨进程租约接管和进程崩溃恢复未验证。已验证慢 AI 不阻塞控制通道，但不是容量结论。
- 短信使用稳定幂等键和持久记录；渠道不支持幂等或响应不确定时，仍需渠道级核对，不能承诺绝不重复发送。回调 DNS 地址过滤与 HTTP 响应解析有本地测试，公网网络行为未验证。
- Spring Boot/JDK 8 按指定版本构建，专项依赖漏洞扫描及安全加固评审尚未执行。
- Compose 配置解析通过；本机 Docker Hub 拉取超时，GitHub Linux x86_64 镜像构建与容器烟测已通过。SQLite 本地库在构建期提取至只读目录，保留只读根目录及 noexec 临时目录。容器验证登录、前端、默认发布门槛、客户、模拟拨号、回调和分析；不是目标环境发布。
- `/readyz` 默认因 `JAVA_RELEASE_ACCEPTED=false` 返回 503；验收标记开启仍须初始化、依赖和消费者健康。生产配置检查已实现；未批准或执行生产切换。
- 测试环境发布、真机测试、生产发布均未进行；GitHub CI 和 main 合并状态须以实际远端结果为准，本记录不代表已发布。

## 交付状态和上线前置条件

| 状态 | 结果 |
| --- | --- |
| 源代码已修改 | 是，核心后端迁移范围的 Java 实现、旧库工具与部署入口已补齐 |
| 静态检查通过 | Java 8 编译/打包/字节码及 Git 空白检查通过；专项静态安全分析未执行 |
| 开发环境功能测试通过 | 仅本记录中实际通过的范围 |
| 测试环境已发布 | 否，本机隔离服务不算发布 |
| 真机测试通过 | 未验证 |
| 生产环境已发布 | 否 |

上线前须在真实数据库副本复验升级与恢复，完成测试环境部署、关键页面与真实外呼/转人工/录音/短信链路验收、渠道对账、容量及故障恢复验证。停止旧核心和 worker，确认无双重消费后才能切换。发布验收标记只是配置门槛，不能代替以上证据。

## 最终复测结果

- SQLite：82 项，0 失败、0 错误、0 跳过；打包退出码 0。
- PostgreSQL：82 项，0 失败、0 错误、0 跳过；测试退出码 0。两组均包含隔离 Redis 验证。
- Java 制品管理端浏览器：13/13 通过，0 跳过。
- 最新 Java 制品模拟外呼浏览器：1/1 通过（8.1 秒）。总计 14 个浏览器场景通过。
- Python/Java 行为对照：271/271 匹配；实 Python 服务 mock 驱动联调：4/4；PostgreSQL 备份迁移恢复演练通过。
- 以上为开发环境验证，不代表真实线路、目标部署容量或生产验收完成。

## GitHub 合并检查补充

PR #51 的首次检查中，Java 8 两组检查及原服务单测通过，旧 Python 后端的 PyJWT 2.13.0 被安全扫描拦截；四个列表分页场景共用一个 30 秒用例导致 Compose E2E 超时。已将 PyJWT 固定为 2.15.1，并将分页场景拆为四个独立测试，保留翻到第六页、下一页禁用及返回第五页的断言。修复版依据 [PyJWT 官方变更记录](https://pyjwt.readthedocs.io/en/stable/changelog.html)。

本地补验：Python 后端 310 项通过、18 项环境条件跳过；PyJWT 升级后的双后端对照仍为 271/271；四个分页浏览器用例 4/4 通过（8.8 秒）。这四项使用拦截的分页响应，单独作为前端分页回归，不替代真实后端验证。新增 `java-image` CI 构建并启动一次性 Java 容器，验证前端、登录、默认发布门槛、模拟拨号与结果读取；远端结果以最新 CI 为准。

进一步补齐 Python 默认配置兼容：登录有效期 12 小时、锁定 15 分钟、导入 20 MiB / 20 万行 / 1000 条错误、录音保留 90 天、TURN 1 小时及 HTTP 超时/清理批次；登录和锁定增加断言，82 项双库测试及 271 项对照重新通过。前端间接依赖 tinypool、brace-expansion、source-map-js 固定到安全修复版本；单测 11/11、覆盖率门槛和构建通过，pnpm audit 高危门槛通过，仍报告 4 项 moderate，未声明零漏洞。

依赖更新后重建前端并复验：12 个管理场景和 1 个模拟外呼场景通过；预约夹具状态未及时重置导致该场景跳过，重置后单独复跑 1/1 通过，全部 14 个场景均有通过证据。见 `validation/frontend-security.json`。

Linux 容器证据：源提交 `ee9dc9f1a625deb5ba0bfb68cd21c170c11ce595` 的 [java-image CI](https://github.com/duyu0218-flash/ai-outbound-platform/actions/runs/38896193114/job/116957119735) 通过。该镜像在 CI 重新构建，不能用本机 JAR SHA-256 代替镜像摘要；其他架构镜像运行未验证。
