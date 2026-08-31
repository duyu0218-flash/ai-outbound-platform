# 版本约束与兼容矩阵

`compatibility-matrix.toml` 是项目版本基准的唯一机器可读来源。生产发布必须锁定已验收版本；候选版本可以自动进入测试，但不得自动进入生产。

## 当前基准

| 组件 | 代码/开发基准 | 生产要求 | 当前证据状态 |
|---|---|---|---|
| 应用服务 | 0.1.0 | 固定发布版本和镜像摘要 | 源码已确认 |
| Python | 3.11.x / `python:3.11-slim` | 固定构建镜像摘要 | CI已确认；生产摘要未提供 |
| Node.js | 22.x / `node:22-alpine` | 固定构建镜像摘要 | CI已确认；生产摘要未提供 |
| pnpm | 11.19.0 | `packageManager`与lockfile一致 | 已确认 |
| setuptools | 84.0.0 | 三个Python服务构建后端完全一致 | 已确认 |
| PostgreSQL | 16.x | 生产镜像必须使用摘要 | 生产摘要未提供 |
| Redis | 7.x | 生产镜像必须使用摘要 | 生产摘要未提供 |
| FreeSWITCH | 待现场确认 | 精确版本、镜像摘要、模块清单 | 未验证真实环境 |
| FreeSWITCH媒体模块 | 尚未选型 | 精确版本并与FreeSWITCH联合验收 | 未选型 |
| coturn | 4.17.2-r0 | 生产镜像必须使用摘要 | 生产摘要未提供 |
| Nginx | 1.28.0-alpine | 生产镜像必须使用摘要 | 生产摘要未提供 |
| Pipecat | 当前未采用 | 未来启用时必须精确锁定 | 不适用 |
| GitHub Actions | checkout/setup-python/setup-node v7 | 发布前改为审计过的完整commit SHA | 当前仅固定major tag |

空白或 `pending` 字段代表尚缺真实环境证据，不得用猜测值补齐，也不得据此宣称生产已固定版本。

## 校验

代码仓库基准：

```bash
python scripts/check-version-constraints.py
```

生产发布文件还必须验证所有基础镜像都使用不可变摘要，并记录FreeSWITCH实际版本：

```bash
python scripts/check-version-constraints.py --production-env /secure/path/production.env
```

生产版本文件不得进入仓库，因为它通常与部署环境、私有镜像仓库和其他配置一起管理。可以从 `deploy/compatibility/production-versions.env.example` 复制字段。

## FreeSWITCH现场取证

发布前在目标服务器执行只读检查：

```bash
docker inspect ai-outbound-freeswitch --format '{{.Config.Image}} {{.Image}}'
docker exec ai-outbound-freeswitch fs_cli -x version
docker exec ai-outbound-freeswitch fs_cli -x 'show modules'
docker image inspect "$(docker inspect ai-outbound-freeswitch --format '{{.Image}}')" --format '{{json .RepoDigests}}'
```

将结果写入发布记录，并更新矩阵中FreeSWITCH及媒体模块的约束。仓库矩阵只保存批准版本，不保存SIP、ESL、TURN或其他密钥。

## 升级规则

1. 自动化工具只能创建升级PR，不得自动合并或部署生产。
2. 依赖变更必须同时更新lockfile、兼容矩阵和变更说明。
3. 基础镜像Tag可以作为开发默认值；生产的Python、Node、PostgreSQL、Redis、FreeSWITCH、coturn、Nginx都必须解析并固定为 `repository@sha256:<digest>`。
4. FreeSWITCH升级必须连同Sofia、Event Socket、编解码、TTS、媒体模块和配置文件一起回归。
5. Pipecat若启用，必须同时验证自定义Serializer、音频编码、ASR、TTS、打断和上下文一致性。
6. 严重安全问题在24小时内完成影响评估；升级仍须经过最小回归和受控灰度。
7. Python直接依赖和前端直接依赖必须精确约束；前端传递依赖由lockfile冻结。Python传递依赖以验收后的应用镜像摘要为最终发布边界。

## 发布门禁

版本约束通过只证明仓库声明一致。正式发布还必须完成：

- Python服务测试；
- 前端构建与测试；
- PostgreSQL/Redis集成测试；
- Compose与浏览器验收；
- 真实FreeSWITCH版本与镜像摘要取证；
- 受控号码真实拨打、双向音频、录音、打断和转人工验收；
- 目标并发容量测试。

未经实际执行的项目必须标记为“未验证”。
