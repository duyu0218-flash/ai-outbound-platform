# 无 Python 开发、测试、部署与运维

本轮将活动工具链统一为 JDK 17、Maven 3.9.11、Node.js 22.13+（CI 使用 22 系列）和 pnpm 11.19.0。业务服务仍是 Spring Boot 4.1.1。Node 用于既有前端及测试/运维驱动，不把业务后端改回另一种语言。数据库、Redis、FreeSWITCH、SIPp、对象存储保留原生组件。

## 文件去向

逐文件清单见 [python-retirement-inventory.json](python-retirement-inventory.json)，包含合并基线 8cb4c30、196 个旧 Python 文件的 SHA-256、分类和替代位置。`delete` 表示退役；`reuse` 表示删除重复旧实现，复用现有 Java/JUnit/TypeScript 和冻结夹具；`rewrite` 表示活动能力由新工具承接。分类不是验收结论，旧测试没有宣称逐行等价移植。

旧源码、旧镜像定义、旧依赖清单和 Python 对照 Compose 从当前树移除，可在 Git 基线 `8cb4c30` 恢复。版本化 SQL、旧库历史校验和、HTTP 合约、520 个策略样例、音频向量和历史验收证据保留；不再要求安装旧 SDK 来重新导出它们。历史文档中的旧命令只供追溯，当前操作使用本页。

## 入口与状态清单

| 入口 | 当前工具/复用 | 验证目标 |
|---|---|---|
| 开发构建 | 两模块 `scripts/build-java17.sh`；`frontend` pnpm | JDK 17 编译；前端构建/单测 |
| 安全门禁 | `tools/security-check.mjs`、`install-osv.mjs` | SBOM/OSV 覆盖、完整类分析、逐项告警及源码绑定；原始报告保留 |
| 合约/配置 | `verify-contracts.mjs`、`verify-compose.mjs`、`check-toolchain.mjs` | 冻结路由、迁移 SQL 摘要、四套配置、端口/卷/配额/版本 |
| 业务链路 | `stack-smoke.mjs`、JUnit、Playwright | 登录/退出/异常、菜单/返回、表单保存回显、任务/拨号/回调/话单/分析/报表 |
| 媒体进程 | `media-process-smoke.mjs`、Java 媒体测试 | 鉴权/代次/会话/尝试围栏、幂等关闭、真实强杀重启 |
| Linux 容器 | `container-smoke.mjs --role core\|services` | 非 root/只读/noexec、S3、录音清理、账本重建、无解释器 |
| 旧库维护 | Java 制品 `--database-migration=plan\|apply\|verify`；`migration-rehearsal.mjs` | 停写副本/校验和/幂等/失败回滚；原生 pg_dump/pg_restore |
| 容量/节点运维 | `capacity-preflight.mjs`、`fleet.mjs`、`db-snapshot.mjs` | 明确准入预算、单节点排空、只读 PostgreSQL 采样，不打印凭据 |
| 负载/长稳 | `load.mjs`、`soak.mjs`、`stability-evaluate.mjs` | 有界到达率、遗漏/过载计数、延迟和原始输出、仅清理自有进程 |
| ASR 离线评测 | `evaluate-asr.mjs` | 授权/人工标注/数据与音频摘要、加权 CER、角色文本误差、缺失分母、成本未知 |
| 真实线路验收 | `real-voice-acceptance.mjs` | 必须显式 `--confirm-dial`，使用受控测试号码与凭据文件 |
| 可选 PBX 夹具 | 编译 `Pbx.java` + 原生镜像；`media-probe.mjs` | 私网/密钥校验、ESL 健康、合成 PCM/中断/原生模块；不证明真实线路 |

业务入口没有购物车、下单和支付；通用验收规则中的电商链路不适用。本项目实际链路使用首页/客户搜索 → 任务/通话 → 拨号/回调 → 话单 → 分析/报表 → 返回/退出。没有验证的状态必须在报告中保留为未验证。

## 开发与检查

```sh
export JAVA_HOME='/absolute/path/to/jdk17'
export MAVEN_BIN=mvn
bash backend-java/scripts/build-java17.sh -B verify
bash services-java/scripts/build-java17.sh -B verify
(cd frontend && corepack enable && pnpm install --frozen-lockfile && pnpm build && pnpm test:coverage)
node --test tools/tests/*.test.mjs
node tools/check-toolchain.mjs
node tools/verify-contracts.mjs
node tools/verify-compose.mjs
JAVA_SERVICES_BROWSER=1 node tools/stack-smoke.mjs
node tools/media-process-smoke.mjs
bash backend-java/scripts/security-check.sh
bash services-java/scripts/security-check.sh
```

