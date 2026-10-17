# 核心后端 Java 迁移

基线：`main` 的 `96d9bef44173612d85399792bf54c6399059b1e7`。
技术栈：Spring Boot **4.1.1**、**JDK 17**、Maven。
前端沿用 React。核心迁移阶段保留的 AI Agent、语音网关和录音适配服务现已有 `services-java` 独立 Java 实现；全 Java 配置入口与本轮验证见 [辅助服务说明](../services-java/README.md)及[验收记录](../services-java/ACCEPTANCE.md)。历史 Python 联调结果仅作基线。

核心后端的接口、后台执行流程、旧库迁移和运行入口已实现 Java 版本，未通过转发 Python 核心接口代替迁移。开发环境验收见 [ACCEPTANCE.md](ACCEPTANCE.md)；生产替换尚未验收。`JAVA_RELEASE_ACCEPTED` 默认关闭，只有完成独立发布验收后才可开启就绪门槛；生产配置仍须通过完整检查。

## 构建与测试

安装 JDK 17 和 Maven 3.9，设置 `JAVA_HOME`。以下命令在仓库根目录执行：

```sh
export JAVA_HOME=/path/to/jdk17
bash backend-java/scripts/build-java17.sh clean verify
```

脚本会拒绝使用其他 Java 主版本。默认集成测试使用独立内存 SQLite。也可使用专用、空白的 PostgreSQL 测试库：

```sh
export JAVA_TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/java_core_test
export DATABASE_USER=java_test
export DATABASE_PASSWORD='<test-database-password>'
bash backend-java/scripts/build-java17.sh test
```

测试会创建完整表结构并插入测试数据；不要连接业务数据库。重复运行 PostgreSQL 测试前须重建专用测试库。

## 本地运行

先按原项目流程构建前端到 `backend/app/static`。在 `backend-java` 目录内：

```sh
mkdir -p .local
export JWT_SECRET='<at-least-32-character-local-development-secret>'
export JAVA_DATABASE_URL=jdbc:sqlite:./.local/control.db
export JAVA_INITIALIZE_SCHEMA=true
export DEMO_USERS_ENABLED=true
"$JAVA_HOME/bin/java" -jar target/outbound-control-0.1.0-SNAPSHOT.jar
```

默认监听 `127.0.0.1:18000`，入口 `/admin/login`、`/agent/login`。初始化参数仅用于新建空白数据库；后续启动应设置 `JAVA_INITIALIZE_SCHEMA=false`。演示账户仅在显式启用时创建，默认管理员 `admin`、座席 `1001@test`，密码均为 `12345678`。

## 当前迁移范围

108 个基线接口均有 Java 实现（108 mapped、0 partial、0 pending）。271 个双后端行为对照用例通过，其中 98 项比较成功响应内容，其余包含权限、参数和状态边界；这不代表每个业务组合都已穷举。

已实现：账户/权限/租户隔离、客户与 CSV 导入导出、任务准备/启动/暂停/恢复/停止、拨号尝试及重试、Webhook 持久化收件箱、Outbox 执行器、话术版本与流程、对话策略/预约/待办、AI Agent 协调、人工接管、录音适配和短信任务、分析质检、报表分组与时长账单、浏览器坐席媒体状态及 SIP 凭证。

执行器默认关闭。仅在隔离开发环境显式配置以下参数启用模拟外呼：

```sh
export TELEPHONY_PROVIDER=mock
export JAVA_DISPATCH_ENABLED=true
export JAVA_WORKERS_ENABLED=true
# 可选：启用持久化回调收件箱和批量接收。
export CALLBACK_INBOX_ENABLED=true
```

任务准备使用 `POST /api/v1/campaigns/{id}/start?auto_dial=false`；默认启动按钮执行实际调度，未开启 `JAVA_DISPATCH_ENABLED` 时返回 503。开启后可通过 mock 网关在 Java 内完成拨号、状态回调、结果分析与报表闭环。AI Agent、语音网关、录音存储使用配置的 HTTP 服务接口；全 Java 方案将其指向 `services-java` 对应角色。HTTP 模拟不等于真实线路验收。

