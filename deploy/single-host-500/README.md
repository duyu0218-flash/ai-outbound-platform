> 当前工具链及命令以仓库 `docs/migration/no-python-toolchain.md` 为准。下列既有验收数字保留为历史证据，不代表本轮重验；旧 Python 源码与对照启动模板已从当前树退役。

# 单机 500 Java 部署候选

本目录示例已配合根目录 `docker-compose.single-host-500.yml` 切换为 JDK 17 / Spring Boot 4.1.1。详细启动顺序、JDBC、Redis TLS、持久卷/UID 和离线旧库迁移要求见 [Java 部署说明](../../services-java/DEPLOYMENT.md)。验收状态见 [ACCEPTANCE.md](../../services-java/ACCEPTANCE.md)。

旧 Python 组件数量、640 异步 AI 槽位、原进程健康文件及历史性能说明保存在 [原部署参考](README.python-reference.md)，只适用于对应 `*.python-reference.yml`，不得作为 Java 容量或发布结论。

复制本目录示例到仓库外并填写实际批准的制品、账户、路由、凭据和证书，保持秘密文件权限 600。API 与 worker 使用同一核心镜像，Agent/录音/网关/媒体使用同一辅助镜像及不同角色。先停止旧写入并完成备份、副本迁移和回退演练，再启动 Java；默认 `JAVA_RELEASE_ACCEPTED=false`，现场验收前不切业务流量。

只读检查：

```sh
python services-java/scripts/verify_compose.py
docker compose --env-file /etc/ai-outbound/single500/host.env -f docker-compose.single-host-500.yml config --quiet
```

配置解析不证明主机权限、证书、真实线路、云服务或容量通过。完整渲染会包含秘密，不得上传。Java 的实际运行、指标和回归记录与目标部署分别报告；当前没有测试环境、真机或生产发布。
