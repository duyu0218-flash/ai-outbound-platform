# 日志切面接入验收（2026-10-22）

## 实现范围

根据用户提供的桌面 `LogAspect.java` 和 `WebLogAspect.java`，适配当前 `outbound-mono` 的 Java 21、Spring Boot 4 / Jakarta、身份及审计结构。原始桌面文件保留，历史 `backend-java` / `services-java` 未修改。

- 增加 Boot 管理版本的 `spring-boot-starter-aspectj`，使用 Spring AOP 代理，无需 Java agent。
- `common.aspect.WebLogAspect` 拦截项目 `controller` 包中带请求映射的可代理方法（含 API 文档的包可见方法）；记录 HTTP 方法、路由模板、直接连接 IP、类/方法、参数与返回值结构摘要、结果状态、异常类型和毫秒耗时，使用现有 Logback 控制台和文件配置。异常原样抛出；辅助方法和无 HTTP 上下文的内部调用跳过请求日志。
- `common.annotation.Log` 和 `common.aspect.LogAspect` 复用 `Audit` / `auditlog`，记录可信身份中的租户、用户、操作名、方法、路由、IP、参数结构摘要、执行结果及耗时；已用于 `/api/v1/auth/logout` 和 `/api/v1/auth/presence`。已有显式业务审计保持原逻辑，避免批量叠加重复记录。
- 不序列化原始参数、返回正文、异常消息、上传文件和 Servlet 对象；不记录查询串、请求头或真实路径中的动态参数。修复参考实现的零参数越界、固定 `substring(0, 4999)` 导致短参数丢失，以及异常调用缺少耗时日志的问题。
- `ApiDocumentation` 移除 `final` 以支持类代理；媒体 WebSocket 通过 `VoiceController.gateway()` 访问实际目标对象的网关，避免读取 AOP 代理对象未初始化的实例字段。未改变接口路径和业务表结构。

## 页面、入口与状态清单

请求日志影响全部控制器入口，回归范围如下。页面内容与表单未改动。

| 页面 / 入口 | 状态与检查 |
| --- | --- |
| `/admin/login`、`/agent/login`、`/api/v1/auth/*` | 登录成功/错误密码、退出、令牌失效、坐席状态保存、权限和非法状态 |
| `/admin`、`/agent` | 首页、菜单点击、正确页面、内容展示、返回 |
| `/admin/contacts`、`/admin/contacts-operations` | 搜索、新增编辑、必填、保存回显、导入导出、禁打 |
| `/admin/scripts`、`/admin/knowledge`、`/admin/campaigns` | 话术流程、知识库、新建活动、启动及状态回显 |
| `/admin/calls`、`/admin/quality`、`/agent/calls`、`/agent/quality` | 通话明细、质检、重拨、坐席数据范围 |
| `/admin/reports`、`/admin/group-monitor`、`/admin/billing` | 报表、团队监控、用量展示 |
| `/admin/users`、`/admin/lines`、`/admin/settings` | 表单填写、保存回显、权限异常 |
| `/admin/product`、`/admin/system` | 策略保存/试跑/发布、服务健康、原有审计 |
| 管理员和坐席 `/operations/*` | 搜索、线索、团队、配置、通知、工作台及角色边界 |
| `/docs`、`/openapi.json`、`/healthz`、`/health`、`/readyz`、内部服务 | 文档、健康、权限边界及内部 mock 调用 |

关键业务链路：登录 → 客户搜索/保存/导入 → 话术/活动 → mock 外呼 → AI 对话 → 转人工 → 通话/质检 → 线索/运营报表 → 返回/退出。项目无门店、房型、购物车、商城订单或支付入口，商城链路不适用。

## 验证和发布状态

最终 `mvn -B -Dmaven.repo.local=../.tools/m2 spotless:apply clean verify` 于 **2026-10-22 17:25:36（Asia/Shanghai）通过**，耗时 5 分 56 秒，使用项目 `.tools` 中的 JDK 21 和 Maven 3.9.11。

| 状态 | 本轮结果 |
| --- | --- |
| 源代码已修改 | 是；源码和验收记录纳入本次 Git 提交，GitHub 同步不等于环境发布 |
| 静态检查通过 | 是；编译、Maven/JDK/依赖约束、297 个 Java 文件 Spotless、Mapper XML 与前端/资源 Prettier 检查通过，`git diff --check` 通过 |
| 开发环境功能测试通过 | 是；229 单元 + 319 集成 = 548 项，失败/错误/跳过均为 0；另含 27 组 Chromium 页面验收，浏览器错误为 0 |
| 测试环境已发布 | 否 |
| 真机测试通过 | 未验证 |
| 生产环境已发布 | 否 |

已验证：上表现有页面的菜单、内容、搜索与表单保存回显、返回、登录退出、权限异常；mock 外呼、AI 与转人工回归；MySQL/Redis、租户与鉴权边界；退出审计真实入库；API 文档的包可见方法正常代理并实际输出请求日志；媒体网关在控制器代理上可通过方法正确取得；独立 Jar 启动及业务冒烟。

新增 7 项切面测试验证成功/异常耗时日志、原异常原样抛出、审计故障隔离、匿名身份处理、无 HTTP 上下文、零参数及包可见映射、ResponseEntity 状态，以及不调用业务对象的 `toString()`。本次集成运行共生成 642 条结构化请求日志，抽检预置密码、令牌及异常文本标记均未进入这些请求日志；此项仅验证测试夹具，不扩大为全环境数据审计结论。

未验证：真机、真实语音与云模型、目标 Linux、测试/生产发布、真实容量和长稳；继承的日志滚动及保留阈值未通过实际时间/体积触发复验。已知问题：未发现本次新增功能回归；现有 Shiro/Spring BeanPostProcessor 生命周期 WARN 和 Jackson 2 兼容模块仍保留；下述日志覆盖和故障审计限制仍适用。

本机原始证据位于 `verification/log-aspects-20261022/`（Git 忽略）；机器摘要为 [log-aspects-validation.json](log-aspects-validation.json)。可执行包 `target/outbound-mono.jar` 的 SHA-256 为 `79c07ddb2ae11701ad4f4ed88806678a6e6207ca5442983b907a31a4b4426884`。

## 已知边界、环境限制和上线前置条件

- IP 使用 `getRemoteAddr()`，不直接信任客户端自报的 `X-Forwarded-For`；反向代理场景中该值可能是代理地址。上线需确认可信代理配置。
- 请求切面记录控制器方法执行，不覆盖控制器执行前的认证拦截、参数校验失败、静态资源、WebSocket 帧，以及流式响应后续写出时间。普通异常最终 HTTP 状态由现有异常处理器决定，切面未能确定时记录 `status=null`；不将控制器耗时当作完整网络耗时。
- 匿名请求和无请求上下文的 `@Log` 调用不写租户审计，避免虚构用户或跨租户归属；登录请求仍有结构化请求日志。
- 审计同步保存；保存失败输出 `operation_log_failed`，不替换业务返回值或原异常。该策略不承诺故障期间审计必达；无异步线程池、队列或重试。
- 本地验证使用 macOS ARM64、隔离 MySQL/Redis 容器和 Chromium、mock 语音/AI 链路。真实 PBX/SIP/RTP、ASR/LLM/TTS、录音与真机、目标 Linux、容量和长稳均需独立验收。
- 日志将增加文件写入及同步审计开销，本轮不开展容量压测，不据此证明 500 CPS。正式上线前完成测试环境发布、日志脱敏与可信代理核查、真实链路/容量/长稳、备份恢复和单实例升级回滚验收。
