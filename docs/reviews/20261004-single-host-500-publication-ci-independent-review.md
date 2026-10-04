# 发布 CI 的 PyJWT 安全补丁：独立审查

日期：2026-10-04。审查者：独立子代理 `capacity_reviewer`。工作树：`/workspace/ai-outbound-platform-single500-fixes-20261004`。比较基线：发布提交 `291844648b8b3480965a1769b8e62517818bfdb7`。

**结论：该额外依赖补丁的静态代码及已完成本地验证审查通过，未发现 P0/P1 阻断，可更新 PR。最终按更新后完整 GitHub CI 的实际结果同步；本报告没有把尚未执行的新依赖各服务、PostgreSQL/Redis 及供应链 CI 描述为通过。此次仅批准额外依赖补丁的软件发布，不新增真实 500 路容量资格。**

审查者只读取源码、差异和实际日志，比较文件摘要；没有执行测试、审计、负载或发布，没有修改业务代码、审计门槛、既有报告或历史 manifest。唯一新增仓库文件是本报告。

## 改动及兼容性

本次实现范围为两项：

- `backend/pyproject.toml` 唯一依赖差异是 `PyJWT[crypto]==2.13.0` 改为 `PyJWT[crypto]==2.15.1`，保留精确 pin 与 crypto extra。
- `backend/tests/test_auth_jwt_security.py` 新增 15 项安全回归：正常签发/解码，过期、错误签名、未允许 HS384、unsigned，exp/nbf/iat 的 None/list/dict 共九项，以及正确 HMAC 签名的深嵌套 JSON 拒绝。

身份验证使用的 jwt.encode/decode 与 PyJWTError 接口保持不变，decode 仍显式固定 algorithms 列表；业务签发的 sub 为字符串、exp 为 UTC 时间，uid/tv 为整数。不使用 JWK/JWKS、头部自动算法选择或 PyJWT 私有 API。测试合成密钥足够两种 HMAC 算法使用，monkeypatch 在测试结束恢复配置，不放宽生产认证。

`auth.py`、`scripts/audit-python-environment.py`、`scripts/check-version-constraints.py` 和 `.github/workflows/ci.yml` 与 `2918446` 逐字节相同。版本约束脚本允许更换精确 PyJWT pin，未将兼容约束检查当作漏洞审计。既有审计脚本仍枚举全部已安装第三方 distribution；仅排除已验证版本的一方项目，无第三方包或漏洞 ID 忽略。本次没有为审计失败新增例外。

## 实际验证证据

已读取 [补丁结果记录](20261004-single-host-500-publication-ci-followup.md)和 `evidence/20261004-single-host-500-publication-ci/` 中的原始日志、环境清单及 local-runner-results。

| 检查 | 实际证据及边界 |
|---|---|
| 定向 PyJWT 审计 | 2.15.1 的 strict/no-deps/disable-pip 审计记录 No known vulnerabilities found；这项单独只证明指定版本 |
| 完整后端依赖审计 | 全新环境中的全部已安装第三方依赖，包括 dev 与审计工具；日志未发现已知漏洞，local-runner-results exit_code=0。no-deps 针对完整已安装清单，未据此省略传递依赖 |
| 依赖及版本约束 | pip check 为 No broken requirements found，兼容矩阵与版本约束为 PASS；环境清单实际为 PyJWT 2.15.1、cryptography 50.0.2 |
| HS256 跨版本兼容 | 2.13.0 签发/2.15.1 验证与相反方向的合成令牌均为 true；限定于这次 HS256 合成用例，不推广到所有算法/配置 |
| 完整 SQLite 回归 | 最终日志 337 passed、17 skipped，local-runner-results exit_code=0；新增安全案例通过，未改业务错误处理来迎合用例 |

本地环境记录为 Python 3.12.14；仓库 CI 的 Python 3.11 与 PostgreSQL/Redis 环境须由更新后的实际 CI 验证。17 项跳过涉及缺少 PostgreSQL/Redis 环境的既有用例，静态查看其 skip 条件与此一致；不能计为本地通过。此前 `2918446` 的 PostgreSQL/Redis 成功也不能代替新依赖版本的 CI。

## 失败保留及测试修正

初次 GitHub job 清单实际是八个 success、一个 security-supply-chain failure。失败日志记录旧 PyJWT 2.13.0 的 13 项漏洞及审计 exit 1；结果记录没有声称初次 CI 全通过。2.15.1 的后续实际清洁审计支持此次升级，没有仅按部分 advisory 的 fix version 推测所有条目已消失。

第一次本地定向审计因默认缓存目录只读失败，保留原 traceback；后续仅显式使用可写临时缓存，审计规则和严格退出码不变。

初次 SQLite 日志仍保存 336 passed、17 skipped、1 failed：新增用例错误地认为 2000 层合法 JSON 必须无法解析。修正只把载荷两处深度改为 10000，以覆盖实际解析器 RecursionError 转为 401 的边界。把最终测试字节中的 10000 虚拟还原为 2000 后，SHA 精确等于初始测试快照，证明没有混入业务修复或删除断言；两个测试摘要及失败设计记录均保留。最终全套重跑通过该边界，无失败记录覆盖。

## 原 500 路证据边界与发布条件

已独立比较原软件验收 manifest 的 115 项运行源码：唯一当前差异是 `backend/pyproject.toml`，其余 114 项包含 auth 业务和 AI 调度/恢复/回调路径，全部匹配。既有文档摘要匹配，旧代码/证据审查报告没有被改写。

原 fixed5/fixed6/fault7 三轮合成软件验收仍绑定 `2918446` 和 PyJWT 2.13.0。它们的结果、归档与 manifest 是历史测试快照，不是升级后依赖清单；本报告没有声称 PyJWT 2.15.1 已重跑这些负载。原真实音频容量、发压有效性和 conversation control SLO 的 false 状态继续保留。用户允许本地硬件限制不阻塞软件交付，未授权把这些失败改为成功。

可把这两项实现及对应证据更新到 PR，由更新后的完整 GitHub CI 检查供应链、各服务、PostgreSQL/Redis、浏览器及部署配置。新的 CI 若失败应继续修复；旧八个通过项与本地 SQLite 均不能替代新提交的检查。最终根据实际 CI 结果同步源码，表述仍限于软件发布，不增加未经实测的 500 路商用资格。

## 审查快照 SHA-256

```text
dd6e223fe907c763522eaef42909c084c64d7720bea8e5530ae5ec8c1f8552a2  backend/pyproject.toml
45b36fd62c6d58c4e85c056ff81953a2fc07c160ef9b9506456cb01eae660c5a  backend/tests/test_auth_jwt_security.py（最终10000层用例）
b21b60afd636dccbbdcaf3a8d2860b371b7689c0cb30f13d8e9d448efbfa1f58  初始测试快照（2000层用例）
f0464db0f91d618e35b75d078d8d115e6ed77428466509020c3143ab4e53c444  backend/app/services/auth.py（与2918446相同）
4530884664d8ddc19c8359defbb4648a98e5b4b97a4721cd63006b9481642144  publication-ci-followup.md（本次审查时）
6686cd3264fcf52537b76c17ae9505979a59be5e7613bcc4ae682f23891df2a3  backend-full-dependency-audit.txt
daf264d353f8ad1d58ac7d5501b9abb397b68e6fe10679f4a8a3f7209e2f0437  backend-sqlite-final.txt
7b828a7e540effc929a9aada86963ee05f5435ca2386aca7babfa6536a659a01  local-runner-results.json
```
