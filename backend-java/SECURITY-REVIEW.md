# Java 17 与前端安全检查

检查日期：2026-10-16。对象为本次源代码和解析后的 Maven / pnpm 依赖；未扫描或修改生产实例。用户批准升级至 JDK 17，原 JDK 8 限制已解除。

## 修复结果

| 项目 | 修复前 | 当前结果 |
| --- | --- | --- |
| 前端依赖 | 4 条 moderate，涉及 2 个公告 | pnpm audit 全严重级别均为 0 |
| Java 全依赖 | 104 个去重 OSV 公告 | 91/91 个包已扫描，公告数 0 |
| 运行时 | Boot 2.7 / Spring 5.3 / JDK 8 | Boot 4.1.1 / Spring 7.0.9 / JDK 17 |
| 安全分析 | 无 Java 专项门禁 | SBOM、OSV、FindSecBugs 与复核检查进入 CI |

前端修复 [Vitest 公告](https://github.com/advisories/GHSA-82fw-gwwq-j7x9)和 [brace-expansion 公告](https://github.com/advisories/GHSA-q2hr-2g5m-vwhr)：Vitest 与 coverage-v8 4.1.11，brace-expansion 2.1.7 / 5.0.12。业务页面源码未改动；11 项单测、覆盖率门槛和生产构建通过。

Java 曾尝试保留 JDK 8 更新兼容依赖，公告从 104 降到 28，剩余均为旧 Spring 系列。获准升级后迁移至 Jakarta 和 Jackson 3。Boot 4.1.1 默认依赖另命中 10 个公告，因此进一步固定 Jackson 3.1.7、Tomcat 11.0.27。最终 OSV 扫描没有忽略任何依赖公告。版本适配依据 [Spring 官方迁移指南](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)，修复范围依据 [Tomcat 官方安全公告](https://tomcat.apache.org/security-11.html)及扫描报告的 affected/fixed 版本。

源代码加固：回调摘要改为常量时间比较；共享 HTTP 传输拒绝非 HTTP(S)、凭据、片段及带查询的服务地址；CORS Origin 在进入响应头前必须通过 URI 格式及配置白名单验证。内部服务地址仍允许私网，因为 Agent、网关和录音服务依赖私网通信；用户可配置业务回调走独立的 DNS/IP 固定传输。

## 专项验证和静态分析

新增 7 项 Java 安全回归：YAML 对象构造拒绝、JSON 过深嵌套拒绝、日志绑定、恶意 Origin、非 HTTP/凭据 URL、SQL 标识符白名单及参数绑定、FreeSWITCH XML 转义。它们与已有 JWT、退出撤销、租户隔离、回调签名/重放、生产配置、DNS 地址过滤及限流测试一起，在 SQLite 和 PostgreSQL 各执行 89 项，全通过且无跳过。不是攻击组合穷举或渗透测试。

工具固定为 CycloneDX Maven 2.9.3、OSV Scanner 2.7.0、SpotBugs Maven 4.10.5.0、FindSecBugs 1.14.0。OSV 可执行文件下载后校验官方 SHA-256。SBOM 包含运行和测试依赖；不含 JDK/操作系统镜像的完整供应链扫描。

FindSecBugs 实际分析 80/80 个应用 class，无分析错误或缺失类。原始结果 171 条：高优先级 0，中优先级 28，低优先级 143。**这不是 171 个已确认漏洞，也不声明静态分析零提示。** 28 条高/中优先级提示逐项记录在 `security/reviewed-findings.json`，保存检测器/方法/源码位置指纹、出现次数、源文件 SHA-256、判断依据和验证证据。

| 提示类型 | 复核结果与边界 |
| --- | --- |
| SQL 动态语句 | 标识符来自固定代码或打包 schema，数据值绑定参数；Store 的内部 where 参数不得接收 HTTP 原文 |
| 文件路径 | 网关配置、指标令牌、前端目录来自部署配置；页面 URL 不拼接到文件路径 |
| HTTP 头、CORS、响应正文 | 请求 ID 限制 ASCII；Origin 格式/白名单验证；生产拒绝通配符；错误正文为固定 JSON 文本 |
| SSRF | 健康检查指向 loopback；服务地址由平台或独立审批管理；业务回调单独做地址过滤和固定连接；不允许重定向 |
| SHA-1 | 仅 UUID v5 的历史业务标识兼容，不用于密码、签名或权限判断 |
| Socket 明文提示 | HTTPS 回调先包装 TLS 再发送正文；Redis 支持 rediss 且校验主机名。redis 明文模式是保留的部署约束，必须限制在可信隔离网络 |

低优先级 143 条完整保留：112 个 Spring 路由入口提示、10 个 Servlet 输入提示、17 个 Unicode 提示、4 个迁移 SQL 提示。低优先级不作为本轮自动阻断阈值，不能把报告解释为所有低优先级组合已经安全穷举。后续新增高/中提示、指纹次数变化或已复核源码变化会使门禁失败，需重新审查。

## CI 和证据

`java-security` 执行 `scripts/security-check.sh`。任何依赖漏洞、包扫描覆盖不全、静态分析缺类/错误、未复核高/中提示或过期复核都会失败；网络或解析失败不能变成绿灯。9 项门禁测试实际验证漏洞、遗漏包、遗漏 class、未知提示、源代码变更、数量变化、过期复核和坏报告的阻断行为，并验证跨平台污点来源排序变化不会影响同一源码位置的复核。

CI 无论成功失败都会上传 `java-dependencies.cdx.json`、`osv.json`、`spotbugsXml.xml`、`security-summary.json`。前端审计门槛从 high 收紧到 low。双库 JDK 17 测试和 Java 容器烟测为独立检查。当前本地摘要见 `validation/java-security.json`、`validation/frontend-security.json`、双库测试和行为对照 JSON；远端是否通过必须以对应提交的运行记录为准。

本次页面/入口/状态清单、实际浏览器与数据迁移验证、六项交付状态见 [ACCEPTANCE.md](ACCEPTANCE.md)。公开依赖公告为检查时点结果，需要后续 CI 持续复扫。

## 未验证和正式上线条件

没有进行生产渗透测试、JDK/基础镜像漏洞扫描或真实网络 TLS 验收。测试环境、真机、真实线路及生产均未发布验收；真实旧库、录音对象存储、云模型、短信、容量和故障恢复仍按验收记录执行。跨信任边界的 Redis 必须使用经过验证的 TLS 或加密网络，不能以当前本机 Redis 测试代替。完成这些验收、停止旧 worker 并确认无双重消费后，才能单独批准生产切换。
