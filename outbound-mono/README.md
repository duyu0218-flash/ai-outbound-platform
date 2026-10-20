# AI 外呼单体工程

API、AI、FreeSWITCH ESL、录音和 Java 实时媒体运行于一个 JDK 17 进程；MySQL、Redis 是数据库/队列基础设施，FreeSWITCH 与对象存储是外部语音/录音设施。

> **本系统事务串行化依赖 JVM 内锁，仅支持单实例部署。需要使用多实例时，必须先把 PlatformLock 替换为分布式实现并重做并发验证。**
>
> 升级必须先停止旧进程并确认退出，再启动新进程；禁止新旧进程重叠或滚动升级。

## 构建与复验

锁定 JDK 17.0.20.1、Maven 3.9.11，验收使用 Node 24.19.0、pnpm 11.25.0；Docker Desktop 用于真实 MySQL/Redis Testcontainers，浏览器使用 Playwright Chromium。核心组件版本保持已批准矩阵。

```sh
cd outbound-mono
pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend exec playwright install chromium
mvn -B verify
mvn -B enforcer:enforce
```

Maven generate-resources 构建 Vue3 + Element Plus 到 `src/main/resources/static/`，再打入 `target/outbound-mono.jar`。可用 NODE_BINARY / PNPM_BINARY 指向现有运行时；JAVA_HOME 指向 JDK 17。验收实际使用 `../.tools` 的 JDK/Maven，附加 `-Dmaven.repo.local=./target/maven-repository`。运行 jar 无需 Node。

## 本机 mock 启动

复制 `.env.example` 到 `.env`，填写数据库密码、JWT_SECRET 等并自行导出；Java 不自动加载 `.env`。不要提交 `.env` 或 `data/`。JWT_SECRET 至少 32 字符。环境样例不含可用生产凭据。

```sh
docker compose --env-file .env -f docker-compose.dev.yml up -d
mkdir -p data/recording-spool data/source-recordings
# 当前 shell 导出配置后启动唯一应用进程：
java -XX:+UseG1GC -XX:MaxGCPauseMillis=20 -jar target/outbound-mono.jar
```

访问 `/admin/login` 或 `/agent/login`。演示账号默认关闭，生产开启会拒绝启动。`JAVA_DISPATCH_ENABLED` / `JAVA_WORKERS_ENABLED` 保持原默认 false，启动自动 mock 流程需明确开启；完整 HTTP 自动化用持久任务逐步推进，不依赖后台时间等待。

`GET /healthz` 是 liveness。`/health`、`/readyz` 还检查依赖和原 `JAVA_RELEASE_ACCEPTED` 发布验收开关。200 探针不等于真实线路或媒体验收。

`TELEPHONY_PROVIDER=mock` 不连接 PBX；`VOICE_GATEWAY_DRIVER=freeswitch_esl` 才连接 FreeSWITCH。原网关端点作为逻辑身份/校验配置，命令在同 JVM 内执行，不另启网关进程。录音回调、PIPECAT_MEDIA_WS_BASE 指向唯一应用进程的可达地址。

## 运营工作台

P0—P2新增话单查询、线索跟进、运营分析、团队权限、表单标签、通知和坐席配置，详见 [功能对照与验收](OPERATIONS-ACCEPTANCE.md) 和 `operations-validation.json`。管理员入口 `/admin/operations/calls`，坐席入口 `/agent/operations/leads`。测试、真机及发布状态分别记录，GitHub源码更新不等于部署。

## 数据与凭证

迁移基线为 **44 张应用表**：原业务 34 张、语音账本 8 张、模型配额 2 张，V4新增10张运营表，当前共54张应用表，另有1张Flyway元数据表。V1 保持原样，V2 新增辅助账本，V3 只增加队列查询所需的3个非唯一索引。所有应用数据库持久化均使用 MySQL；Redis 保留原控制信号/队列用途。对象存储保存录音，spool/源录音是原文件处理暂存。

`voiceLedgerDataSource` 是独立 Druid 连接池，使用 MYSQL_JDBC_URL / MYSQL_USER / MYSQL_PASSWORD 连接同一 MySQL 库；独立事务不加入控制面业务事务。命名写者行 FOR UPDATE 保留原单写者范围。启动强制验证 `innodb_flush_log_at_trx_commit=1`、`sync_binlog=1` 和 REPEATABLE READ，不满足拒绝启动。开发旧账本不做数据迁移；新辅助账本从空开始。原模型配额以“路径已配置”启用窗口，迁为 LLM_QUOTA_ENABLED（默认 false）；原来配置过路径的部署须设 true，样例已映射为 true。

API Key 继续从 TENANT_API_SCOPES_JSON 取得租户权限集合；管理员接口须显式权限码。JWT 保留 sub/uid/tenant_id/role/tv/exp，吊销依赖 token_version。服务令牌的角色映射、签名与录音存储凭证见 `.env.example` 与 MIGRATION-NOTES.md，未引入新的授权来源。

通用 CallLifecycle.status 保持原 rank 单调与终态不可重开；显式重拨、活动重启复用、拨号准入回退和转人工恢复仍按原业务直接写状态。终态且 attempts 未耗尽时前端可重拨，耗尽返回 409。唯一批准的业务行为差异是 API Key 管理员越权修复。

## 验证与交付边界

接口对照108/108、手册功能36/36，不适用均0。最新本地 `mvn -B verify` 为 **140单元+196集成=336项**，失败/错误/跳过0；Chromium **19组**、完整mock业务闭环与fat jar `/healthz`=200通过。入口覆盖登录/退出、表单保存回显、返回/异常、租户和角色边界、后台/坐席查看、终态重拨。没有门店、购物车、支付页面，不新增这些能力。

500总在途、50轮/秒、120秒复测仍失败：计划6000，HTTP实际发送5492次全200；120秒内回复5148，持续 **42.9轮/秒**；排空后5492/6000，回复P95/P99=**10.110/10.496秒**，高于1.5/3秒标准。MySQL死锁计数0、异常401未复现，但积压与延迟尚未修完。不能用功能测试通过认定容量通过，更不能当真实500路线路容量达标。

详细结果、修复范围和未验证项见 [当前验证摘要](VALIDATION.md) 与 [机器摘要](validation-summary.json)。原始日志/数据库/历史压测资料仅保留本地，不随Git上传；旧文档的verification路径指本机历史证据。

源码与GitHub同步不等于部署。测试环境未发布、真机未验证、生产未发布，未替换现有部署。真实FreeSWITCH/SIP/RTP、云ASR/LLM/TTS、录音/设备、长稳和目标Linux仍未验证。媒体专属有界队列、丢帧策略、DirectByteBuffer与G1/20ms目标已实现；真实媒体压测和暂停目标是否达到仍未验证。

正式上线前须按 [部署文档](DEPLOYMENT.md) 完成生产初始化、备份恢复、单实例升级、目标宿主与真实线路/音频/容量验收，再设置发布验收标志。
