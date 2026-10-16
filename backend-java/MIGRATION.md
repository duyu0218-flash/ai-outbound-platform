# 旧数据库迁移与回退

当前 Java 制品要求 JDK 17，旧 JDK 8 无法运行；执行迁移前先设置对应的 `JAVA_HOME`。数据库格式与原版本迁移历史保持兼容。

Java 使用原 SQLModel 表名、枚举、主键和关联，不将旧数据搬到新的一套业务表。迁移命令在独立维护进程运行，不启动 HTTP、调度或演示账户。应先在停写的真实备份副本演练，再安排切换窗口。

## 已实现及验证边界

- 原 21 个 PostgreSQL SQL 文件按原始字节打包，保留原文件名与 SHA-256，复用 `schema_migration_history`。默认咨询锁与 Python 的 `ai-outbound-schema-migrations` 一致，可用 `DATABASE_MIGRATION_LOCK_NAME` 配置。
- `java_20261016_additive_compatibility` 补齐 49 条原运行时语句涉及的字段、缺失表/索引/唯一约束、PostgreSQL 枚举及收件箱分区。SQLite 使用兼容版本，不执行 PostgreSQL SQL。
- 执行前检查历史校验和与重复唯一键，不自动删除冲突行。重复执行不重复写入迁移历史。
- 启动/verify 检查表、列、枚举和唯一约束；不是所有自定义类型、外键、触发器或索引定义的完整审计。未知的业务定制必须单独核查。
- SQLite 与 PostgreSQL 合成历史夹具验证了账号字段、同意/DNC 状态、通话标识、转写、录音地址和关联保留。PostgreSQL 实际做了 `pg_dump/pg_restore`，8 类业务表的行数和内容摘要一致。
- 未读取真实业务数据库。本记录不能证明任意历史版本或现场定制库都可直接切换。

## 操作顺序

1. 暂停新任务，排空通话与回调，停止原 Python 核心、worker、调度及其他数据库写入。迁移锁只协调迁移进程，不阻止业务写入；SQLite 也要求独占维护窗口。
2. 记录版本、数据库连接目标、表行数、未完成任务状态及密钥配置。备份数据库及必要的对象存储引用，验证备份可以恢复到另一个隔离库。SQLite 连接关闭后复制文件；有 WAL 写入时不能只复制主文件。
3. 对副本执行 plan → apply → verify，比较历史数据和业务接口，重启验证；故障时恢复副本。确认后按相同步骤处理切换目标。

以下命令从仓库根目录执行，目标必须由操作者显式设置：

```sh
export JAVA_DATABASE_URL='jdbc:postgresql://database-host:5432/database-name'
export DATABASE_USER='<migration-user>'
export DATABASE_PASSWORD='<migration-password>'
"$JAVA_HOME/bin/java" -jar backend-java/target/outbound-control-0.1.0-SNAPSHOT.jar --database-migration=plan
"$JAVA_HOME/bin/java" -jar backend-java/target/outbound-control-0.1.0-SNAPSHOT.jar --database-migration=apply
"$JAVA_HOME/bin/java" -jar backend-java/target/outbound-control-0.1.0-SNAPSHOT.jar --database-migration=verify
```

SQLite 使用 `jdbc:sqlite:/absolute/path/backup-copy.db`。维护命令拒绝隐式目标及不存在的 SQLite 文件，避免拼错路径后误建空库。

`plan` 返回待执行版本、结构缺口和完整性冲突，不写入数据库。历史校验和不匹配会直接失败。`apply` 先提交缺失基础表，再逐版本提交；每个失败版本回滚其 DDL/历史写入，之前已提交的版本仍保留。**这不是整个升级的一次性事务，也没有自动 downgrade。** 最后执行兼容性检查，重复 apply 应返回空的 applied 列表。

4. 以 `JAVA_INITIALIZE_SCHEMA=false` 启动 Java，保留 JWT 和服务鉴权配置，检查历史登录、客户、话术、任务、回调、报表及账单。启用 Java 执行器前确认旧 worker 已停止。测试通过才开启发布验收标记并切换流量。
5. 若须回退，停止 Java 和所有写入，恢复切换前备份，再启动原制品。切换后产生的新数据须另行导出/核对，不能直接覆盖丢弃。不要手工删除迁移历史来强迫重跑。

## 可复现实验

`DatabaseMigrationTest` 覆盖只读计划、缺字段/缺表、历史保留、原 Python 版本历史复用、重复执行、校验和篡改、重复号码冲突、失败版本回滚、SQLite 备份恢复。通过 `JAVA_TEST_DATABASE_URL` 启用专用 PostgreSQL 后，同样验证其数据库语义。

`scripts/postgres_migration_rehearsal.py` 仅面向本机固定的隔离 Docker 测试容器 `java-core-postgres-20261015` 和合成库 `java_core`，自动建/删临时副本，执行只读 plan、22 个版本 apply、verify、重复 apply 和备份恢复。不要改为连接业务库。实验报告为 `validation/postgres-migration-rehearsal.json`。
