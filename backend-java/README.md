# Java 核心后端

业务运行时为 **JDK 17 + Spring Boot 4.1.1**，实现核心 API、鉴权、客户/话术/任务、拨号执行、持久回调、分析报表与数据库维护。开发、测试与运维不需要安装旧 Python 应用。

从仓库根目录构建：

```sh
export JAVA_HOME='/absolute/path/to/jdk17'
bash backend-java/scripts/build-java17.sh -B verify
node tools/verify-contracts.mjs
bash backend-java/scripts/security-check.sh
```

首次启动或旧库切换须先阅读 [迁移与回退](MIGRATION.md)。运行时默认不自动初始化业务库，`JAVA_INITIALIZE_SCHEMA=false`；`JAVA_RELEASE_ACCEPTED` 默认关闭。`JAVA_DISPATCH_ENABLED` 和 `JAVA_WORKERS_ENABLED` 分别控制调度准入和执行器，不能用启用标记代替验收。

前端使用 React/TypeScript，`frontend` 中执行 `pnpm build` 输出 `frontend/dist`。直接运行 jar 时显式设置 `FRONTEND_STATIC_DIR` 为该目录的绝对路径；核心 Dockerfile 已将资源打包到 `/app/static`。

当前测试/容器/安全检查入口统一见 [无 Python 工具链](../docs/migration/no-python-toolchain.md)，最新状态见 [本轮验收记录](../docs/migration/no-python-acceptance.md)。JDBC、Redis、网关、Agent、录音存储和正式发布条件见 [部署说明](../services-java/DEPLOYMENT.md)。

旧合约、SQL 和向量已冻结保留；不再调用旧应用重新导出。旧版 README 与既有迁移验证记录保留为历史证据，不作为本轮或生产验收结论。真实业务库、线路、云服务、500 总在途和生产发布须单独验收。
