# 安装与运行

开发工具：JDK 17、Maven 3.9.11、Node.js 22、pnpm 11.19.0；容器部署需要 Docker/Compose。不需要安装 Python 或旧版业务依赖。

完整命令及验收入口见 [无 Python 工具链](docs/migration/no-python-toolchain.md)。首次切换必须先完成 [旧库迁移与回退](backend-java/MIGRATION.md) 和 [目标部署验收](services-java/DEPLOYMENT.md)。

开发构建：

```sh
export JAVA_HOME='/absolute/path/to/jdk17'
bash backend-java/scripts/build-java17.sh -B verify
bash services-java/scripts/build-java17.sh -B verify
(cd frontend && corepack enable && pnpm install --frozen-lockfile && pnpm build)
node --test tools/tests/*.test.mjs
node tools/check-toolchain.mjs
```

默认容器开发入口：

```sh
./scripts/bootstrap-deployment-secrets.sh
docker compose --env-file .env.example config --quiet
docker compose --env-file .env.example up -d --build
```

以上是开发启动入口，不是对现有生产环境执行切换的授权，也不代替旧库和真实线路验收。不要对含业务数据的环境直接执行示例。生产固定镜像摘要、验证 TLS/挂载/UID/凭据，部署流程见上述文档。
