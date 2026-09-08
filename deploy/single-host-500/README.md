# 单机 500 路部署与验收

本档对应 2026-09-08 评估及其硬件修订：呼出、振铃、AI/人工接通合计最多 500；ASR/TTS/LLM 在云端。32 专用 vCPU / 64 GiB 为首轮候选，16/32 可分档探索，64/128 仅在资源实测不足后考虑。本模板不是容量证书，也不提供整机故障后的无中断能力。

## 组件及容量

- 一个 FreeSWITCH、一个控制网关、一份网关安全账本；12 个媒体进程各 50，媒体预算 600，业务上限 500。
- 六个单进程 API，每进程 6 个 DB 连接、回调最多 5、管理请求保留 1；四个 AI worker 各 160 异步任务、2 个 DB 线程、4 个动作线程、5 个 DB 连接。
- 一个后台调度角色，8 个 DB 连接；回调、录音、分析、播放后动作及拨号独立有界工作池。总应用 DB 连接上限 `6×6+4×5+8=64`，PostgreSQL 上限 120，留迁移、运维余量。
- 两个 Agent 各 320 连接，共享 `model_quota/account.db` 的账号 RPM、估算 TPM、短时请求预算及限流冷却。模型网络等待不占用数据库。UTF-8 字节加输出上限是保守估算口径，需对选定模型 tokenizer 校准，不能作为供应商账单。
- PostgreSQL 和 Redis 同机，数据卷独立。模型额度账本与语音账本独立，只有模型账本由两个 Agent 共享；不得复制 PBX 控制者或共用网关安全账本。

媒体失败会保留原通话归属和容量，终止旧媒体代次并请求 PBX 收尾。新拨号取健康媒体容量、平台、租户、线路、任务和网关额度中的最小值。健康信息过期则停止准入；进程重启不会把旧通话移给新进程。Agent 额度/服务探测失败时，调度器停止向节点发起新呼叫；此保护有探测周期延迟，已接通通话仍走原超时及可听兜底策略。`/readyz` 不代表真实云语音和线路已验证。

## 配置与启动顺序

1. 使用本代码构建固定版本镜像及受控 Pipecat wheel。复制本目录示例到 `/etc/ai-outbound/single500`，填写 `host.env`。模板复用 compact 的单个服务定义，**只使用 `docker-compose.single-host-500.yml` 一个 Compose 文件**，不要同时 `-f docker-compose.compact.yml`。
2. `postgres.env` 配置 `POSTGRES_USER`、`POSTGRES_PASSWORD`、`POSTGRES_DB`；生产为应用和迁移分配独立最小权限角色，连接串与文件保持一致。`redis.env` 配置独立 `REDISCLI_AUTH`，与 backend.env 的 REDIS_URL 密码一致。秘密文件权限 600，日志和配置解析输出不得外传。
3. 按 `.env.example` 和真实线路/云服务填写 backend.env、agent.env、voice.env、recording.env。voice.env 必须设 `VOICE_CALLBACK_BASE_URL=http://127.0.0.1:8000`、`VOICE_CALLBACK_ALLOW_PRIVATE_HTTP=true`，独立签名密钥、ESL 凭据、`PIPECAT_MEDIA_PROTOCOL=voismart`、固定 Pipecat 版本及真实 ASR/TTS 配置。Agent 使用 `LLM_PROVIDER=openai-compatible` 和批准的 HTTPS 地址/allowlist。服务之间的 token 必须匹配，独立用途密钥必须不同。
4. nodes.json 使用唯一 `single-500`，CPS 先设 15。voice-routes.json 必须为真实获准租户/线路填写前缀、主叫、网关、500 上限和独立 CPS/日限额/费用预算。**不自动放宽租户、任务、线路、日限额或预算。** 网关 `VOICE_CPS`、日限额、金额预算也需按同一工作量明确配置，否则较小限额仍拦截。`LLM_APPROVED_*` 填实际批准预算，不可直接把评估示例当成已获额度。
5. 合并 switch.conf.example.xml 到已验收的 PBX 配置；保留 ESL 仅回环、SIP 鉴权/ACL、`agent-restricted`、音频模块、WAV 兜底音及录音权限。媒体 RPC、Agent、网关、DB 和 Redis 全部只在回环监听；业务端口 8000 由另行配置的 HTTPS 入口代理，转发与可信代理配置需一起验收。RTP 防火墙与 XML 范围一致；不开放 ESL。
6. 启动 DB/Redis，执行现有备份、迁移与初始化流程，再启动应用；生产保持 `AUTO_MIGRATE=false`，迁移失败不可启动调度。应用非 root UID 必须可写新建的 voice_security、model_quota 和 recording_spool 数据卷，权限需按已构建镜像 UID 初始化。

