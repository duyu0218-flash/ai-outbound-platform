# JDK 21、分包与日志配置验收（2026-10-22）

## 修改范围

`outbound-mono` 的 Java 源码、测试、MyBatis XML、应用配置、前端源码排版、构建依赖和对应 CI。其他历史服务模块未进行本次升级；原有未跟踪的迁移资料未改动。

主包为 `com.duozhi.outbound`，仅保留启动类 `OutboundApplication`。233 个生产 Java 文件按以下功能模块组织，模块内按实际类型使用 `controller / service / domain / dao / config / security / support / transport / worker / storage / web / annotation / exception` 等子包。

| 模块 | 职责 |
| --- | --- |
| auth | 登录、JWT、用户身份和权限 |
| customer | 客户、导入和禁打 |
| campaign | 外呼活动 |
| call | 通话状态、话单和回调入口 |
| dial | 拨号、授权、配额、批次和恢复 |
| script | 话术、流程和知识库 |
| ai | 对话策略和模型适配 |
| agent | 坐席、转人工和路由 |
| operations | 运营、线索、报表、团队、通知和配置 |
| system | 管理页面、系统设置、线路和审计 |
| callback / job | 回调收件箱与后台任务 |
| voice / media / recording | 网关、音频与录音 |
| integration | 内嵌服务配置、传输和独立账本 |
| monitoring / health / capacity | 指标、健康检查与容量证据 |
| common | 公共配置、事务、存储、工具和异常 |

38 个 Mapper XML 位于 `src/main/resources/mapper/<模块>/dao/`，与 Java 的 `<模块>.dao` 一一对应。已同步命名空间、resultMap/typeHandler、反射实体加载和租户 SQL 策略测试。Mapper SQL 的 XML 结构、属性、文本已与改动前版本逐项比较：忽略空白与包名迁移后，差异为零。Flyway SQL 未修改。对 227 个原有生产类进行 AST 比较，归一化包名、导入、可见性和常量字符串换行后，222 个类体一致；5 个类的实际改动分别为 Store 的 Mapper 查找、两处 YAML 默认配置读取、独立账本池的 JMX 唯一命名和 Spring AI 客户端迁移。

## 版本与配置

| 组件 | 修改前 | 修改后 |
| --- | --- | --- |
| Java | 17 | 21（本机验证使用 Zulu 21.0.12.1） |
| Spring Boot | 3.5.16 | 4.1.1 |
| Spring AI | 1.0.3 | 2.0.1 |
| MyBatis Starter | 3.0.4 | 4.1.0 |
| PageHelper Starter | 2.1.0 | 4.1.1 |
| Druid Starter | Boot 3 / 1.2.28 | Boot 4 / 1.2.28 |
| Springdoc | 2.8.16 | 3.1.1 |
| AWS SDK S3 / Apache client | 2.29.52 | 2.57.2 |
| Shiro Jakarta | 2.0.6 | 2.2.1 |

其余由 Spring Boot / Spring AI BOM 管理的依赖随版本矩阵升级。Maven Enforcer 要求 JDK `[21,22)`；对应单体 CI 使用 JDK 21。macOS ARM64 开发机通过 Maven profile 加载匹配的 Netty DNS 原生库；其他平台不激活该 profile。

- `resources/application.yaml`：应用、数据库、Redis、AI、上传限制、MyBatis 与日志参数。
- `resources/setting-defaults.yaml`：系统设置默认值。
- `resources/scenario-defaults.yaml`：对话策略默认值。
- `resources/logback-spring.xml`：依据用户提供文件适配。原始桌面文件保留。
- `resources/contracts/*.json`：API 与表结构契约，仍保留 JSON；数据库迁移仍位于 `db/migration/`。这些是接口/结构资料，不是应用配置。

Logback 保留控制台、按日及大小滚动、单文件 500 MB、7 天、总量 2 GB 的规则。排除 Shiro Starter 默认引入的 Log4j2，并以依赖约束和集成测试检查只有一个 SLF4J Provider。项目 logger 改为 `com.duozhi.outbound`，文件输出使用 encoder；根日志默认 WARN，应用默认 INFO。`LOG_PATH`、`LOG_LEVEL_ROOT`、`LOG_LEVEL_APP` 通过 YAML 配置。默认目录 `applog/` 已加入忽略规则。关闭 Logback 自行扫描，避免重新加载时无法识别 Spring 的 `springProperty`；配置修改通过重启应用生效。

Spring Boot 4 应用层暂用官方 `spring-boot-jackson2` 兼容模块，保留既有 JSON 业务契约；Spring AI 2 使用其新客户端。模型适配继续通过共享 JDK HTTP 客户端访问预先验证的完整端点，保留超时、1 MiB 响应上限、无自动重试、限流冷却和用量记录。实时流式模型接入仍需单独实现与验收。

