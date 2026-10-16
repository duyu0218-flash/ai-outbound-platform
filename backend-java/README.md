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

108 个基线接口均已有 Java 路由（105 mapped、3 partial），但仍处于开发验收阶段，不能据此判定整体迁移完成。

已实现：账户/权限/租户隔离、客户与 CSV 导入导出、任务准备/启动/暂停/恢复/停止、拨号尝试及重试、Webhook 持久化收件箱、Outbox 执行器、话术版本与流程、对话策略/预约/待办、AI Agent 协调、人工接管、录音适配和短信任务、分析质检、报表分组与时长账单、浏览器坐席媒体状态及 SIP 凭证。

执行器默认关闭。仅在隔离开发环境显式配置以下参数启用模拟外呼：

```sh
export TELEPHONY_PROVIDER=mock
export JAVA_DISPATCH_ENABLED=true
export JAVA_WORKERS_ENABLED=true
# 可选：启用持久化回调收件箱和批量接收。
export CALLBACK_INBOX_ENABLED=true
```

任务准备使用 `POST /api/v1/campaigns/{id}/start?auto_dial=false`；默认启动按钮执行实际调度，未开启 `JAVA_DISPATCH_ENABLED` 时返回 503。开启后可通过 mock 网关在 Java 内完成拨号、状态回调、结果分析与报表闭环。AI Agent、语音网关、录音存储继续调用保留的 Python 服务接口；HTTP 模拟不等于真实线路验收。

执行器支持任务租约、重试与旧轮次/旧回复失效。录音删除只有适配器确认后才清除存储位置。定时核对只在 PBX 明确确认终止后结束超时通话，未知结果继续占用容量。SQLite 使用单连接串行事务；正式并发能力需要 PostgreSQL 和目标环境另行测量。

新增后台能力：网关节点授权、容量/CPS 准入及健康探测；Redis 共享压力信号和降速、暂停、恢复控制；数据库、Redis、AI、电话服务探针；回调消费者心跳及过期回执清理；文本脱敏、录音到期删除和已完成任务压缩为幂等凭据。拨号、AI、通话控制、外部集成分别使用独立执行通道，回调消费单独运行。拨号会固定本轮策略快照并发送给 Python 网关。

仍待完成：原库增量迁移与完整行为差异核对、执行通道并发公平性和容量压力验收、崩溃恢复及真实依赖故障演练。WebRTC/SIP、真实线路与云模型完整链路未验收；Redis 共享状态已在隔离实例测试，但生产拓扑未验证。生产启动禁用与 `/readyz` 503 保留。

运行 Redis 集成测试时设置 `JAVA_TEST_REDIS_URL` 指向专用测试实例；未设置时该项会跳过，不能记为通过。生产准入使用 `REDIS_URL`，共享控制失败时暂停新拨号，不使用进程内缓存放行。

接口清单位于 `src/main/resources/contracts/migration-inventory.json`。`mapped` 仅表示已注册 Java 路由，`partial` 表示功能受限，`pending` 表示尚未迁移；这些状态不等于验收结论。新增控制器后执行 `python3 backend-java/scripts/sync_inventory.py` 更新清单。

`scripts/export_contract.py` 从基线 Python 代码导出 OpenAPI、表结构及配置默认值，保存在 `src/main/resources/contracts`。该导出工具需要原后端 Python 依赖，但 Java 服务运行不需要 Python 解释器。
