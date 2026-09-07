# 四台应用服务器部署

当前部署目标为：四台独立 Linux 宿主机，每台包含 FreeSWITCH、网关、Pipecat、API、AI Agent 和后台任务；PostgreSQL、Redis、负载均衡、录音对象存储使用托管服务。单节点 200 路、全平台 500 路均为待真实媒体验收的目标。

## 配置与发布入口

使用仓库根目录的 `docker-compose.compact.yml`，它是独立模板，不与原 `docker-compose.yml` 叠加。模板中没有 PostgreSQL、Redis、SeaweedFS 容器；管理网页由 control-api 同时提供。每台运行两个 API 进程、一个任务 worker、一个媒体网关进程，媒体账本不能被多个 Uvicorn worker 共享。

1. 按 `host.env.example` 为每台准备节点 ID、私网 IP、同一组不可变镜像和配置文件路径。原项目数据目录、容器和卷不覆盖。
2. 四台使用相同的 `nodes.json`，参考 `nodes.example.json`。每个节点设置独立地址与 ID；`routes` 为明确的 `tenant_id:line_id` 授权范围，默认线路为 `tenant_id:0`。该范围还要与独立审批的网关 `voice-routes.json` 一致。
3. 后端、网关、Agent、录音适配器分别使用自己的 env 文件；`backend.env.example` 仅列出拓扑差异，生产必填密钥、允许号码前缀、录音和模型配置继续按 `.env.example` 补全。实际密钥不要提交到 Git。
4. 数据库 API/worker 连接托管主库；迁移使用直连主库账户。TLS 证书按托管服务要求挂载。Redis 使用受认证的托管连接，四台必须连接同一数据库编号。
5. 在指定的单个发布进程中执行 `python -m app.schema_bootstrap` 建立或补齐结构，再执行 `python -m app.migration_runner` 校验并应用版本化迁移，其中包括 `backend/migrations/postgresql/20260907_compact_cluster.sql`。新迁移仅新增节点表、通话归属字段和索引。先备份，在预生产验证迁移过程；生产 `AUTO_MIGRATE=false`，不允许四台启动时自行改表。
6. 每台私网仅开放必要端口给负载均衡、其他应用节点与批准的通信网络。ESL 仅本机访问。外网不直接暴露 API/网关管理接口。

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

模板对容器设置 CPU、内存、进程数、文件描述符与日志轮转上限。单台起测候选为 16 vCPU/32GB，必须在所有服务同时运行时验证 200 路。CPU 上限总和不是 CPU 预留保证；还需给内核、中断、磁盘和监控留余量。实测资源不足时调整规格/限额，不以单独媒体进程测试代替合并部署验收。

每个 API 进程 6 个 PostgreSQL 连接、每个 worker 6 个，overflow 为 0：每台最多 `2×6+6=18`，四台合计 72；另外预留迁移、运维和采样连接。增加 API 进程或 worker 前重新计算总预算。初期报表共用主库，导出与后台并发限制必须保留；报表规模增长时根据主库观测值再引入读库，不预先增加服务器。

每台 AI 执行槽 64、业务回调 8、录音 2、质检 2，四台 AI 总槽 256，少一台为 192。它们是初始有界执行预算，无法单凭槽数证明 125 轮/秒或 500 个同步终句时延。必须结合平均任务占用时间、模型 QPS/TPM、实际负载测试调整。CPS、日次数、费用、租户、任务与线路限额继续独立生效。

`MAX_CONCURRENT_CALLS=500` 仅作为未保存租户容量策略时的默认值；现有租户及任务的已保存限额不会被覆盖。全局上限为 500，节点上限为 200，节点预留/拨号中也占名额。单台满载不再接新电话，可选择其他有余量且健康的授权节点；所有节点满载则保留待拨任务。

## 验收门槛

- 单节点 20→50→100→150→200 路真实音频与云 AI，每档至少 30 分钟；200 路 8 小时，另做 24 小时混合负载。
- 200 个同步客户终句、第 201 路准入、客户插话、转人工、双声道录音、导出与后台任务同时运行。
- 四节点 500 路长稳；计划排空一台后可按 167/167/166 承载。崩溃会影响故障节点已有通话；先对账，再补入新通话，不承诺透明迁移。
- 不重复拨号、不超额接入、不串租户、无旧轮次操作、无事件/任务丢失。真实运营商接入、音质、坐席真机和生产发布单独报告。