官方版本适配依据：[Boot 4 迁移指南](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)、[Spring AI 支持矩阵](https://docs.spring.io/spring-ai/reference/getting-started.html)、[MyBatis Starter](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)。

## 页面、入口和状态清单

本次修改影响全应用启动与全部 API，因此回归覆盖所有现有页面。页面路径和业务文案保持原状。

| 页面 / 入口 | 应检查状态 |
| --- | --- |
| `/admin/login`、`/agent/login` | 登录成功、错误密码、未登录跳转、退出后令牌失效 |
| `/admin` | 首页概览和导航 |
| `/admin/contacts`、`/admin/contacts-operations` | 搜索、新增、编辑、保存回显、导入导出、禁打、异常输入 |
| `/admin/scripts`、`/admin/knowledge` | 新增、编辑、流程配置、知识保存与检索 |
| `/admin/campaigns` | 新建、启动、暂停、停止、重拨及状态回显 |
| `/admin/calls`、`/admin/quality` | 话单、明细、通话状态、质检与人工复核 |
| `/admin/reports`、`/admin/group-monitor`、`/admin/billing` | 报表、团队监控与用量内容 |
| `/admin/users`、`/admin/lines`、`/admin/settings` | 账号、线路、开关、表单保存和权限异常 |
| `/admin/product`、`/admin/system` | 策略保存/试跑/发布、健康状态与审计 |
| `/admin/operations/{calls,leads,reports,teams,configs,notices,workspace}` | 话单搜索、线索跟进、报表、团队权限、表单标签、通知和个人配置 |
| `/agent`、`/agent/calls`、`/agent/quality` | 工作台、坐席状态、记录隔离、主管质检权限 |
| `/agent/operations/{calls,leads,reports,notices,workspace}` | 坐席数据范围、只读边界和个人配置 |
| 返回、退出、401/403、非法路径 | 浏览器返回有效、会话失效、越权拦截、异常提示 |
| `/healthz`、受保护指标、Druid、OpenAPI | 启动与访问边界 |

关键回归链路：登录 → 客户/搜索/导入 → 话术/活动 → mock 外呼 → AI 对话 → 转人工 → 通话/质检 → 线索/运营报表 → 返回/退出。项目无门店、房型、购物车、商城订单或支付入口，用户规则中的商城链路不适用于本项目。

## 可重复执行的检查

```sh
# 当前目录：outbound-mono，JAVA_HOME 指向 JDK 21，Maven 3.9.11
mvn -B clean verify
mvn -B enforcer:enforce
mvn -B spotless:check
sh tools/check-format.sh
pnpm --dir frontend format:check
java tools/FormatMapperXml.java --check
```

Java 使用四空格 AOSP 格式；JS / Vue / HTML / YAML 使用两空格，普通 XML 使用四空格。MyBatis XML 由 JDK 原生工具单独排版，保留 SQL 运算符及绑定表达式。规则分别保存于 `.editorconfig`、`frontend/.prettierrc.json` 和 Maven Spotless 配置。

## 本轮验证与发布状态

最终 `mvn -B clean verify` 于 **2026-10-22 14:44（Asia/Shanghai）通过**，耗时 6 分 4 秒。

| 状态 | 本轮结果 |
| --- | --- |
| 源代码已修改 | 是；本轮 Git 提交包含源码、构建配置与验收记录 |
| 静态检查通过 | 是；JDK/Maven/依赖约束、291 个 Java 文件的 Spotless、前端/HTML/普通 XML/YAML 格式、38 个 Mapper XML 格式 |
| 开发环境功能测试通过 | 是；222 单元 + 319 集成 = 541 项，失败/错误/跳过均为 0；含 27 组 Chromium 页面验收 |
| 测试环境已发布 | 否 |
| 真机测试通过 | 未验证 |
| 生产环境已发布 | 否 |

已验证：菜单点击、正确跳转、页面内容、表单填写、保存回显、返回、登录退出及权限异常；完整本地 mock 外呼/AI/转人工链路；MySQL 数据层 121 项、全部 19 个 Flyway 迁移、Redis、MyBatis/分页、鉴权和 OpenAPI；独立 Jar 的健康页、Vue 页面与静态资源、指标权限及重拨；只有一个 Logback 日志 Provider，控制台与滚动文件 appender 启动并实际写入文件。

未验证：真实设备与语音链路、测试/生产部署、目标 Linux、生产对象存储与真实云模型、容量与长稳；日志的 7 天保留和 500 MB 滚动阈值未经过真实时间/体积触发验证。本次验证配置加载与写入，不将阈值配置等同于长期运行验收。

已知项：业务 JSON 暂保留 Jackson 2 兼容模块；启动仍有 Shiro/Spring BeanPostProcessor 生命周期 WARN，现有鉴权和业务回归已通过。原始日志中的 `isolated database-failure regression fixture` 异常为恢复测试主动注入，不是测试失败。没有将本地结果作为生产可用结论。

机器摘要：[refactor-validation.json](refactor-validation.json)。本机原始证据保留于 `verification/refactor-20261022-jdk21/`（已忽略，不随 Git 提交）；当前可执行包为 `target/outbound-mono.jar`。

## 未验证项、环境限制及上线前置条件

- 当前为 macOS ARM64 本地开发验证；Linux 目标宿主、真机、真实 PBX/SIP/RTP、云 ASR/LLM/TTS、生产对象存储和真实录音链路需单独验收。
- 模拟链路与真实数据库功能测试不能证明 500 CPS 或真实媒体容量；本次未开展容量压测。
- 正式上线前需完成测试环境部署、真实语音/录音/设备与容量/长稳验收、备份恢复、单实例停机升级和回滚演练。不能仅凭本轮测试开启 `JAVA_RELEASE_ACCEPTED`。
- Jackson 2 兼容模块属于后续迁移事项；移除前需对 JSON 契约、签名及全部 API 重新回归。

本轮 Jar SHA-256：`d2ff5215c1219f2cd429fc759ed50ee7d2eed9ba92427e5a69fc65c4b5fd1380`。
