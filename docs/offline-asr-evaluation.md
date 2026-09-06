# 第一阶段：离线转写评测

本阶段补齐语音生命周期边界、真实验收判定和离线对比工具。VibeVoice 尚未部署，也没有真实中文电话效果结论。现有云 ASR 和候选模型可各自导出到下面的统一文件格式，再用同一份人工标准答案比较。

入口是 `scripts/evaluate_asr.py`，仅依赖 Python 3.11+ 标准库。它不调用云接口、不下载权重、不上传录音、不写入通话、意向或质检业务表。模型推理和结果导出仍需在独立的候选服务完成。

## 准备样本和标准答案

1. 选择已获授权的电话录音，包含正常通话、噪声、口音、短句、数字、品牌/车型/门店词和静音样本。建议先选 20～50 个短片段做可行性验证，再扩大覆盖；数量只是起步建议，不代表统计充分。
2. 人工校对内容、起止时间和说话人，固定样本集。不能直接把某个模型输出作为标准答案。双声道录音优先按实际通道映射客户/坐席；不要默认 `Speaker 0` 就是客户。
3. 原音频、人工标签、各模型原始 JSON 分开保留；用 SHA-256 绑定同一段音频。按需把会话拆成短片段，保留原始通话和片段偏移的内部映射，不把电话号码放进样本 ID。
4. 对比双方使用相同音频和预先冻结的业务词库，记录模型精确版本、热词配置、硬件、计费口径及原始输出。`keywords` 是人工标注的、实际在该片段出现的目标业务词，用于计算召回，不是从标准答案临时生成的模型提示词。

清单文件 `manifest.json`（下列摘要值需要替换）：

```json
{
  "schema_version": 1,
  "dataset_kind": "recorded",
  "samples": [{
    "id": "sample-001",
    "audio_path": "audio/sample-001.wav",
    "audio_sha256": "填写该音频的64位小写SHA-256",
    "duration_ms": 4000,
    "authorized": true,
    "human_verified": true,
    "keywords": ["预约"],
    "segments": [{
      "start_ms": 300,
      "end_ms": 1900,
      "speaker_role": "customer",
      "text": "我想预约明天下午。"
    }]
  }]
}
```

`dataset_kind` 必填：真实录音为 `recorded`，工具验证夹具为 `synthetic`。授权和人工校对标记需要由整理人确认，程序只能检查声明，不能代替审核。音频路径相对清单目录解析；时间单位统一为毫秒，分段不能超过录音时长，静音样本用空 `segments`。

## 导出识别结果

每个提供方/模型版本一份结果文件。`dataset_sha256` 是最终清单文件的字节摘要；即使只修改了清单空格，也应重新冻结并更新摘要。通过下列命令获取摘要：

```bash
python3 -c 'import hashlib,pathlib,sys; print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())' manifest.json
```

结果文件：

```json
{
  "schema_version": 1,
  "dataset_sha256": "填写manifest.json的64位小写SHA-256",
  "provider": "实际提供方名称",
  "model": "实际模型标识",
  "revision": "实际权重提交或部署版本",
  "items": [{
    "id": "sample-001",
    "audio_sha256": "与清单中相同的音频SHA-256",
    "status": "ok",
    "processing_ms": 850,
    "cost_cny": 0.01,
    "segments": [{
      "start_ms": 320,
      "end_ms": 1920,
      "speaker_role": "customer",
      "text": "我想预约明天下午。"
    }]
  }]
}
```

上面的耗时和费用仅为格式示例。没有测量就省略或填 `null`，不能用 0 代替未知值。推理失败时使用 `status: "error"`，保留样本 ID 和音频摘要；成功但未识别出内容用 `status: "ok", segments: []`。整批没有成功样本时也应提供每条 error 记录，不要交空列表。VibeVoice 和云 ASR 的不同字段需在导出时转换到上述格式；原始输出另存，不直接送入现有实时 speech webhook。

## 执行比较

在仓库根目录执行，所有输入和报告另存在受控目录：

```bash
python3 scripts/evaluate_asr.py \
  --manifest /secure/asr-eval/manifest.json \
  --results /secure/asr-eval/cloud.json /secure/asr-eval/vibevoice.json \
  --verify-audio \
  --report /secure/asr-eval/comparison-001.json
```

程序拒绝覆盖已有报告和输入文件。退出码 `0` 表示结果完整并生成报告，`1` 表示存在缺失/失败样本但已生成报告，`2` 表示输入或输出错误。`0` 不表示效果合格或可上线。默认不开启音频文件核验时，报告会明确 `audio_files_verified=false`；正式对比应使用 `--verify-audio`。

## 指标口径

| 字段 | 口径与限制 |
| --- | --- |
| `cer` | 总编辑距离 / 总标准答案字符数；按字符数汇总，不平均每条 CER。缺失或失败样本按空识别结果计算删除错误，并另报数量。插入较多时可大于 1。全静音集分母为 0 时返回 null，插入错误仍保留。 |
| `keyword_recall` | 每条样本内去重的目标词命中数 / 目标词总数；同一词多次出现仅算一次。无目标词时为 null。该值不等于意向识别准确率。 |
| `role_text_cer` | 按已映射的说话人角色拼接后计算文本错误率，用于发现客户/坐席串话。角色缺失时为 null；它不是时间加权的 DER，也不是说话人身份鉴定。 |
| `offline_processing_p50_ms/p95_ms` | 成功样本中有测量值的整段推理耗时，使用 nearest-rank 分位数，同时报告样本数。不是实时 ASR 句末延迟或电话端到端延迟。 |
| `offline_rtf_p95` | 每段处理耗时 / 音频时长，再取 P95；不能据此推断多路通话容量。 |
| `cost_per_audio_minute_cny` | 所有样本实测费用 / 所有样本音频分钟数。任何样本费用缺失就返回 null；免费额度、硬件摊销等口径需由整理人另附说明。 |

归一化固定为 Unicode NFKC、小写折叠、去空白和标点；保留数字、词语和其他符号，不将“一百”自动改为“100”。报告保留清单及结果文件摘要、模型版本、失败数量、每条错误指标，默认不复制原始转写内容。

## 后续准入

只有拿到真实样本效果、人工抽检、目标机器耗时和成本之后，才决定是否增加离线质检服务。实时替换仍需独立完成采样率适配、流式句末、插话、播放停止、转人工、超时回退和目标并发验收。报告始终保留 `real_line_verified=false` 和 `production_approved=false`，离线评分不能给这两项验收盖章。
