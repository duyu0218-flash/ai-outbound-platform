# 核心后端 Java 迁移

基线：`main` 的 `96d9bef44173612d85399792bf54c6399059b1e7`。
技术栈：Spring Boot **2.7.18**、**JDK 1.8**、Maven。
前端沿用 React；AI Agent、语音网关和录音适配服务保留 Python。

当前是开发中的部分迁移，尚不能替换完整 Python 核心后端。未迁移接口不会转发到 Python。生产模式暂时禁止启动，`/readyz` 返回 503，避免将部分实现误认为已完成迁移。

## 构建与测试

安装 JDK 1.8 和 Maven 3.9，设置 `JAVA_HOME`。以下命令在仓库根目录执行：

```sh
export JAVA_HOME=/path/to/jdk8
bash backend-java/scripts/build-java8.sh clean verify
```

脚本会拒绝使用其他 Java 主版本。默认集成测试使用独立内存 SQLite。也可使用专用、空白的 PostgreSQL 测试库：

```sh
export JAVA_TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/java_core_test
export DATABASE_USER=java_test
export DATABASE_PASSWORD='<test-database-password>'
bash backend-java/scripts/build-java8.sh test
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

已实现的接口范围：登录/退出/身份/在线状态、客户基本管理与批量 DNC、话术模板、知识库、管理员账户、线路配置、管理设置、概览查询、前端静态入口；另已迁移任务 CRUD、预检、待拨记录准备、暂停与停止，以及通话/事件/转写/录音/指标查询。测试范围见验收记录，接口实现不等于所有页面或业务已验收。

任务准备使用 `POST /api/v1/campaigns/{id}/start?auto_dial=false`。准备操作生成持久化的 `created` 通话记录，不实际拨号。实际启动和恢复暂时返回 503，并保持任务状态不变，直到 Java 执行器完成迁移。SQLite 开发环境使用单连接串行事务；PostgreSQL 使用行锁，已测试多连接同时准备任务不会重复建单。

待迁移：客户导入导出、通话执行与重试、Webhook/Inbox/Outbox、任务调度、流程图、WebRTC、结果分析与报表、外部服务健康探测及其他管理接口。新建库结构从 Python SQLModel 导出；原有库的运行时增量迁移尚未逐项验证。

接口清单位于 `src/main/resources/contracts/migration-inventory.json`。`mapped` 仅表示已注册 Java 路由，`partial` 表示功能受限，`pending` 表示尚未迁移；这些状态不等于验收结论。新增控制器后执行 `python3 backend-java/scripts/sync_inventory.py` 更新清单。

`scripts/export_contract.py` 从基线 Python 代码导出 OpenAPI、表结构及配置默认值，保存在 `src/main/resources/contracts`。该导出工具需要原后端 Python 依赖，但 Java 服务运行不需要 Python 解释器。
