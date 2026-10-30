# AI 外呼平台

- **[平台操作手册（截图＋步骤说明）](docs/user-guide/README.md)**：管理员配置、客户与话术、外呼任务、话单查询、线索跟进、运营报表和坐席操作。

最新单实例、单进程迁移代码位于 [outbound-mono](outbound-mono/README.md)：JDK17、Boot3.5.16、MySQL8.4、Redis、Vue3，API/AI/语音/录音/媒体合入一个JVM。本地336项功能回归通过；500总在途、50轮/秒模拟仍未达标，最新42.9轮/秒，边界见 [验证摘要](outbound-mono/VALIDATION.md)。默认Compose及现有部署未切换，下列多进程工程保留为原实现与契约基准。

业务后端统一为 **JDK 17 + Spring Boot 4.1.1**。核心 API/调度/任务位于 `backend-java`；Agent、语音网关、媒体工作器和录音适配位于 `services-java`。前端使用 React/TypeScript。

当前活动开发、测试、部署和运维入口使用 Java、Node.js/Shell 或原生组件；旧 Python 运行时、依赖和入口从当前树退役，历史可由 Git 基线 `8cb4c30` 追溯。文件分类及替代位置见 [逐文件清单](docs/migration/python-retirement-inventory.json)。

- [开发、CI、无 Python 环境验收与运维命令](docs/migration/no-python-toolchain.md)
- [安装与部署](INSTALL.md)
- [数据库迁移及回退](backend-java/MIGRATION.md)
- [Java 服务部署与目标环境前置条件](services-java/DEPLOYMENT.md)
- [监控语义](services-java/MONITORING.md)
- [本轮验收状态](docs/migration/no-python-acceptance.md)

默认 `docker-compose.yml`、compact、single-host-500 配置均运行 Java 业务服务。单机 500 是配置候选，不能沿用旧 Python 的容量结论。实际部署未自动切换；测试环境、真实线路/云服务、现场数据库/证书及生产验收均须单独报告。

旧设计与验收记录保留用于追溯，不作为当前运行指令或本轮验证证据。正式上线前必须完成停写备份、旧库副本迁移、恢复演练和目标环境资格验证，`JAVA_RELEASE_ACCEPTED` 默认关闭。
