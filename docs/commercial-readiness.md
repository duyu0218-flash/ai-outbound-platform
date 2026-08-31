# 商用代码侧加固

本文件只描述仓库内已经实现的商用加固。运营商资质、SIP Trunk、真实号码、
FreeSWITCH非核心媒体模块、云厂商账号、域名证书和真机验收仍须在目标环境完成。

## 混合语音灰度

生产默认继续使用 `VOICE_AI_PIPELINE=legacy`。需要灰度时，语音网关设置为：

```env
VOICE_AI_PIPELINE=hybrid
```

管理端“AI与语音”配置可设置租户默认流水线和Pipecat灰度比例；活动可选择继承、
强制Legacy或强制Pipecat。平台使用通话ID的稳定哈希选择灰度组，并把最终结果写入
`callsession.voice_ai_pipeline`，所以重试、Worker切换或进程重启不会改变同一通电话的链路。

网关处于非hybrid模式时，如果控制面请求了另一条流水线，会明确拒绝拨号，避免审计记录
与实际媒体链不一致。

## 独立任务Worker

Compose中的 `task-worker` 运行 `python -m app.worker`，负责待拨任务、重试、AI持久任务、
业务回调、录音托管入库、录音删除和保留期清理。Control API设置 `SCHEDULER_ENABLED=false`，不再随API
Worker数量重复启动调度器。Worker通过Redis leader lock串行执行，并写入带TTL的心跳；
容器健康检查同时验证数据库、Redis和调度心跳。

## 登录安全和令牌撤销

- 连续失败次数和锁定时间由 `AUTH_MAX_FAILED_ATTEMPTS`、`AUTH_LOCKOUT_SECONDS` 控制；
- 登录成功自动清除失败记录；
- 管理员可以在用户管理页解锁；
- 退出、密码重置、账号禁用和人工解锁都会增加 `token_version`，旧JWT立即失效；
- 登录接口对密码错误和锁定账号保持相同响应，避免用户名枚举。

## 监控

Control API和Voice Gateway均提供需要Bearer `METRICS_TOKEN`的 `/metrics`。核心指标包括：

- 各通话状态和实际语音流水线数量；
- 持久任务状态、锁定账号、录音入库与删除失败；
- Voice Gateway readiness、活动绑定及Pipecat会话数。

Prometheus告警基准位于 `deploy/prometheus/alerts.yml`。生产环境必须限制指标端点网络访问，
并使用至少24位随机令牌。

## 录音托管存储

设置 `RECORDING_INGEST_ENDPOINT` 后，录音回调不会只保留运营商临时URL，而会创建可重试的
`recording_ingest` 任务，请求内部存储适配器复制文件。适配器必须返回平台长期保存的
`storage_uri`，可同时返回SHA-256；重试耗尽时资产状态为 `ingestion_failed` 并进入监控。
生产启用录音保留策略时，启动检查要求同时配置入库端点和服务令牌。

## 滚动发布排空

Voice Gateway提供受服务令牌保护的 `POST /v1/admin/drain?enabled=true`。进入排空后，健康接口
仍可查看进程，但readyz返回503并拒绝新拨号；已有通话的播报、转接和挂断接口保持工作。
当health中的活动通话及Pipecat会话都为0后再停止实例。Compose为网关预留5分钟停止宽限期。

## PostgreSQL备份恢复演练

```bash
BACKUP_DATABASE_URL="$DATABASE_URL" BACKUP_DIR=/secure/backups \
  scripts/backup-postgres.sh

BACKUP_ARCHIVE=/secure/backups/ai-outbound-YYYYMMDDTHHMMSSZ.dump \
  scripts/verify-postgres-backup.sh

RESTORE_DATABASE_URL=postgresql://user:pass@host/restore_ai_outbound \
RESTORE_ARCHIVE=/secure/backups/ai-outbound-YYYYMMDDTHHMMSSZ.dump \
RESTORE_CONFIRM=RESTORE_TO_DISPOSABLE_DATABASE \
  scripts/restore-postgres-drill.sh
```

恢复脚本只接受以 `restore_` 开头的数据库，避免把日常演练误指向生产库。正式灾难恢复切换
仍须由运维按已批准的RTO/RPO、DNS和应用停写流程执行。
