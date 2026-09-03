# 安全依赖例外

本文只记录暂时无法通过升级消除、且已经建立自动化边界检查的依赖告警。例外不能替代修复；上游发布修复版本后应立即删除例外并恢复完整阻断。

## PYSEC-2026-3740 / CVE-2026-81726

- 状态：临时例外，复核截止日期为 2026-10-03。
- 影响依赖：`voice_gateway` → `pipecat-ai==1.8.1` → `nltk==3.10.3`。
- 上游状态：截至 2026-09-03，GitHub 安全公告将 `nltk<=3.10.3` 标为受影响，未提供已修复版本。
- 漏洞前提：应用启用 NLTK `pathsec`，并把不可信路径交给指定的模型导入、导出或保存 API。
- 当前可达性：平台代码不直接导入 NLTK；固定版本的 Pipecat 只在 `pipecat/utils/string.py` 中使用 NLTK 句子分词，不调用公告列出的文件路径 API。
- 补偿控制：精确锁定 Pipecat 和 NLTK 版本；CI 在忽略该单一告警前运行 `scripts/check-nltk-advisory-boundary.py`，任何新的 NLTK 导入位置、危险 API 名称或版本变化都会使检查失败。
- 移除条件：NLTK 发布修复版本，或 Pipecat 移除 NLTK 依赖。升级后删除 `--ignore-vuln PYSEC-2026-3740`、本条记录和不再需要的边界检查。
- 禁止事项：不得把用户输入、接口参数或模型配置映射为 NLTK 模型文件路径；不得扩大 `pip-audit` 的忽略范围。

参考：[GitHub 安全公告 GHSA-8mgp-746c-j5xp](https://github.com/nltk/nltk/security/advisories/GHSA-8mgp-746c-j5xp)
