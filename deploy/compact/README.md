# 四台应用服务器部署

本轮代码修复与验证边界见 [P1/P2 修复验收记录](../../docs/reviews/20260907-capacity-p1-p2-fixes.md)。以下新增配置必须合并到各节点实际配置，不会自动覆盖租户已保存策略。

## 本轮升级新增配置

- `nodes.json` 每个启用节点需要明确 `cps`，不得高于该节点/全部授权线路中的最低 CPS。示例 2 只是安全起始值；生产清单不允许省略。
- 网关 `voice.env` 配置 `VOICE_AGENT_REGISTRARS_JSON`，例如 `{"1:23":"10.20.0.12:5060"}` 表示租户 1 的坐席 23 真正注册在该 PBX。必须同时验证浏览器注册位置、目标 PBX 的私网 SIP 鉴权/ACL 和 `agent-restricted` 拨号计划。缺少集群坐席路由会明确拒绝，不会退回本地用户查找。
- compact 为网关设置专有 `VOICE_RECORDING_SOURCE_BASE_URL=http://${NODE_PRIVATE_IP}:8002`，复用现有只读录音目录，提供带节点签名的 24 小时下载链接。录音适配器 `RECORDING_SOURCE_ALLOWED_HOSTS` 必须包括四台实际节点地址；公网或跨不可信网络须改为节点专有 HTTPS 地址。适配器并发 2，节点下载并发 2；长期故障导致签名过期需要重新签发或补录。
- 默认新增 `dial_call:8` 与 `after_playback:4` 后台执行通道；等待播放不再占用 AI 槽。静态页面/资源使用独立有界名额 32；数据库请求名额仍维持原值。
- 新迁移 `20260907_capacity_bottlenecks.sql` 增加 CPS 预留字段、TaskReceipt 及索引。完整升级先验证迁移和备份恢复，再升级网关/受限拨号计划、worker 与 API；不得只更新 API 而保留不认识新任务类型的 worker。

通过管理凭据读取真实生效的网关策略并预检（下列时长和接通率仅为命令示例，应换成实测值）：

```sh
python scripts/capacity-preflight.py --roster /etc/ai-outbound/nodes.json \
  --admin-token-file /secure/voice-admin-token --scope 1:0 --target 500 \
  --mean-duration-sec 180 --answer-rate 0.5 --hours 8 \
  --turn-interval-sec 4 --mean-ai-task-sec 3 --ai-slots-per-host 256
```

预检同时计算 N-1 与 AI 70% 利用率预算。AI 槽位必须按目标工作负载和实际模型耗时重新计算；通过预算检查并不代表模型或音频已经达标。`policy_check_passed` 也不代表真实音频已验收，输出中未验证项目必须逐项完成。

当前部署目标为：四台独立 Linux 宿主机，每台包含 FreeSWITCH、网关、Pipecat、API、AI Agent 和后台任务；PostgreSQL、Redis、负载均衡、录音对象存储使用托管服务。单节点 200 路、全平台 500 路均为待真实媒体验收的目标。

## 配置与发布入口

使用仓库根目录的 `docker-compose.compact.yml`，它是独立模板，不与原 `docker-compose.yml` 叠加。模板中没有 PostgreSQL、Redis、SeaweedFS 容器；管理网页由 control-api 同时提供。每台运行四个独立 API 进程、本机 NGINX、两个异步 AI worker、一个后台 worker、一个 PBX 控制进程与四个媒体进程。它们仍在同一台应用服务器中，由一个 Compose 项目管理。PBX 控制账本不能被多个网关 Uvicorn worker 共享。