直接运行核心 jar 时设 `FRONTEND_STATIC_DIR=/absolute/path/to/frontend/dist`；容器已配置 `/app/static`。`JAVA_TEST_DATABASE_URL`、`JAVA_TEST_REDIS_URL`、`JAVA_TEST_S3_ENDPOINT` 必须指向独立测试实例，未配置的外部集成不能记为已验证。

## 无解释器的干净环境验收

```sh
docker build -f tools/Dockerfile --target browser -t outbound-no-python:validation .
node tools/clean-environment.mjs --image outbound-no-python:validation --browser
```

基础构建及最终验收镜像检查 `python/python3/pip/pip3` 均不存在，再执行 Java/JUnit、Node 工具、前端、独立 Java 进程、浏览器及隔离 PostgreSQL/Redis/S3。宿主只需要 Docker 与 Node；每轮使用随机容器/网络，保留报告后只清理本轮资源。不得对现有部署执行 `down` 或替换业务库。

业务镜像独立验收：

```sh
docker build -f backend-java/Dockerfile -t java-core-ci .
docker build -f services-java/Dockerfile -t java-services-ci .
node tools/container-smoke.mjs --role core --image java-core-ci
node tools/container-smoke.mjs --role services --image java-services-ci
node tools/migration-rehearsal.mjs
```

## 运维命令

- `node tools/fleet.mjs status --roster nodes.json`；排空用 `drain --node <id> --admin-token-file <path> --wait-seconds 60`，恢复用 `resume`。仅指定单节点；以持久活动通话和待回调均为零判断可停止。
- `node tools/capacity-preflight.mjs` 接受与旧工具对应的负载参数，**必须显式提供 `--ai-slots-per-host` 的实际 Java 通道数**，不保留旧 Python 默认 64。预检不是实测容量。
- `STABILITY_PG_DSN` 环境变量配合 `node tools/db-snapshot.mjs --output <fresh.jsonl> --seconds 60 --interval 5`；需要原生 `psql`，也可用 `--container <postgres-container>`。只读事务和语句期限；失败返回非零，凭据不进入参数或输出。
- `node tools/evaluate-asr.mjs --manifest <json> --results <a.json,b.json> --report <fresh.json> --verify-audio`。结果文件用逗号分隔；不上传录音。Unicode casefold 映射固定于 14.0 数据，Node 提供 NFKC；缺失/失败结果不从分母删除。
- `node tools/load.mjs --url http://127.0.0.1:<port> --isolated-mock --scenario read --rate 50 --seconds 20 --output <fresh.json>`；签名回调模式使用 `LOAD_WEBHOOK_TOKEN/SECRET` 环境变量。对话模式还需 `--calls-file` 指定独立合成通话。报告为 Java schema v2，HTTP 200 不等于 AI 回复已交付，不复用历史六场景容量结论。
- `node tools/soak.mjs --manifest <json> --output <new-directory>` 支持 8/24 小时；`--smoke` 只用于 1～60 秒自测。清单使用 argv 数组、目标主机和七类固定摘要镜像；监督完成不等于媒体/容量通过。
- `node tools/real-voice-acceptance.mjs --base-url <origin> --api-key-file <path> --phone <controlled-handset> --confirm-dial --report <fresh.json>`。需要时另加 `--security-approval-file`。不得未经授权运行真实外呼。

可选 PBX 构建使用经过批准的预编译原生 FreeSWITCH/VoiSmart 镜像作为制品来源，`FS_NATIVE_IMAGE=<image> ./scripts/build-voismart.sh` 只导出原生程序/库并编译 Java 辅助类，不安装旧 SDK 或解释器。原生制品的上游源码构建不再是当前项目的开发入口；生产必须固定、审计其摘要。启动器目前接受 RFC1918 IPv4 子网；现场 IPv6 部署未验证。

## 发布与回退边界

本轮目标是工具链不依赖 Python。测试环境发布、真机、真实旧库、现场 UID/证书/TLS、真实 SIP/RTP/云模型、500 路长稳和生产发布仍需单独验收。`JAVA_RELEASE_ACCEPTED` 默认关闭。正式切换前停写、备份、恢复演练，停止全部旧写入进程；不能双写同一库/账本。

当前树不再提供旧运行时启动入口。回退必须使用保留的旧制品或单独检出 Git 基线，并按数据恢复方案执行，不在新 Java 工具链中临时安装旧依赖。全量独立 Codex Security 扫描与源码 CI 是不同状态，历史宿主 Git 阻断不能被本轮工具迁移掩盖。
