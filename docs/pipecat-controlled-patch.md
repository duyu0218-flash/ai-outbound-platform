# Pipecat 受控补丁包：移除 NLTK

## 范围与身份

- 上游基线：PyPI 官方 `pipecat-ai==1.8.1` wheel；平台版本为 `1.8.1+outbound.1`，与 `PIPECAT_VERSION`、网关依赖声明和兼容矩阵一致。
- `vendor/pipecat/manifest.json` 固定 URL、上游 SHA-256 和产物 SHA-256。默认构建拒绝任一摘要不符的产物。
- `scripts/build-pipecat-wheel.py` 不执行/解压上游代码，以固定 ZIP 顺序、时间戳、权限和无压缩格式重建 wheel，保留 BSD-2-Clause 许可证并重算 RECORD。不进行运行时 monkey patch。
- 改动限于分句入口、预热入口、流式 lookahead、新增纯内存扫描器及包元数据；其余 Pipecat 文件字节一致。

## 安装、测试与离线复现

从仓库根目录操作，使用全新 Python 3.11+ 环境，避免旧环境遗留 NLTK：

```bash
python3 -m venv .venv-no-nltk
source .venv-no-nltk/bin/activate
python -m pip install pip==26.2.1 setuptools==84.0.0
python scripts/build-pipecat-wheel.py
python -m pip install --find-links artifacts/wheels './voice_gateway[dev]'
python scripts/check-pipecat-distribution.py
python -m pip check
(cd voice_gateway && python -m pytest -q tests)
```

上游 wheel 缓存于 `artifacts/upstream`，补丁 wheel 位于 `artifacts/wheels`，均不入 Git。可传 `--upstream-wheel /已校验归档/pipecat_ai-1.8.1-py3-none-any.whl` 离线重建；其余依赖离线安装仍需独立 wheelhouse。Docker 和 CI 执行同一配方，不依赖本机缓存。

`--allow-unpinned-output` 仅供维护者生成/升级配方时计算待审查摘要，正常安装、Docker 和 CI 禁止使用。修改配方必须更新摘要、补丁版本及测试证据。部署时更新自己的 `PIPECAT_VERSION`；本次代码变更不覆盖现有 `.env` 或自动替换服务。

## 分句契约与边界

扫描器仅依赖标准库 `re`，不读写文件、不请求网络、不加载模型。保留 `match_endofsentence(text) -> int` 原始文本偏移约定；无完整句返回 0。支持中英文终止标点、连续标点、尾随引号、常见英文缩写/首字母缩写、金额小数、版本号、网址和邮箱。

聚合器等到标点后的非空白正文字符才确认分句，保留连续标点和右引号。单独 `29.` 无法判断是小数还是句末，需等待下一字符或流结束；`flush` 输出剩余半句，打断/reset 清空旧文本。TOKEN 模式保持逐块输出。

这是有明确词表的保守规则，不承诺等同 Punkt 的所有语言学判断。陌生缩写可能误切；已知缩写在真正句尾可能延后至后续标点或 flush。生产放量前须用实际话术验收缩写、货币、电话、网址及中英混排 TTS 节奏。

## 供应链门禁

- `check-pipecat-distribution.py` 检查精确版本、安装包字节与已批准 wheel 一致、无 NLTK 分发/可导入模块/依赖声明/直接导入。
- PyPI 不认识 `+outbound.1`，直接审计会跳过本地版本。`audit-python-environment.py` 校验补丁后，以 **上游 1.8.1** 查询 Pipecat 本体公告，其余包按实际安装版本审计，不重新解析已移除的 NLTK 依赖。使用 `pip-audit --strict`，不忽略漏洞；只不向 PyPI 查询仓库内四个第一方服务包。
- CycloneDX SBOM 保留真实版本 `1.8.1+outbound.1`。原 NLTK 例外及边界脚本已删除。
- 测试覆盖文本全部两段切分位置、逐字输入、无 I/O、flush、打断、TOKEN 模式、可复现构建、RECORD 和许可证；CI 同时跑供应链、四服务、PostgreSQL/Redis、Compose、浏览器。

## 发布与回退

CI/模拟链路通过不等于真实 SIP、ASR/TTS、真机或生产验收；保持生产 `legacy` 默认值。发布前备份旧镜像及配置，以受控候选/灰度验证语音质量。回退旧 NLTK 依赖属于重新引入已知漏洞，须单独审批，不能恢复全局忽略。

上游跟进：[Pipecat #5627](https://github.com/pipecat-ai/pipecat/issues/5627)。将来切回官方包也必须经过同等回归。
