# 第一批可直接部署功能

本批交付解决三个仓库内可闭环的基础问题：统一监控、录音托管、受控SIP线路验收。它不替代运营商、
真实号码、生产证书、告警接收账号和目标环境容量验收。

开源许可证已记录在 `compatibility-matrix.toml`：SeaweedFS、Prometheus、Alertmanager为Apache-2.0，
Grafana为AGPL-3.0-only，SIPp为GPL-2.0-or-later。正式对外分发镜像、修改并提供网络服务或嵌入商业发行版前，
仍需由法务按实际交付方式复核义务；本仓库没有把这些组件代码复制进业务服务。

## 1. 入口与状态清单

| 能力 | 入口 | 正常状态 | 鉴权/限制 |
|---|---|---|---|
| Control API指标 | `http://127.0.0.1:8000/metrics` | HTTP 200 | Bearer指标令牌 |
| Voice Gateway指标 | 容器网络 `voice-gateway:8002/metrics` | HTTP 200 | Bearer指标令牌 |
| 录音适配器 | `http://127.0.0.1:8003/readyz` | `status=ready` | 数据接口使用服务令牌 |
| Prometheus | `http://127.0.0.1:9090` | 三个业务target均为up | 默认仅绑定本机 |
| Alertmanager | `http://127.0.0.1:9093` | ready | 默认仅本机且不外发通知 |
| Grafana | `http://127.0.0.1:3000` | 预置商用就绪大盘 | admin + secret文件密码 |
| SIPp | `scripts/run-sipp-acceptance.sh` | 结果写入 `artifacts/sipp/` | 必须显式确认测试目标 |

Grafana、Prometheus和Alertmanager是独立运维页面，没有业务表单或数据保存动作；其返回路径由浏览器负责。
业务后台登录、退出、录入、保存和关键链路仍按 `docs/operator-manual.md` 与
`docs/production-acceptance.md` 验收。

## 2. 一键启动开发/测试栈

```bash
cp .env.example .env
./scripts/bootstrap-deployment-secrets.sh
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.observability.yml ps
```

同一主机并行部署测试/预发布时，必须同时指定独立Compose项目名、容器名前缀和主机端口，例如：

```bash
COMPOSE_CONTAINER_PREFIX=ai-outbound-staging \
CONTROL_API_HOST_PORT=18000 RECORDING_ADAPTER_HOST_PORT=18003 SEAWEEDFS_S3_HOST_PORT=18333 \
PROMETHEUS_HOST_PORT=19090 ALERTMANAGER_HOST_PORT=19093 GRAFANA_HOST_PORT=13000 \
docker compose -p ai-outbound-staging -f docker-compose.yml -f docker-compose.observability.yml up -d --build
```

读取Grafana初始密码：

```bash
cat .secrets/grafana_admin_password
```

指标令牌只保存在 `.secrets/metrics_token`，Compose将其只读挂载到Control API、Voice Gateway、
Recording Adapter和Prometheus。`.secrets/` 已被Git忽略。

停止并保留数据：

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml down
```

只有在确认删除本地数据库、录音和监控历史时，才额外使用 `--volumes`。

## 3. 录音托管

录音回调到达后，后台将任务写入持久化outbox。Worker调用Recording Adapter，适配器从白名单来源流式下载，
校验内容类型和最大体积，计算SHA-256，再写入：

```text
s3://<bucket>/recordings/tenant-<tenant_id>/call-<call_id>/asset-<asset_id>.<ext>
```

同一资产重试写入相同对象键。删除接口同时校验bucket、前缀和tenant，不能借此删除其他租户对象。
该适配器只确认平台托管对象的删除；如果资产尚未入库、只有运营商录音ID或临时URL，它会明确失败并进入
死信/告警，不会把“未调用运营商删除API”误报为成功。运营商侧原始录音的保留和删除仍需在供应商合同、
控制台策略或单独的供应商删除适配器中完成。

生产至少需要替换：

- `RECORDING_STORAGE_SERVICE_TOKEN`、`RECORDING_S3_ACCESS_KEY_ID`、`RECORDING_S3_SECRET_ACCESS_KEY`；
- `RECORDING_SOURCE_ALLOWED_HOSTS` 为运营商实际录音下载域名；
- `RECORDING_SOURCE_REQUIRE_HTTPS=true`；
- `SEAWEEDFS_IMAGE` 为验收镜像的不可变digest；
- 对 `recording_data` 建立加密、异地备份和恢复演练，并按数据驻留规则确定部署区域。

当前适配器不跟随下载重定向，避免白名单域名把请求导向内网地址。如果运营商使用重定向，应在适配器前增加
已审核的固定下载代理，或二次开发为“每跳重新校验域名和协议”。

## 4. Prometheus、Grafana和告警

预置大盘 `AI Outbound Commercial Readiness` 展示网关就绪、活跃会话、各语音流水线通话量、持久任务和录音失败。
默认Alertmanager配置仅保留网页内告警，不向外部发送，避免把示例地址误当生产通知。

生产应复制 `deploy/alertmanager/alertmanager.yml` 到受保护路径，配置企业Webhook、邮件或值班系统，然后设置：

```bash
ALERTMANAGER_CONFIG_FILE=/secure/alertmanager.yml \
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d
```

正式验收必须主动触发一条测试告警，确认通知到达、恢复通知、值班升级和静默权限全部有效。

## 5. SIPp受控验收

SIPp只用于明确批准的测试SIP目标。建议在与FreeSWITCH同网段的Linux测试机运行；Docker Desktop的host网络
语义与Linux不同，不作为真实线路验收环境。当前脚本要求测试机使用明确的IPv4本地地址；`SIPP_TRANSPORT`
可选 `udp` 或 `tcp`，脚本会转换为SIPp要求的 `u1`、`t1` 模式。TLS需要现场证书、私钥和对端校验配置，
本批脚本不会把缺少证书配置的测试误报为TLS验收通过。

先执行无呼叫建立的OPTIONS可达性：

```bash
SIPP_CONFIRM_RUN=RUN_CONTROLLED_SIPP_TEST \
SIPP_TARGET=10.0.0.20 \
SIPP_LOCAL_IP=10.0.0.30 \
SIPP_SERVICE=1000 \
SIPP_SCENARIO=options \
./scripts/run-sipp-acceptance.sh
```

再在受控测试号码上执行一通基础呼叫：

```bash
SIPP_CONFIRM_RUN=RUN_CONTROLLED_SIPP_TEST \
SIPP_TARGET=10.0.0.20 \
SIPP_LOCAL_IP=10.0.0.30 \
SIPP_SERVICE=1000 \
SIPP_SCENARIO=uac-basic \
SIPP_MAX_CALLS=1 \
SIPP_CONCURRENCY=1 \
./scripts/run-sipp-acceptance.sh
```

逐级容量测试时分别设置10%、25%、50%、100%候选并发，保留每次 `statistics.csv` 与 `errors.log`。
脚本不会自动判断业务成功、录音、ASR、TTS和转人工，仍需与平台事件及真机结果对账。

## 6. 生产门禁

以下各项不能互相替代：

1. 版本矩阵和CI通过；
2. 测试环境Compose全部ready，Prometheus targets为up；
3. SeaweedFS备份恢复演练通过；
4. Alertmanager真实通知链路通过；
5. SIPp在批准目标完成分级容量测试；
6. 真实号码完成双向语音、录音、AI插话、转人工和挂机验收；
7. 灰度发布和回滚演练通过；
8. 生产负责人批准后，才可替换生产镜像digest并发布。