只读渲染及校验示例（执行时配置包含秘密，渲染文件存私有目录）：

```sh
umask 077
docker compose --env-file /etc/ai-outbound/single500/host.env -f docker-compose.single-host-500.yml config --format json > /tmp/single500-private.json
python scripts/check-single-host-500.py --compose-json /tmp/single500-private.json
python scripts/check-node200-pbx.py --expanded-core-xml /path/to/effective-switch.xml --calls 500 --spare-sessions 500 --cps 50
python scripts/capacity-preflight.py --roster /etc/ai-outbound/single500/nodes.json --admin-token-file /path/to/security-token --scope 1:0 --topology single-host --call-semantics inflight --target 500 --mean-setup-sec 20 --mean-duration-sec 120 --answer-rate .3 --hours 8 --turn-interval-sec 4 --mean-ai-task-sec 3 --ai-slots-per-host 640
```

预检只判断配置与预算；同机模式不套用集群 N-1 验收。模型最坏负载仍按 500 全接通估算，与振铃占比无关。修改共享模型预算前先排空全部 Agent，保留至少 61 秒冷却并受控更新账本配置；不同预算的进程会拒绝启动，不通过换 scope 绕过共享额度。

## 交付验收入口清单

| 入口 | 状态与验证方式 |
|---|---|
| 节点清单与拨号 | 500 在途、并行申请第 501 路、未知结果、较小租户/线路上限、停止/恢复、重复 attempt |
| 媒体控制 | 12 个真实进程的合成会话建立、归属恢复、旧 epoch 拒绝、单进程故障、健康过期、部分容量、新旧通话隔离 |
| 回调 | 终句/媒体/终态同通话有序、跨通话并发；FULL 落盘后 ACK、批提交失败、取消/退出、重启与重复事件 |
| Agent | 两实例共享额度、RPM/估算 TPM/短时预算、超额无模型请求、429 冷却、账本不可用、请求取消后释放本地槽位 |
| 运维 | Compose 合并、总 DB/CPU/内存预算、PBX 呼叫腿及端口、逐 API metrics、录音/备份恢复 |
| 既有业务页面 | 登录→客户搜索/创建→任务/话术保存→外呼启停→话单→人工接管→录音/质检/线索；表单、回显、返回、退出及异常状态 |

本仓库没有门店/购物车/支付功能；对应通用验收链采用外呼业务链，不标记不存在的电商入口通过。

## 正式上线门槛

目标 Linux 机按 20→50→100→200→350→500 在途分档，最坏档含 500 路真实音频、动态云模型、打断、双声道录音和真实转人工。每档至少 30 分钟，500 档 8 小时及 24 小时混合运行；600 有效混合回调/秒持续、1200/秒突发、500 同步终句和同步挂断分别记录。

必须附计划/实际发出、成功/拒绝/超时、队列最老年龄、DB 锁及连接等待、CPU/内存/磁盘/网卡曲线、终句至首音和打断 P95/P99。音频全链路、真实云额度和真机未通过不得声明 500 路商用。持续积压或超时应降低准入并定位，不增加超时掩盖失败。

长期录音存对象存储，本机保留上传缓冲；盘容量按保留天数测算，不默认采购四盘。使用 `scripts/backup-postgres.sh`、`scripts/verify-postgres-backup.sh`、`scripts/restore-postgres-drill.sh` 完成异机备份和恢复演练；保留安全/媒体/模型账本，恢复时先禁拨并对账，不能把旧备份当成空闲容量。该配置不自动部署、购买资源或修改现有实例。
