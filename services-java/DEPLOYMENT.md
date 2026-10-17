> 当前工具链及命令以仓库 `docs/migration/no-python-toolchain.md` 为准。下列既有验收数字保留为历史证据，不代表本轮重验；旧 Python 源码与对照启动模板已从当前树退役。

# Java 部署与切换

业务运行时统一 JDK 17 / Spring Boot 4.1.1。`backend-java/Dockerfile` 构建核心镜像，`services-java/Dockerfile` 构建一个供 Agent、录音、网关、媒体四角色共用的镜像。生产固定制品摘要；React/NGINX、PostgreSQL、Redis、对象存储及 FreeSWITCH 是独立组件。

## 配置入口

| 模板 | Java API/执行器 | 网络与媒体 | 当前验证 |
| --- | --- | --- | --- |
| `docker-compose.yml` | 核心 API 内启执行器；Agent/录音/网关分别运行 | 桥接；网关内置媒体 | 配置解析及独立四角色容器；未整体发布 |
| `docker-compose.compact.yml` | 每台 4 API + 3 个统一 Java worker；4 台共享数据库/Redis | Linux host；每台 4 个媒体进程 | 命令/端口/挂载解析；未四机部署 |
| `docker-compose.single-host-500.yml` | 6 API + 11 个统一 Java worker + 2 Agent | Linux host；12 个媒体进程、单个网关 | 静态资源预算及回归；未证明 Java 500 路容量 |

`docker-compose.java.yml` 和 `docker-compose.java-services.yml` 是空的兼容覆盖文件，默认模板已完成其原有覆盖。旧 Python 配置和启动入口已从当前树删除；仅在 Git 基线 `8cb4c30` 和保留的历史制品中追溯。受控回退须单独恢复旧制品与数据，不在新工具链安装旧依赖。

## 操作顺序

1. 固定版本、数据库和持久卷目标。停止新任务，排空通话、回调和未决外部动作，停止全部旧 Python 写入进程；不能双写同一账本/队列。备份数据库、SQLite WAL/SHM、模型额度、网关账本、录音生命周期、S3 对象及 PBX 源文件，验证恢复。
2. 在停写副本上按 [MIGRATION.md](../backend-java/MIGRATION.md) 执行只读 plan → apply → verify → 数据/接口对照 → 回退演练。运行时保持 `JAVA_INITIALIZE_SCHEMA=false`、`AUTO_MIGRATE=false`；迁移使用独立维护账号，不能复用应用权限自动升级。
3. `backend.env` 改用 `JAVA_DATABASE_URL=jdbc:postgresql://...`、`DATABASE_USER`、`DATABASE_PASSWORD`。SQLAlchemy `DATABASE_URL*` 不会替代 JDBC。复制本次示例到仓库外，并合并必要 JWT、CORS、可信主机、签名、令牌和出站允许列表。
4. 生产 Redis 必须是 `rediss://`，主机名与证书 SAN 匹配。compact 使用托管 Redis。单机示例 `redis.conf.example` 关闭明文端口、开启 6379 TLS，挂载 `SINGLE500_REDIS_TLS_DIR` 内的 `redis.crt`、`redis.key`、`ca.crt`，证书 SAN 包含 `localhost`，密码取 `redis.env`。Java 挂载 `JAVA_TRUSTSTORE_FILE` 的 PKCS12 信任库，`JAVA_TOOL_OPTIONS` 指向 `/etc/java/redis-truststore.p12`；同一 JVM 调用公有 HTTPS 时，信任库还需保留所需公有 CA。Compose 不生成证书或批准凭据；现场 TLS 握手尚未验证。
5. 先准备可写持久卷。辅助镜像 UID/GID 为 `10001:10001`；PBX 和网关录音目录需匹配文件创建/删除权限，compact 已改为可写挂载。禁止通过新建空卷绕过旧状态升级或额度冻结。媒体只监听 loopback；不能把其地址套到独立桥接容器。
6. 只读解析配置并运行 `verify_compose.py`；单机额外运行 `check-single-host-500.py`。渲染输出会包含秘密，只能保存在权限受限目录，不提交 GitHub。启动基础设施及媒体/录音/Agent/网关，再启动统一 worker，最后启动 API 和入口代理；部署模板不替代真实顺序与资格验证。
7. Java worker 每进程实际处理四条租约任务通道和 Inbox；历史 callback/AI 服务名保留以方便资源编排，不再表示 Python shard。compact 默认要求共享集群 12 个 worker，单机要求 11 个。Java 自行上报实际 AI 通道调度等待和待执行任务最老等待；停止/停滞会使信号过期。API 不执行队列；只有一个配置了 `JAVA_DISPATCH_ENABLED=true` 的调度进程。全局准入从暂停开始，健康持续满足恢复窗口后才逐步恢复，不通过伪造健康信号或关闭门禁抢先放行。
8. 完成目标镜像/UID、旧库与卷、真实 PBX/ASR/TTS/模型/短信/S3/TLS、人工转接、崩溃恢复和性能对照，再开启 `JAVA_RELEASE_ACCEPTED` 并安排受控切换。该标记默认 false，不代表已经验收。发生问题停止 Java 写入，按已演练方案恢复备份并核对切换后的新增数据。

## 容量边界

Java 单机静态预算为 64 个应用 DB 连接、31.5 CPU 限额和 54.25 GiB 内存限额；均非资源预留或实测容量。11 个 Java worker 各有一条 AI 执行通道，不能沿用旧 Python 640 异步 AI 槽位的预检结果。以 500 总在途为目标仍需同条件旧/新版本对照、持续输入积压、尾延迟、追平时限和长稳测试。配置上限、合成音频或单进程测试均不证明商用容量。

测试环境、真机和生产均未发布。本机临时进程/容器通过仅表示已记录的隔离开发链路通过。