执行器支持任务租约、重试与旧轮次/旧回复失效。录音删除只有适配器确认后才清除存储位置。定时核对只在 PBX 明确确认终止后结束超时通话，未知结果继续占用容量。SQLite 使用单连接串行事务；正式并发能力需要 PostgreSQL 和目标环境另行测量。

新增后台能力：网关节点授权、容量/CPS 准入及健康探测；Redis 共享压力信号和降速、暂停、恢复控制；数据库、Redis、AI、电话服务探针；回调消费者心跳及过期回执清理；文本脱敏、录音到期删除和已完成任务压缩为幂等凭据。拨号、AI、通话控制、外部集成分别使用独立执行通道，回调消费单独运行。拨号会固定本轮策略快照并发送给配置的网关。

旧库迁移已支持原 21 个 PostgreSQL 版本与 49 条运行时补列语句，另有 Java 兼容性版本；支持只读计划、历史校验和、重复执行、完整性冲突阻断、单版本事务和备份恢复演练。操作方法见 [MIGRATION.md](MIGRATION.md)。Java 启动只校验已有库，不自动修改旧表。

运行入口包括 `/healthz` 存活、`/health` 数据库/Redis、`/readyz` 初始化/依赖/回调消费者/发布验收门槛，以及令牌保护的 `/metrics`、`/metrics/runtime`。运行时指标使用 JVM 指标，不沿用 Python 线程池指标。HTTP 主机名/CORS、进程内限流和并发上限、流式 JWT 到期关闭已实现；限流不代表分布式容量承诺。

仍待正式环境验收：真实历史数据库副本、目标 Linux 容器、SIP/RTP/WebRTC、云模型、短信和对象存储渠道、500 总在途及长稳负载、跨进程崩溃接管。实际 Python 规则 Agent 与 mock 驱动语音网关已和 Java 实进程联调通过；不代表真实线路通过。短信重试使用稳定幂等键，但渠道必须支持幂等，不能据此保证未知结果下绝不重复发送。

运行 Redis 集成测试时设置 `JAVA_TEST_REDIS_URL` 指向专用测试实例；未设置时该项会跳过，不能记为通过。生产准入使用 `REDIS_URL`，共享控制失败时暂停新拨号，不使用进程内缓存放行。

接口清单位于 `src/main/resources/contracts/migration-inventory.json`。`mapped` 仅表示已注册 Java 路由，`partial` 表示功能受限，`pending` 表示尚未迁移；这些状态不等于验收结论。新增控制器后执行 `python3 backend-java/scripts/sync_inventory.py` 更新清单。

`scripts/export_contract.py` 从基线 Python 代码导出 OpenAPI、表结构及配置默认值，保存在 `src/main/resources/contracts`。该导出工具需要原后端 Python 依赖，但 Java 服务运行不需要 Python 解释器。

## 回归与部署入口

在仓库根目录，用安装了原后端开发依赖的 Python 执行：

```sh
python backend-java/scripts/compare_behavior.py
# 实际 Python 服务进程联调；仅规则模型、mock 网关和临时数据库。
PYTHON_AGENT=/path/to/agent-venv/bin/python PYTHON_VOICE=/path/to/voice-venv/bin/python \
  python backend-java/scripts/retained_services_smoke.py
```

脚本要求 `JAVA_HOME` 和已打包的 Java 制品，自动使用临时库与 loopback 端口，报告写入 `validation/`。行为对照归一化生成时间/令牌/UUID；固定历史时间保留比较，错误信息只比较状态边界。GitHub 配置新增 `java-core.yml`，覆盖 JDK 17 的 SQLite/PostgreSQL/Redis 测试；本机通过不等于远端 CI 通过。

