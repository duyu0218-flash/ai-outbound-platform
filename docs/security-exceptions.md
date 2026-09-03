# 安全依赖例外

当前没有已批准的第三方漏洞忽略项。

原 NLTK 临时例外已移除：语音网关改用受控 `pipecat-ai==1.8.1+outbound.1`，从包元数据和执行路径中删除 NLTK，不再下载 `punkt_tab`。构建/安装校验失败、NLTK 被重新引入或在线审计发现漏洞，CI 均必须失败。

补丁来源、复现构建、审计与回退约束见 [Pipecat 受控补丁包](pipecat-controlled-patch.md)。这不代表 NLTK 上游漏洞已发布修复版，也不代表旧部署已自动更新。