1. 按 `host.env.example` 为每台准备节点 ID、私网 IP、同一组不可变镜像（新增 API_PROXY_IMAGE）和配置文件路径；另生成本机专用独立 MEDIA_RPC_TOKEN，至少 32 字符，保存在不入 Git 的 host.env。原项目数据目录、容器和卷不覆盖。
2. 四台使用相同的 `nodes.json`，参考 `nodes.example.json`。每个节点设置独立地址与 ID；`routes` 为明确的 `tenant_id:line_id` 授权范围，默认线路为 `tenant_id:0`。该范围还要与独立审批的网关 `voice-routes.json` 一致。
3. 后端、网关、Agent、录音适配器分别使用自己的 env 文件；`backend.env.example` 仅列出拓扑差异，生产必填密钥、允许号码前缀、录音和模型配置继续按 `.env.example` 补全。实际密钥不要提交到 Git。
4. 数据库 API/worker 连接托管主库；迁移使用直连主库账户。TLS 证书按托管服务要求挂载。Redis 使用受认证的托管连接，四台必须连接同一数据库编号。
5. 在指定的单个发布进程中执行 `python -m app.schema_bootstrap` 建立或补齐结构，再执行 `python -m app.migration_runner` 校验并应用版本化迁移，其中包括 `backend/migrations/postgresql/20260907_compact_cluster.sql`。新迁移仅新增节点表、通话归属字段和索引。先备份，在预生产验证迁移过程；生产 `AUTO_MIGRATE=false`，不允许四台启动时自行改表。
6. 每台私网仅开放必要端口给负载均衡、其他应用节点与批准的通信网络。ESL、API 8010–8013 和媒体 RPC/PCM 8101–8104 仅本机访问。8000 代理及 8002 控制器监听全部网卡，必须由宿主机防火墙限定私网负载均衡与授权节点；媒体不会经过随机负载均衡。外网不直接暴露 API/网关管理接口。

```sh
# 在每台宿主机执行；env 文件只列路径与版本，服务密钥放各自文件。
docker compose --env-file /etc/ai-outbound/host.env -f docker-compose.compact.yml config --quiet
docker compose --env-file /etc/ai-outbound/host.env -f docker-compose.compact.yml up -d

# 一处查看四个网关身份、就绪状态与容量。
python scripts/compact-fleet.py status --roster /etc/ai-outbound/nodes.json

# 升级前仅排空一个节点，等持久通话和待发事件都清零。
python scripts/compact-fleet.py drain --node node-1 --roster /etc/ai-outbound/nodes.json \
  --admin-token-file /secure/voice-admin-token --wait-seconds 600
```

排空命令超时或失败时不可继续停止该媒体节点。停止成功后再更新该节点；待 `/readyz` 恢复、身份和容量正确、控制面探测重新纳入后，才排空下一台。脚本不自动 SSH 发布，不会替用户停止生产服务。更新网关前必须执行排空；Compose 的 `stop_grace_period` 不能替代排空。API/任务 worker 可滚动重启，已经领取的任务在退出时先完成，进程硬中断则通过任务租约恢复。

### 通信与坐席配置边界

FreeSWITCH 镜像、音频模块、SIP 中继、录音目录及坐席 WebRTC/TLS/ICE 配置要使用已联调版本。模板复用既有 FreeSWITCH 配置挂载方式，不生成运营商参数或虚构证书。`sip-edge` 是可选配置入口，可在 node-1/node-2 上运行；启用前必须提供自己的不可变 OpenSIPS 镜像及合格配置。模板没有声称自动配置好了 SIP 入口与跨 PBX 坐席注册路由。

当前 `user/agent@domain` 转人工必须能定位真实注册的坐席，不能让随机 SIP 负载均衡把坐席注册放在一个 PBX，而客户通话在另一个 PBX 后仍沿用本地用户查找。完整上线前要完成统一注册路由或明确的跨 PBX 分机路由联调。已有通话命令的 HTTP 归属已在代码中处理，坐席 SIP/RTP 的真实路由是独立验收项。

节点地址在仍有活跃/未知通话时不得更换或复用。修改线路的通用网关 URL 不会把已分配通话移到新节点；播放、打断、挂断和转人工使用通话保存的地址及轮次。

## 资源与连接预算

模板对容器设置 CPU、内存、进程数、文件描述符与日志轮转上限。当前非可选容器 CPU 限额合计 23、内存限额合计约 29.25 GiB；限额不是预留或实测用量。原 16 vCPU/32GB 不能继续作为 200 路保证规格，必须在目标宿主机运行所有服务量测余量后确定。CPU 上限总和不是 CPU 预留保证；还需给内核、中断、磁盘和监控留余量。实测资源不足时调整规格/限额，不以单独媒体进程测试代替合并部署验收。