全 Java 启动需再叠加 `docker-compose.java-services.yml`；仅使用核心覆盖文件仍保留 Python 辅助进程。`backend-java/Dockerfile` 构建 Java 核心及前端；`docker-compose.java.yml` 覆盖原 Compose 的 `control-api`，保留其他 Python 服务，将旧 `task-worker` 置于 `legacy-python-core` profile。切换前必须先停止旧核心及其执行器，完成备份、离线迁移和回滚验证；不能让两个实现同时消费任务。Compose 配置可单独校验；`java-image` CI 负责 Linux 镜像构建及一次性容器烟测，运行结果以对应提交为准。目标环境发布仍需单独验收。

```sh
docker compose -f docker-compose.yml -f docker-compose.java.yml config --quiet
docker compose -f docker-compose.yml -f docker-compose.java.yml build control-api
```

生产使用 PostgreSQL，配置 JDBC 的 `JAVA_DATABASE_URL`、`DATABASE_USER`、`DATABASE_PASSWORD`，保留原 JWT/服务签名密钥以兼容已有账号及回调；关闭演示账户、自动迁移及 inline 任务。`TRUSTED_HOSTS` 须包含业务域名和容器健康探针使用的 `127.0.0.1`。完整发布前置条件见验收记录。

Java 镜像预置 SQLite 本地库以兼容只读文件系统及 noexec 临时目录。自定义 `JAVA_TOOL_OPTIONS` 时需保留 `-Dorg.sqlite.lib.path=/app/native`；生产数据库仍使用 PostgreSQL。

## 安全检查

用户已批准升级到 JDK 17，JDK 8 不再受支持。当前使用 Spring Boot 4.1.1 / Spring 7.0.9 / Jackson 3.1.7 / Tomcat 11.0.27；前端保持 React；辅助服务 Java 迁移另见 `services-java`。执行 `bash backend-java/scripts/security-check.sh` 生成全依赖 SBOM、OSV 漏洞报告和 FindSecBugs 静态分析。扫描失败、依赖公告、任何未复核提示（含低优先级）、新路由或运行源码/资源变化均阻断 CI；原始报告作为 artifact 保存。范围与部署约束见 [安全检查报告](SECURITY-REVIEW.md)。

## 本轮安全加固的配置兼容要求

- Java 核心使用 `ENV=prod` / `production` 时，`REDIS_URL` 必须为 `rediss://`，并由 JVM 信任服务证书和校验原始主机名。明文 `redis://` 仅供非生产开发/测试；共享配置有明文 Redis 的部署必须先提供 TLS 端点，不能直接套用生产配置启动。
- `CORS_ALLOW_ORIGINS` 必须逐项填写完整 HTTP(S) origin，不再支持 `*`。跨域客户端使用显式 Authorization / API Key；服务不授予跨域浏览器 Cookie 凭据。仓库前端使用显式 Bearer，不依赖跨域 Cookie。
- 线路 `credential_ref` 只接受 1—128 位 ASCII 字母、数字和下划线，不再将标点自动映射为其他凭据名称。历史非法引用需人工对照原凭据配置修正。
- 指标令牌文件最多 4096 字节，网关配置最多 1 MiB；拒绝设备/FIFO/目录，支持合法 Secret 挂载符号链接。
- 安全请求头必须为有界 ASCII 单值，出站头禁止换行及 Host/Content-Length/Transfer-Encoding/Connection 覆盖。自然语言、客户姓名和话术内容保持 Unicode 处理。

当前 149 条原始提示全部保留：37 条安全相关观察逐项复核，112 条路由信息登记访问控制；未复核项为 0，不代表原始扫描零提示。维护复核时必须先检查源码/调用方/资源和回归证据，再更新 `security/reviewed-findings.json`、`entrypoints.json` 和全范围 `review-scope.json`；不得仅重算摘要以放行。见安全报告和验收记录。
