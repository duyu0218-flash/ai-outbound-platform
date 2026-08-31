# 版本约束与兼容矩阵

`compatibility-matrix.toml` 是项目版本基准的唯一机器可读来源。生产环境固定已经验收的精确版本和不可变镜像摘要；候选版本持续自动跟踪并通过升级PR进入测试，但未经回归验收和受控灰度不得进入生产。安全修复走加急升级流程，但不能绕过最小回归和灰度门禁。

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
| Pipecat | 1.8.1 候选集成 | 固定精确版本；真实媒体回归与灰度前保持 `legacy` | 代码/容器依赖已验证；真实线路未验证 |
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

1. `.github/dependabot.yml` 每周跟踪Python、前端、Docker基础镜像和GitHub Actions的新版本，并创建候选升级PR。
2. 所有候选升级PR自动触发与普通PR相同的版本约束、服务测试、PostgreSQL/Redis集成和Compose浏览器验收；自动进入测试不等于自动批准。
3. 自动化工具只能创建升级PR，不得自动批准、自动合并或部署生产。
4. 依赖变更必须同时更新lockfile、兼容矩阵和变更说明；矩阵不一致时CI必须失败。
5. 基础镜像Tag可以作为开发默认值；生产的Python、Node、PostgreSQL、Redis、FreeSWITCH、coturn、Nginx都必须解析并固定为 `repository@sha256:<digest>`。
6. FreeSWITCH升级必须连同Sofia、Event Socket、编解码、TTS、媒体模块和配置文件一起回归。
7. Pipecat 1.8.1 作为候选链路已精确锁定；启用生产前必须同时验证自定义Serializer、音频编码、ASR、TTS、打断和上下文一致性。
8. 严重安全问题在24小时内完成影响评估并进入加急升级；仍须经过最小回归、真实链路验证和受控灰度，不得直接推送生产。
9. Python直接依赖和前端直接依赖必须精确约束；前端传递依赖由lockfile冻结。Python传递依赖以验收后的应用镜像摘要为最终发布边界。

### 自动测试与生产发布的边界

- 自动流程负责发现新版本、创建候选PR并执行仓库可自动化的全部测试。
- 回归负责人确认兼容矩阵、变更影响和测试结果后，才能批准候选版本。
- 灰度必须使用生产同构配置，在限定租户、线路或实例上验证监控指标和回滚路径。
- 灰度通过后，才允许把候选版本写入生产发布清单；生产部署仍由发布审批触发。
- FreeSWITCH、媒体模块和Pipecat候选链路涉及真实音频链，仓库CI不能替代真实线路、双向音频、打断、录音和转人工验收。

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