每个 API 进程 4 个 PostgreSQL 连接、两个 AI worker 各 3 个、后台 worker 2 个，overflow 为 0：每台最多 `4×4+2×3+2=24`，四台合计 96；另预留迁移、运维、健康采样连接。API 总执行 4、回调 3、普通请求 1，每进程保留 8 个等待、最长 50ms。增加模型名额不会增加数据库连接池预算。

监控分别抓取节点 8000 的 `/internal/metrics/api/1` 至 `/internal/metrics/api/4`，使用原后端 metrics 凭据；不能只抓随机分流的 `/metrics`。媒体进程指标由控制器聚合，AI worker 心跳保存在各自容器 `/tmp/ai-worker-health.json`。

四个独立 API 端口由本机 NGINX `least_conn` 按请求分流，关闭代理重试；原始签名正文透传。回调超时必须由发送者带原 event_id 重试，不可认定业务未提交。实际隔离结果见[本轮实施验收](../../docs/reviews/20260907-node200-implementation.md)。

两个 AI worker 每个最多 128 个异步任务，各有 2 个数据库工作线程和 2 个动作工作线程；长模型等待不占这些线程。后台 role=background 排除 ai_turn，避免旧 64 线程通道继续抢 AI 任务。Agent 池改为 256 个连接、128 个 keepalive，可按实测收紧。每秒检查一次陈旧模型结果并取消等待，最终结果应用仍做轮次和租约检查。动作阶段尚使用小线程池，真实慢 PBX/短信仍会占用动作槽，必须在混合验收中量测。

四个媒体进程各 50 个名额；控制器先将 call/attempt/worker/epoch/session/token 写入本地 FULL 账本，再创建会话。FreeSWITCH 直接连接指定媒体进程。媒体回调必须得到控制器持久确认，播放按同一语音代次去重。进程重启不会迁移原有音频；其未知名额保持到确认关闭或 epoch 变化，PBX 名额另由原安全账本核对。

更新时必须先排空该物理节点的通话与回调，再整体更新该节点的控制器和四个媒体进程。不得带活跃会话修改 worker ID、端口、容量或共享 token。异步 AI worker 有独立本地心跳健康检查；媒体 ready 由控制器对四个 worker 进行带凭据校验。`docker compose config` 会展开凭据，不要将完整结果提交或分享。

`MAX_CONCURRENT_CALLS=500` 仅作为未保存租户容量策略时的默认值；现有租户及任务的已保存限额不会被覆盖。全局上限为 500，节点上限为 200，节点预留/拨号中也占名额。单台满载不再接新电话，可选择其他有余量且健康的授权节点；所有节点满载则保留待拨任务。

## 验收门槛

- 单节点 20→50→100→150→200 路真实音频与云 AI，每档至少 30 分钟；200 路 8 小时，另做 24 小时混合负载。
- 200 个同步客户终句、第 201 路准入、客户插话、转人工、双声道录音、导出与后台任务同时运行。
- 四节点 500 路长稳；计划排空一台后可按 167/167/166 承载。崩溃会影响故障节点已有通话；先对账，再补入新通话，不承诺透明迁移。
- 不重复拨号、不超额接入、不串租户、无旧轮次操作、无事件/任务丢失。真实运营商接入、音质、坐席真机和生产发布单独报告。

## PBX 配置预检

不要把仓库只供 20 路本机联调的 FreeSWITCH XML 当生产配置。对运营提供的已展开 core XML 执行：

```sh
python scripts/check-node200-pbx.py --expanded-core-xml /secure/expanded-freeswitch-core.xml \
  --calls 200 --legs-per-call 2 --spare-sessions 100 --cps 10
```

这里 10 CPS 是示例工作负载。该预检要求 500 PBX 会话名额、对应 RTP/RTCP 端口及腿创建速率预算；它不修改任何 PBX 参数，也不验证运行时生效状态、音质或真实通话。实际转人工腿数、模块、编解码和服务器参数仍须读取运行环境核对。
