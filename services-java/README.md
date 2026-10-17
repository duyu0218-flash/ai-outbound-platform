# Java 辅助业务服务

统一使用 **JDK 17 + Spring Boot 4.1.1**。一个 Java 制品按 `JAVA_SERVICE_ROLE=agent|recording|voice|media` 分进程运行四个角色，业务处理不代理或启动旧 Python 服务。

| 角色 | 职责 |
|---|---|
| agent | 对话策略、模型调用与账号额度 |
| recording | 录音获取、S3 校验、源清理与删除生命周期 |
| voice | 电话网关、FreeSWITCH ESL、签名命令、持久账本与回调 |
| media | 原生 Java PCM、ASR/TTS 协议及媒体会话归属 |

前端继续使用 React。FreeSWITCH、PostgreSQL、Redis、对象存储属于独立原生基础设施。旧 `PIPECAT_*`、`VOICE_AI_PIPELINE=pipecat|hybrid` 名称仅用于接口/配置兼容，实际媒体管道为 Java，不安装 Python Pipecat SDK；`pipecat_version` 返回空值。

从仓库根目录构建与验证：

```sh
export JAVA_HOME='/absolute/path/to/jdk17'
bash services-java/scripts/build-java17.sh -B verify
node tools/verify-contracts.mjs
node tools/verify-compose.mjs
node tools/media-process-smoke.mjs
JAVA_SERVICES_BROWSER=1 node tools/stack-smoke.mjs
bash services-java/scripts/security-check.sh
```

完整流程见 [无 Python 工具链](../docs/migration/no-python-toolchain.md)。进程验收需要已构建的两个 jar；浏览器验收还需前端构建及 Chromium。测试驱动使用 Node，不依赖解释器。新的原始报告输出到 `artifacts/no-python/`；当前状态见 [本轮验收](../docs/migration/no-python-acceptance.md)。

默认、compact 和 single-host-500 Compose 直接选择 Java。旧运行时和对照启动模板已从当前树退役，可在 Git `8cb4c30` 追溯，不能在新工具链中临时安装旧依赖。切换前停止旧写入进程并备份 SQLite/WAL、录音生命周期、对象数据及 PBX 文件；不同语言的文件锁不能作为双写保护。

四个角色提供只读 `/docs`、`/openapi.json`，监控语义见 [MONITORING.md](MONITORING.md)，迁移与现场条件见 [DEPLOYMENT.md](DEPLOYMENT.md)。`JAVA_RELEASE_ACCEPTED` 默认关闭。真实线路、云模型、现场 TLS/UID/卷、500 路长稳与生产切换仍需单独验收。
