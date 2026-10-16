# Java 8 核心后端阶段验收

日期：2026-10-16。基线 `96d9bef44173612d85399792bf54c6399059b1e7`，分支 `feat/java-core-backend-20261015`。本记录不能作为生产替换批准。

## 环境与制品

- Spring Boot 2.7.18；实际构建、测试和浏览器服务使用 Zulu JDK 1.8.0_504；Maven 3.9.11。
- 73 个应用 class 的 major version 均为 52。制品 `target/outbound-control-0.1.0-SNAPSHOT.jar` 的 SHA-256 为 `6532e07f18b3bd6dd2abf556326511c9a157f9d1a4d23bd8587557746ced654b`。
- 内存 SQLite、专用 PostgreSQL 16、独立 Redis 7；浏览器使用本机隔离 SQLite 合成数据。未连接业务数据库或真实电话线路。
- 108 项基线接口均有 Java 路由：105 mapped、3 partial。路由存在不等于行为完全一致。

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

本项目没有门店、购物车、支付页面。适用链路为：登录 → 客户 → 话术 → 外呼任务 → 拨号 → 回调 → 通话记录 → 分析/报表 → 返回首页。

## 已验证项

- Java 8 编译、打包和字节码检查通过。
- 集成测试共 61 项：Auth 4、Management 4、Admin 3、Campaign 7、Lifecycle 6、Dispatch 5、Conversation 11、Operations 11、Gateway 4、Stability 4、Retention 2。最终 SQLite、PostgreSQL 结果见文末。隔离 Redis 显式启用，不能将默认跳过记为通过。
- 最新制品模拟外呼浏览器场景通过，覆盖启动、后台拨号、状态完成、事件查询、分析、报表/账单/分组页和返回首页；回调心跳准入开启。
- 浏览器发现并修复：流程边缺少 `keywords` 默认数组导致编辑页异常；消费者首次心跳缺少 `processed` 字段导致调度停滞。后者补充首次注册、重复心跳和消费计数断言。
- 播放结束回调早于 speak 响应时仍可安排挂断；旧轮次回调失效；转人工无人接听后释放座席并重新启动等待计时。
- Git 空白检查通过；前端业务源码未修改，本轮增加模拟闭环 E2E。

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

本机日志：`/private/tmp/java-core-final-sqlite-20261016.log`、`/private/tmp/java-core-final-postgres-20261016.log`、`/private/tmp/java-final-portal-20261016.log`、`/private/tmp/java-final-browser-20261016.log`。临时日志不是长期归档。

## 未验证项、已知问题与环境限制

- 原库全部增量迁移、真实历史数据转换、回滚和 Python/Java 全量行为差异未完成。新建库测试不能证明旧库可直接替换。
- 真实 SIP/RTP/WebRTC、Python AI/网关/录音服务完整实联网联调、云模型及短信渠道未验证。
- 500 总在途、长稳/突发流量、慢依赖下执行通道公平性、跨进程租约接管和崩溃恢复未验证。SQLite 单连接不代表生产容量。
- 外部回调网络边界、短信结果不确定时的渠道幂等、流式会话令牌过期仍需专项核验；复杂跨字段语义尚未全量对照。
- `/readyz` 仍返回 503、生产模式仍禁止启动。当前只能做隔离开发验证，不能称“完整可用”。
- 测试环境发布、真机测试、生产发布均未进行；本轮未推送 GitHub 或合并 main。

## 交付状态和上线前置条件

| 状态 | 结果 |
| --- | --- |
| 源代码已修改 | 是，核心后端迁移继续推进 |
| 静态检查通过 | Java 8 编译/打包/字节码及 Git 空白检查通过；专项静态安全分析未执行 |
| 开发环境功能测试通过 | 仅本记录中实际通过的范围 |
| 测试环境已发布 | 否，本机隔离服务不算发布 |
| 真机测试通过 | 未验证 |
| 生产环境已发布 | 否 |

上线前须完成旧库迁移和回滚演练、全量接口行为核对、关键页面及真实外呼链路验收、渠道对账、目标环境容量与故障恢复验证，并通过测试环境验收。完成后才能解除生产启动和就绪保护。

## 最终复测结果

- SQLite：61 项，0 失败、0 错误、0 跳过；打包退出码 0。
- PostgreSQL：61 项，0 失败、0 错误、0 跳过；测试退出码 0。两组均包含隔离 Redis 验证。
- 最新 Java 制品管理端浏览器：13/13 通过，0 跳过（32.9 秒）。
- 最新 Java 制品模拟外呼浏览器：1/1 通过（8.9 秒）。总计 14 个浏览器场景通过。
- 以上为开发环境验证，不代表真实线路、目标部署容量或生产验收完成。
