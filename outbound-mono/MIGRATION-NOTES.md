# 单体转译迁移记录

> 2026-10-20 收尾：以最新存储、锁接口和显式回退裁决为准。此前 311 条及 18 组浏览器结果为历史快照，不代表本轮改动验收；本轮结果单独记录在 verification/closeout-20261020/。源工程与既有验收资料保持原位，真实线路、真机及媒体容量不在本轮已验证范围。


## 执行基准与当前进度

以用户最后给定的《执行基准》为最终裁决。阶段 0 技术验证已通过并锁定，继续功能等价转译，不再重复依赖扫描或版本升级论证。所有新产物位于 ./outbound-mono/；./backend-java/、./services-java/、./frontend/ 保持原样。

目标为一个 jar、一个 JVM，API、AI、语音控制、录音、Java 实时媒体同进程。外部 FreeSWITCH 及 MySQL、Redis、对象存储是基础设施；不部署独立网关或第二媒体进程。

阶段 0 的既有结论：无 javax.servlet / javax.annotation-api，保留 javax.validation 精确例外；mvn verify、独立 Enforcer、6 个测试、fat jar 启动及 GET /healthz 200 均已通过。Boot 由 3.5.12 到 3.5.16 属已授权的最新 3.5.x patch 范围。版本不再调整。全量实测组合如下。

| 组件 | 实测版本 |
|---|---|
| JDK | 17.0.20.1 |
| Maven | 3.9.11 |
| Spring Boot | 3.5.16 |
| Shiro Jakarta | 2.0.6 |
| MyBatis starter | 3.0.4 |
| MyBatis core | 3.5.17 |
| PageHelper starter | 2.1.0 |
| PageHelper core | 6.1.0 |
| Druid Boot3 | 1.2.28 |
| Spring AI | 1.0.3 |
| springdoc | 2.8.16 |
| Flyway | 11.7.2 |
| MySQL server | 8.4.11 |
| MySQL Connector/J | 9.7.0 |
| Redis server | 7.4.11 |
| Lettuce | 6.6.0.RELEASE |
| SeaweedFS | 4.44 (356373869) |
| AWS SDK S3 | 2.29.52 |
| java-jwt | 4.5.0 |
| Micrometer | 1.15.12 |
| Jackson | 2.21.4 |
| javax.validation API (approved) | 1.1.0.Final |
| jakarta.validation API | 3.0.2 |
| Testcontainers | 1.21.4 |
| JUnit Jupiter | 5.12.2 |
| Tomcat | 10.1.55 |

## 原行为与转译边界

- 原始基线见 ./outbound-mono/source-baseline.json：126 个源文件、34 张业务表、379 个字段、108 个 HTTP 操作、86 个路径。
- 输入、输出、字段、错误码、状态机及幂等效果均以原代码和 OpenAPI 为准。保留原实现显式人工重拨、转人工失败返回等待、活动并发先于节点选择等行为；此前与此冲突的裁决已被最终执行基准覆盖。
- 不新增原系统没有的接口或审计功能，不优化、不扩展重构、不修其他缺陷。自由字符串状态维持原大小写规则，不擅自收紧为新枚举。
- 按数据层、认证与租户、客户与合规、话术与知识、外呼与通话、AI、转人工与坐席、质检与报表、前端、语音合并、收尾逐域推进。
- 收尾时才执行一次 dependency-check，生成非阻断 HTML 报告；不配置扫描门禁。旧审查临时产物及结论已清除。Enforcer 禁用规则与 javax.validation 精确例外保留。

## 行为差异

原实现无 jti，本次不引入；JWT 保持 sub/uid/tenant_id/role/tv/exp 六字段，吊销依赖 token_version，没有新增 JWT 行为偏离。

当前数据层未引入业务行为差异。PG → MySQL 的表名和存储类型按已批准映射规则替换，详见下节。

唯一已授权并已在本域实施的偏离是 API Key 管理员越权修复：原 Identity.admin() 对 user 为空直接放行；新实现须区分 JWT 用户与 API Key，API Key 必须显式包含接口要求的权限码，缺少则 403。权限来源仍为 TENANT_API_SCOPES_JSON。JWT 用户原路径保持原样。范围只限管理员级接口及其权限检查，不扩展其他行为变化。所有已转译管理员级接口均显式声明所需权限码；权限来源与 JWT 用户路径不变。

## 依赖例外

2026-10-19 按人工要求执行 mvn -B dependency:tree -Dincludes=javax.validation:*，成功。完整引入路径如下（不是其他模块引入）：

```text
com.duozhi:outbound-mono:0.0.1-SNAPSHOT
→ org.springframework.ai:spring-ai-starter-model-openai:1.0.3
→ org.springframework.ai:spring-ai-autoconfigure-model-chat-client:1.0.3
→ org.springframework.ai:spring-ai-client-chat:1.0.3
→ com.fasterxml.jackson.module:jackson-module-jsonSchema:2.21.4
→ javax.validation:validation-api:1.1.0.Final (compile)
```

证据为 ./outbound-mono/verification/validation-dependency-tree.txt；完整依赖树未检出 javax.servlet:* 或 javax.annotation:javax.annotation-api，符合人工指定的应用豁免前提。

人工给定的判定依据：

- javax.validation:validation-api 是纯注解 API，无实现类，不参与 Servlet 容器生命周期。
- 它与 jakarta.validation:jakarta.validation-api 包名不同（javax.validation.* / jakarta.validation.*），可安全共存，不构成 Jakarta EE 10 冲突。
- 本次禁用 javax.* 的真实目标是 javax.servlet / javax.annotation-api（即会与 jakarta 容器/注解处理冲突的那两类）。javax.validation 不在该风险面内。
- 排除它会破坏上游 JSON Schema 生成器对校验注解的识别，导致 function calling / 结构化输出的 schema 静默丢失 required / maxLength 等约束，属于“不报错但功能变弱”，风险高于保留。

技术边界：以上“无实现类”按“不含 Bean Validation 提供者/执行引擎”理解，API JAR 本身仍包含接口、注解、异常、引导及辅助类，并非只有注解 class。不同包名允许两套 API 共存；不代表 Jakarta Validator 会自动识别 javax 注解。本阶段测试直接运行 Jackson 的 ValidationSchemaFactoryWrapper，保护该上游模块的 required / maxLength；未来 Spring AI 结构化输出与工具调用的实际 DTO 路径仍须各自验证，不能把保留 JAR 等同于所有路径已通过。

## 已批准例外

| 项目 | 内容 |
|---|---|
| 坐标 | javax.validation:validation-api |
| 精确版本 | 1.1.0.Final |
| 类型/范围 | jar / compile |
| 直接引入者 | com.fasterxml.jackson.module:jackson-module-jsonSchema:2.21.4 |
| 完整路径 | 见上方“依赖例外” |
| 为何可保留 | 无 Servlet 生命周期及 Bean Validation 执行引擎，包名与 Jakarta 分离；保留上游注解解析依赖 |
| 批准人 | 人工（当前项目用户） |
| 复核日期 | 2026-10-19 |
| 规则 | 保持原 excludes 禁止列表不动；仅新增 includes 中的 javax.validation:validation-api:[1.1.0.Final]:jar:compile |
| 版本漂移 | 后续版本超出本豁免时构建失败，须重新复核 |

Maven Enforcer 的 excludes 表示禁止，includes 才是禁止规则中的例外，因此按真实插件语义实现人工的“精确豁免”意图；不照抄会再次禁止该 API 的示例。官方依据：https://maven.apache.org/enforcer/enforcer-rules/bannedDependencies.html 。没有排除任何传递依赖，没有放宽 javax.* 通配，没有删除原规则，没有调整组件版本。

## 数据层转译

本域产物：./outbound-mono/src/main/resources/db/migration/V1__source_equivalent_schema.sql，34 个实体、34 个 Mapper 接口及 34 个 XML、6 个原生枚举对应的 Java enum、UUID TypeHandler。逐表字段映射见 ./outbound-mono/schema-translation.json；源声明快照见 ./outbound-mono/src/test/resources/contracts/data-layer-baseline.json。

- 34 张表与 379 个字段均保留；保留主键、外键、唯一键和既有索引。user 改名 sys_user。Flyway 的历史表属于迁移工具元数据，不计入 34 张业务表。
- PostgreSQL 原生枚举使用 VARCHAR(32) 与 CHECK，数据库保留大写标签，Java JSON 值保留原小写值；只接受原有大写标签或小写值，拒绝混合大小写。原自由字符串状态不改。
- UUID 使用 CHAR(36)，UTC 无时区时间使用 DATETIME(3)，FLOAT 使用 DOUBLE。Java 默认值保留源 tables.json 的值及 UUID/UTC 时间工厂。
- JSON 保留 LONGTEXT 原文，不交由 MySQL JSON 重排键序。大于 4000 字符的原 VARCHAR 使用 LONGTEXT 加原长度 CHECK（如 100000 字符转写、50000 字符知识正文）。其他无界非唯一文本使用 LONGTEXT。
- 无界唯一索引字段按原可达输入边界映射：租户 code 为内部生成 java-dev- 加整数，VARCHAR(64)；username 原接口上限 200；contact/phonesuppression.phone 的原 Contacts.phone 只接受 6–15 位数字，VARCHAR(32)；provider_event_key 为 SHA-256 十六进制 64 位。未新增接口校验。证据见 schema-translation.json。
- 数据库默认排序仍为 utf8mb4_0900_ai_ci；文本列使用 utf8mb4_0900_bin（NO PAD）保留 PG 字符串区分大小写的等值/唯一比较语义。LONGTEXT 的非唯一普通索引使用前缀，不截断存储值。原非唯一部分索引转换为全量普通索引，不改变筛选结果；nullable campaign_contact_key 的普通唯一键保留多个 NULL 的行为。
- Mapper 使用固定表名/列名和绑定参数。属于租户的表按 tenant_id 读取、更新、删除；campaigncontact、callevent、webhookeventingest 经原父对象确定归属。平台内部表保留内部调用边界。
- 本域 Mapper 是持久化基础，不直接发布为 CRUD API。后续业务域必须继续转译原查询筛选/排序、PATCH 合并、updated_at 自动刷新、错误码、锁和事务，不得将基础 Mapper 当作已完成的原业务接口。
- MyBatis 启用 call-setters-on-nulls，防止实体默认值覆盖数据库实际 NULL。业务事务使用 Spring 事务管理器；测试验证实际回滚，不用手动 SqlSession.rollback 替代 Spring 事务边界。

### 数据层实测结果

2026-10-19 使用锁定 JDK 17.0.20.1 / Maven 3.9.11 执行 mvn -B verify，通过。DataLayerIT 121 项（34 项 CRUD、34 项默认值/枚举、20 项唯一键、29 项外键及 4 项专项验证），加上既有 2 项单元测试和 4 项兼容性集成测试，全量 127 项通过，失败/错误/跳过均为 0。使用真实 MySQL 8.4 容器，Flyway V1 成功；原基线 126 个文件 SHA-256 复核无变化。

证据：./outbound-mono/verification/data-layer-verify.txt、./outbound-mono/verification/com.duozhi.outbound.DataLayerIT.txt、./outbound-mono/verification/data-layer-summary.json。测试数量不替代接口/手册功能完成度；当前仅 /healthz 计入接口已转译。

### 数据层验收与入口清单

| 入口/状态 | 本次范围 | 结果 |
|---|---|---|
| Flyway 空库迁移 | MySQL 8.4，34 张表、379 字段及约束 | 通过；证据见 data-layer-summary.json |
| 34 个 Mapper | 新增、自增/UUID 主键、逐字段读取、更新、可空值、分页、删除 | 通过；证据见 data-layer-summary.json |
| 租户/关联归属 | 错误租户读取不可见，更新/删除不命中 | 通过；证据见 data-layer-summary.json |
| 唯一键与外键 | 重复幂等键、缺失父对象拒绝；跨租户号码独立 | 通过；证据见 data-layer-summary.json |
| 默认值、大小写、长文本、JSON、事务 | 源默认值/枚举、10 万字符、JSON 原文、回滚 | 通过；证据见 data-layer-summary.json |
| HTTP /healthz | 原接口 200 与 status=ok | verify 中保留实测 |
| 本域业务接口 | 无新接口；不计作客户/认证等接口完成 | 未验证 |
| 页面、表单、登录/退出、返回路径 | Vue 前端尚未转译；认证 API 已验证 | 未验证 |
| 客户→话术→活动/单呼→接管→质检/报表 | 留待各业务域 mock 端到端验收 | 未验证 |

外呼项目不包含 AGENTS 通用模板中的电商“门店/房型→购物车→支付”链路；本次没有将任何 OpenAPI/手册功能判为不适用。

## 单进程媒体硬约束

1. 媒体专属线程池，不与 HTTP 或 Kafka 消费线程池共享；输入/输出队列有界，过期旧帧丢弃并计数，不能积压扩大延迟。
2. 音频缓冲使用可复用 DirectByteBuffer 堆外池，限制容量并明确归还/释放责任，避免每帧分配短命对象。
3. JDK 17 暂选 G1，-XX:+UseG1GC -XX:MaxGCPauseMillis=20。20ms 是目标而非保证；理由是维持已锁定 JDK 并控制暂停，若不达标再以同负载对比 ZGC。需要记录 GC p95/p99/max、帧间隔、端到端延迟、丢帧、堆外占用、HTTP/Kafka 压力影响及 CPU。

媒体压测与真机验证均未做，属于原阶段 6（当前业务域清单的“语音合并”域）的验收项；上面是硬约束，不代表已完成实现或音频质量验收。

## 交付状态与上线前置条件

- 源代码：数据层已新增；认证与用户管理 API 已转译；客户、外呼等业务和 Vue 页面仍待转译。
- 静态检查与开发环境测试：以 ./outbound-mono/verification/data-layer-summary.json 和测试报告为准。
- 测试环境：未发布；真机：未验证；生产：未发布。
- 当前限制：只有持久化基础与兼容性骨架；未验证完整外呼 mock 业务闭环、真实 SIP/RTP、云模型或真实媒体。
- 正式上线前置条件：全部接口和手册功能转译完成，各域契约和 mock 链路验收，全量测试及部署文档完成，另完成真实线路、目标环境与媒体验证。不能用本域测试替代整体验收。

## 租户条件例外（按原 schema 核实）

源 schema 无 tenant_id 列共 8 张，全部列于下方；3 张关联业务表仍经父对象限制租户，不免除归属检查。认证前置查询 B 的键仅用于身份定位，结果不能直接返回业务数据；后续唯一租户上下文为定位出的用户 tenant_id（API Key 则为服务器配置映射），前端 x-tenant-id 只作一致性校验。

| 表 / 类别 | Mapper 方法及调用边界 | 为何不能带本表 tenant_id |
|---|---|---|
| tenant / A | TenantMapper.findById/list/updateById/deleteById；平台内部持久化；当前未发布对应业务入口 | 源无该列；不新增租户列、不改变原内部表语义 |
| gatewaynode / A | GatewayNodeMapper.findById/list/updateById/deleteById；平台内部持久化；当前未发布对应业务入口 | 源无该列；不新增租户列、不改变原内部表语义 |
| callbackinboxpartition / A | CallbackInboxPartitionMapper.findById/list/updateById/deleteById；平台内部持久化；当前未发布对应业务入口 | 源无该列；不新增租户列、不改变原内部表语义 |
| callbackinbox / A | CallbackInboxMapper.findById/list/updateById/deleteById；平台内部持久化；当前未发布对应业务入口 | 源无该列；不新增租户列、不改变原内部表语义 |
| callbackinboxworker / A | CallbackInboxWorkerMapper.findById/list/updateById/deleteById；平台内部持久化；当前未发布对应业务入口 | 源无该列；不新增租户列、不改变原内部表语义 |
| campaigncontact / A | CampaignContactMapper.findById/list/updateById/deleteById；父 campaign/callsession 的 tenant_id 归属，保留条件 | 源无该列；通过父表条件实现隔离 |
| callevent / A | CallEventMapper.findById/list/updateById/deleteById；父 campaign/callsession 的 tenant_id 归属，保留条件 | 源无该列；通过父表条件实现隔离 |
| webhookeventingest / A | WebhookEventIngestMapper.findById/list/updateById/deleteById；父 campaign/callsession 的 tenant_id 归属，保留条件 | 源无该列；通过父表条件实现隔离 |

B 类依据：user.id 为 PRIMARY KEY，ix_user_username 为全局 UNIQUE(username)，tenant.id 为 PRIMARY KEY。AuthIdentityMapper.findByUsernameForLogin / findByIdForJwt / findTenantById 仅在认证入口定位身份和校验租户，均非业务数据读取接口；查询结果中的 tenant_id 决定租户上下文。当前调用点与依据见 TENANT-QUERY-EXCEPTIONS.md。

### A 类原文：tenant

```sql
CREATE TABLE tenant (
	id SERIAL NOT NULL,
	name VARCHAR NOT NULL,
	code VARCHAR NOT NULL,
	enabled BOOLEAN NOT NULL,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id)
);
```

### A 类原文：gatewaynode

```sql
CREATE TABLE gatewaynode (
	id VARCHAR(64) NOT NULL,
	endpoint VARCHAR(512) NOT NULL,
	capacity INTEGER NOT NULL,
	ready BOOLEAN NOT NULL,
	checked_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	next_dial_at TIMESTAMP WITHOUT TIME ZONE,
	PRIMARY KEY (id)
);
```

### A 类原文：callbackinboxpartition

```sql
CREATE TABLE callbackinboxpartition (
	id SERIAL NOT NULL,
	pending_count INTEGER NOT NULL,
	pending_bytes INTEGER NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT ck_callback_capacity CHECK (pending_count >= 0 AND pending_bytes >= 0)
);
```

### A 类原文：callbackinbox

```sql
CREATE TABLE callbackinbox (
	id BIGSERIAL NOT NULL,
	receipt_key VARCHAR(64) NOT NULL,
	partition_id INTEGER NOT NULL,
	call_id UUID NOT NULL,
	kind VARCHAR(32) NOT NULL,
	body_json VARCHAR NOT NULL,
	body_digest VARCHAR(64) NOT NULL,
	body_bytes INTEGER NOT NULL,
	state VARCHAR(16) NOT NULL,
	received_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	available_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	completed_at TIMESTAMP WITHOUT TIME ZONE,
	attempts INTEGER NOT NULL,
	error_type VARCHAR(128) NOT NULL,
	PRIMARY KEY (id),
	UNIQUE (receipt_key)
);
```

### A 类原文：callbackinboxworker

```sql
CREATE TABLE callbackinboxworker (
	id VARCHAR(64) NOT NULL,
	heartbeat_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	processed BIGINT NOT NULL,
	max_latency_ms FLOAT NOT NULL,
	PRIMARY KEY (id)
);
```

### A 类原文：campaigncontact

```sql
CREATE TABLE campaigncontact (
	id SERIAL NOT NULL,
	campaign_id INTEGER NOT NULL,
	contact_id INTEGER NOT NULL,
	contact_order INTEGER NOT NULL,
	is_active BOOLEAN NOT NULL,
	PRIMARY KEY (id),
	FOREIGN KEY(campaign_id) REFERENCES campaign (id),
	FOREIGN KEY(contact_id) REFERENCES contact (id)
);
```

### A 类原文：callevent

```sql
CREATE TABLE callevent (
	id SERIAL NOT NULL,
	call_session_id UUID NOT NULL,
	event_type VARCHAR(64) NOT NULL,
	source VARCHAR NOT NULL,
	payload VARCHAR NOT NULL,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	FOREIGN KEY(call_session_id) REFERENCES callsession (id)
);
```

### A 类原文：webhookeventingest

```sql
CREATE TABLE webhookeventingest (
	id SERIAL NOT NULL,
	call_session_id UUID NOT NULL,
	event_type VARCHAR NOT NULL,
	source VARCHAR NOT NULL,
	provider_event_key VARCHAR NOT NULL,
	repeat_count INTEGER NOT NULL,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	PRIMARY KEY (id),
	FOREIGN KEY(call_session_id) REFERENCES callsession (id)
);
```


## 租户条件机器检查与负向证明

集中白名单：./outbound-mono/src/test/resources/contracts/tenant-query-policy.json。构建检查：./outbound-mono/src/test/java/com/duozhi/outbound/TenantMapperPolicyTest.java，随 mvn test / verify 自动执行。白名单按完整 Mapper 方法名声明，原有 20 项只覆盖 5 张纯平台表；本域另显式新增 A 类租户锁 1 项、B 类认证定位 3 项，总计 24 项；新增全局方法必须显式改名单。3 张父对象归属表没有免除条件。B 类的 3 个认证定位方法已逐个声明，只允许 select，不能用于更新/删除。

C 类全工程统一采用直接 WHERE tenant_id 条件，更新/删除同样绑定 tenantId；3 张父表归属表用 EXISTS 中的父 tenant_id 条件。当前没有 C 类“先查后校验”的替代路径。已有 DataLayerIT 对每个租户 Mapper 实测错误租户 find=null、update/delete=0；本域另以真实 HTTP 验证租户 1 身份修改租户 2 用户返回 404，原记录未改；不以持久化结果冒充 HTTP 断言。

本域当前正向输出：XML=35，select/update/delete=145，带租户条件=121，明确例外=24，BUILD SUCCESS。

负向命令（仅使用临时目录中的完整 XML 副本，不改运行时源文件）：

```sh
mvn -B -Dtest=TenantMapperPolicyTest -DmapperPolicyDirectory=/private/tmp/outbound-tenant-negative test
```

只删除 ContactMapper.findById 中的 `AND tenant_id = #{tenantId,...}` 条件，结果退出码 1，BUILD FAILURE，准确报告：

```text
com.duozhi.outbound.mapper.ContactMapper.findById: missing WHERE tenant_id = #{tenantId} predicate
```

随后已用原 XML 还原临时副本；实际 ./outbound-mono/src/main/resources/mapper/ 从未被探针修改。完整日志见 ./outbound-mono/verification/tenant-policy-positive.txt 和 tenant-policy-negative.txt。逐方法 SQL 与条件清单见 ./outbound-mono/verification/mapper-tenant-conditions.md。


## 域 2：认证与租户

已转译 auth 的 login/me/logout/presence 与 admin/users 的 list/create/update/reset-password/unlock/disable，共 10 个原接口。Shiro Realm/JwtFilter 从数据库用户建立上下文；B 类前置查询只定位身份。JWT 六字段、过期边界、可选 nbf、tv 缺失默认 0、token_version 吊销、Authorization 优先及错误码均按原代码保留。

### 行为差异（最终裁决）

- 原实现无 jti，本次不引入，JWT 仍是 sub / uid / tenant_id / role / tv / exp；吊销依赖 token_version。这一项没有行为差异。
- 唯一批准的变化：Identity.admin 不再在 user=null 时放行；API Key 必须精确包含接口声明权限码。对当前用户管理接口，无 admin:users 返回 403，有则允许；JWT admin/agent 角色判断不变。原 /admin 前缀会对 API Key 统一 requireUser=401，本次按同一人工裁决统一改为显式权限判定，不能把旧前缀行为写成原来全部返回 200。真实旧 Identity 由 JDK 编译原只读源码执行，证实 null 用户原先通过；新版本无权限为 403。
- API Key 权限仍来自原 TENANT_API_SCOPES_JSON 的租户映射；没有新增配置项或权限管理接口。同一租户的兼容 API_KEY/UI_API_KEY 共享原租户权限配置。
- 其他业务语义未变。启动拒绝保留原环境边界：生产环境禁止 DEMO_USERS_ENABLED；开发仍允许原演示账号，CORS 通配在所有环境均拒绝，JWT_SECRET 长度与原生产弱值检查保留。数据库前缀要求按批准的 PG→MySQL 替换。

### 实现与证据

- Shiro 无状态片段在 src/main/java/com/duozhi/outbound/config/JwtSecurityConfiguration.java：sessionStorageEnabled=false、rememberMeManager=null、Session Cookie/URL rewriting/validation scheduler 全禁用、SubjectFactory 禁止 sessionCreation，所有链加 noSessionCreation。每条 HTTP 断言均检查无 Set-Cookie；客户端旧 Cookie 不会建立身份。
- 原 AUTH_MAX_FAILED_ATTEMPTS 默认 5、AUTH_LOCKOUT_SECONDS 默认 900（最小 30 秒）原样保留；失败正常返回以提交计数。保留成功后重置、锁定到期、旧密码升级与 agent 初始状态。
- 退出先清 ai-outbound:agent-media:<tenant>:<id>，保留 SIP credential，再原子 token_version+1/offline；生产 Redis 清理失败返回 503，不继续吊销。改角色、班组长、启停、改密、解锁同样保留原 tv 增量。
- 用户管理 SQL 全部直接 tenant_id；原用户名全局唯一索引仍保留，重复创建返回原 409 文案。PATCH/更新只更新原提供字段，不用整行覆盖撤销版本或其他并发字段。
- 关键列 collation 逐列判定、源字段名称差异与哈希/JSON 边界见 COLLATION-DECISIONS.md；完整 A/B 方法清单和调用点见 TENANT-QUERY-EXCEPTIONS.md。这两份清单是本节的逐项证据。
- 测试与输出：verification/auth-domain-summary.json、auth-domain-verify.txt，AuthHttpIT、IdentityCompatibilityTest、AuthStartupPolicyTest 的报告。完整原业务 mock 电话链路和 Vue 页面未验证；本域身份闭环独立于真实线路。

本域没有前端修改。源代码已修改，编译/本机 Docker 开发环境测试以本域汇总为准；测试/生产未发布、真机未验证，正式上线仍需其余全部域与真实目标环境验收。

## 本域租户例外完整清单

原 schema 无 tenant_id 的完整 8 表：tenant、gatewaynode、callbackinboxpartition、callbackinbox、callbackinboxworker、campaigncontact、callevent、webhookeventingest。上一轮人工初步清单列出 7 表，缺少的是 callbackinbox；其 CREATE TABLE 同样没有 tenant_id。

前 5 表为平台内部表；后 3 表虽无本表 tenant_id，仍保留通过 campaign/callsession 的 EXISTS tenant_id 条件，不加入无条件豁免。完整原 CREATE TABLE 原文见 verification/tenant-exception-source-evidence.md。

原 20 条方法豁免逐条如下，集中名单见 src/test/resources/contracts/tenant-query-policy.json；没有通配名单。

| Mapper 方法 | 所属表 | 豁免类别 | 依据 / 调用边界 |
|---|---|---|---|
| com.duozhi.outbound.mapper.TenantMapper.findById | tenant | A | 平台内部基础 Mapper；本域无直接 HTTP 暴露；无 tenant_id 的原文已逐表记录。 |
| com.duozhi.outbound.mapper.TenantMapper.list | tenant | A | 平台内部基础 Mapper；本域无直接 HTTP 暴露；无 tenant_id 的原文已逐表记录。 |
| com.duozhi.outbound.mapper.TenantMapper.updateById | tenant | A | 平台内部基础 Mapper；本域无直接 HTTP 暴露；无 tenant_id 的原文已逐表记录。 |
| com.duozhi.outbound.mapper.TenantMapper.deleteById | tenant | A | 平台内部基础 Mapper；本域无直接 HTTP 暴露；无 tenant_id 的原文已逐表记录。 |
| com.duozhi.outbound.mapper.GatewayNodeMapper.findById | gatewaynode | A | 平台内部基础 Mapper；本域无直接 HTTP 暴露；无 tenant_id 的原文已逐表记录。 |
| com.duozhi.outbound.mapper.GatewayNodeMapper.list | gatewaynode | A | 平台内部基础 Mapper；本域无直接 HTTP 暴露；无 tenant_id 的原文已逐表记录。 |
| com.duozhi.outbound.mapper.GatewayNodeMapper.updateById | gatewaynode | A | 平台内部基础 Mapper；本域无直接 HTTP 暴露；无 tenant_id 的原文已逐表记录。 |
| com.duozhi.outbound.mapper.GatewayNodeMapper.deleteById | gatewaynode | A | 平台内部基础 Mapper；本域无直接 HTTP 暴露；无 tenant_id 的原文已逐表记录。 |
| com.duozhi.outbound.mapper.CallbackInboxPartitionMapper.findById | callbackinboxpartition | A | 平台内部基础 Mapper；本域无直接 HTTP 暴露；无 tenant_id 的原文已逐表记录。 |
| com.duozhi.outbound.mapper.CallbackInboxPartitionMapper.list | callbackinboxpartition | A | 平台内部基础 Mapper；本域无直接 HTTP 暴露；无 tenant_id 的原文已逐表记录。 |
| com.duozhi.outbound.mapper.CallbackInboxPartitionMapper.updateById | callbackinboxpartition | A | 平台内部基础 Mapper；本域无直接 HTTP 暴露；无 tenant_id 的原文已逐表记录。 |
| com.duozhi.outbound.mapper.CallbackInboxPartitionMapper.deleteById | callbackinboxpartition | A | 平台内部基础 Mapper；本域无直接 HTTP 暴露；无 tenant_id 的原文已逐表记录。 |
| com.duozhi.outbound.mapper.CallbackInboxMapper.findById | callbackinbox | A | 平台内部基础 Mapper；本域无直接 HTTP 暴露；无 tenant_id 的原文已逐表记录。 |
| com.duozhi.outbound.mapper.CallbackInboxMapper.list | callbackinbox | A | 平台内部基础 Mapper；本域无直接 HTTP 暴露；无 tenant_id 的原文已逐表记录。 |
| com.duozhi.outbound.mapper.CallbackInboxMapper.updateById | callbackinbox | A | 平台内部基础 Mapper；本域无直接 HTTP 暴露；无 tenant_id 的原文已逐表记录。 |
| com.duozhi.outbound.mapper.CallbackInboxMapper.deleteById | callbackinbox | A | 平台内部基础 Mapper；本域无直接 HTTP 暴露；无 tenant_id 的原文已逐表记录。 |
| com.duozhi.outbound.mapper.CallbackInboxWorkerMapper.findById | callbackinboxworker | A | 平台内部基础 Mapper；本域无直接 HTTP 暴露；无 tenant_id 的原文已逐表记录。 |
| com.duozhi.outbound.mapper.CallbackInboxWorkerMapper.list | callbackinboxworker | A | 平台内部基础 Mapper；本域无直接 HTTP 暴露；无 tenant_id 的原文已逐表记录。 |
| com.duozhi.outbound.mapper.CallbackInboxWorkerMapper.updateById | callbackinboxworker | A | 平台内部基础 Mapper；本域无直接 HTTP 暴露；无 tenant_id 的原文已逐表记录。 |
| com.duozhi.outbound.mapper.CallbackInboxWorkerMapper.deleteById | callbackinboxworker | A | 平台内部基础 Mapper；本域无直接 HTTP 暴露；无 tenant_id 的原文已逐表记录。 |

## 本域显式新增的 4 条（不混入原 20 条）

| Mapper 方法 | 所属表 | 豁免类别 | 依据 / 调用边界 |
|---|---|---|---|
| com.duozhi.outbound.mapper.AuthIdentityMapper.findByUsernameForLogin | sys_user | B | 全局 UNIQUE(username)；AuthService.login 校验凭证、AuthDemoBootstrap.seed 定位演示身份；结果不直接作为业务响应。 |
| com.duozhi.outbound.mapper.AuthIdentityMapper.findByIdForJwt | sys_user | B | user.id 主键全局唯一；仅 JwtRealm.authenticate 定位用户；从 DB 用户取 tenant_id/role 并校验 enabled/sub/tv。 |
| com.duozhi.outbound.mapper.AuthIdentityMapper.findTenantById | tenant | B | tenant.id 主键；仅 JwtFilter.onAccessDenied 检查已定位身份的租户启用状态。 |
| com.duozhi.outbound.mapper.AuthIdentityMapper.lockTenantForUserAdmin | tenant | A | tenant 原表无 tenant_id；AdminUsers.locked 按 actor.tenant 的 tenant.id 加锁，保留原最后管理员并发保护。 |

B 类查出的 DB tenant_id 是 JWT 身份后续租户上下文唯一来源；API Key 则由服务器 TENANT_API_KEYS_JSON/API_KEY/UI_API_KEY 映射建立上下文。前端 x-tenant-id 只能校验一致性，不能覆盖上下文。

C 类统一直接带 tenant_id；更新和删除同样直接绑定 tenantId。35 个 XML 共 145 条 select/update/delete：121 条带条件，24 条明确例外（原 A20 + 本域 A1/B3）。逐条 SQL 见 verification/mapper-tenant-conditions.md。

原 global username 冲突检查改由保留的全局唯一索引在插入时判定并映射为相同 409 / username already exists，不为身份确定后的用户管理新增全局读方法。跨租户 PUT 用户资源的真实 HTTP 验证为 404、未返回数据且未保存更改。


本域最终验收：mvn -B verify 共 154 项通过（单元 9、容器集成 145），失败/错误/跳过均为 0。原 126 个源文件哈希复核未变。配置与代码版本组合不变；当前 35 个 XML 的负向探针也再次以退出码 1 拒绝缺失租户条件，临时副本已还原，运行时 XML 未修改。


## 差异与自决记录

本节持续追加；最终交付逐条全文列出。执行模式已按最新裁决改为连续执行，无阶段确认点；阶段 0 结论锁定，不重扫依赖，不变更已锁定版本。未实现/未验证条目仍保持未完成，不使用“不适用”替代。

1. **位置：Identity.admin、各管理员接口。** 原实现：用户为空时管理员校验放行；部分 /admin 路由统一 requireUser。选择：按人工批准的唯一修复显式判定 JWT admin 或 API Key 的接口权限码；联系人使用 contacts:write，话术及流程使用 script-templates:write，知识写操作使用 knowledge:write，设置使用 settings:write，用户管理使用 admin:users。权限仍从 TENANT_API_SCOPES_JSON 读取；知识 GET 原本不要求 admin，继续保留。理由：落实已批准的 API Key 越权修复，不改 JWT 用户角色规则。影响：仅管理员接口的 API Key 授权路径，未授权为 403；需要真实用户身份的非管理员业务动作继续保留原限制。
2. **位置：Crypto.token / JwtRealm。** 原实现：JWT 只有 sub / uid / tenant_id / role / tv / exp，吊销使用 token_version。选择：按人工最终裁决保留六字段，不引入 jti。理由：等价转译，无新增黑名单能力。影响：无业务行为差异。
3. **位置：Store、每张表 Mapper XML 的 patchFields。** 原实现：Store.update 只更新输入字段，去除 id / tenant_id / created_at，并自动写 updated_at。选择：保留同样字段处理，改用各表 XML 中逐字段的 containsKey 条件和 #{} 绑定；NULL 与未提供字段继续区分。理由：避免把原 PATCH 写成全行覆盖，同时满足 MyBatis 替换要求。影响：无业务语义差异；34 张表仍各自独立实体、接口和 XML。时间精度按已批准 DATETIME(3) 映射到毫秒。
4. **位置：Transactions.lock / identity / platform / tenant。** 原实现：PG pg_advisory_xact_lock 按哈希键串行化操作并在事务结束释放；tenant 使用行锁。选择：单 JVM 中以同一个哈希 long 为键，使用可重入锁并在事务 commit / rollback 后释放，所有持有者释放后移除锁项；tenant 仍用 MySQL FOR UPDATE。理由：目标已明确是单进程，保留原事务内串行范围和重入语义，不新增锁表或外部协调服务。影响：单进程部署内行为等价；不支持把多个应用 JVM 同时写同一个业务库，这也不属于本次目标。回滚释放已断言；其它并发链路在后续完整闭环验证。
5. **位置：DataRetention.globalCandidates 与各维护查询。** 原实现：维护任务枚举租户，部分录音/归档查询跨租户按时间排序并受全局 LIMIT 约束。选择：先从原平台 tenant 表枚举，每个业务查询显式 tenant_id，合并后按相同时间/原有次键排序，再应用原全局 LIMIT；每次更新/删除均带 tenant_id，父表隔离的 callevent 使用 callsession.tenant_id。理由：同时保留原全局批次边界和已裁决的 Mapper 租户条件要求。影响：返回/处置的业务集合与原排序规则一致；原 SQL 对相同排序值未定义顺序的情况仍不承诺额外顺序。
6. **位置：JwtSecurityConfiguration.shiroRegistration。** 原实现：原手写认证过滤器未引入 Shiro 的请求包装；CSV 使用 StreamingResponseBody。选择：Shiro 过滤器显式覆盖 REQUEST / ASYNC / FORWARD / INCLUDE / ERROR，保持 asyncSupported=true 和无 Session / 无 Cookie。理由：实际 HTTP 测试发现默认注册缺少 ASYNC，使 CSV 异步结束时 SecurityManager 线程上下文丢失；修改配置以保留原导出能力。影响：无对外契约差异，导出真实数据、UTF-8 BOM、列顺序、公式转义与流式响应均有断言。
7. **位置：所有业务文本列、查询及幂等键。** 原实现：PG 文本比较大小写敏感；原部分字段是复合幂等键而不是纯哈希。选择：延续已通过的数据层裁决，关键及全部业务文本列使用 utf8mb4_0900_bin（NO PAD），JSON 继续是原始文本，不新增归一化；应用内原有显式 lower/规范化照搬。理由：保持精确匹配、唯一约束和哈希输入。影响：无业务语义差异，18 类关键字段已对实际 MySQL 断言大小写、尾空格及 JSON 文本比较。

8. **位置：CallbackInboxBootstrap / 数据源会话初始化。** 原 PostgreSQL SERIAL 可以显式存储分区编号 0；MySQL 默认 AUTO_INCREMENT 会把 0 分配成自增值。选择：仅为 Druid 连接添加 NO_AUTO_VALUE_ON_ZERO，会话保留其他 sql_mode；DDL 不变。仅 Servlet 应用启动初始化 64 个分区，纯数据层测试上下文不执行 API 进程初始化。影响：保持 0–63 完整编号及重复初始化计数不变。
9. **位置：Store.query / TaskWorker.claim。** 原 Store 返回可变列表并在 AI 上下文组装时原地倒序；目标同样返回 ArrayList。原全局跨租户后台查询改为每租户带条件查询后合并，再执行原排序、LIMIT 与 round-robin；不对前台暴露全局业务数据。影响：不改变租户归属、任务候选排序及幂等键；MySQL 按租户候选锁定的行数可能多于原单条 PG 查询，尚不据此宣称性能改善。
10. **位置：EmbeddedAgent / SpringAiGeneration / EmbeddedAiTransport。** 原 AI 独立 HTTP 进程保持的规则、批准开关、消息历史、PII 处理、账户配额与追踪移入同一 JVM，配额账本按最新裁决改用独立 MySQL 事务；第三层生成通过锁定的 Spring AI 1.0.3，保留模型、temperature=0.3、输出上限、无隐式重试、1 MiB 响应上限和原调用超时。原 JSON 解码不依赖响应 Content-Type，Spring AI 解码器同样接受内容类型缺失的 JSON。影响：移除本机 AI 服务 HTTP 跳转，实际供应商 HTTP 请求及结果语义不变。
11. **位置：VoiceGateway / LedgerDatabase / RecordingController / MediaCluster / MediaWorkerRuntime。** 语音账本已从 SQLite 迁入 MySQL：voice_attempts、voice_flags、voice_nonces、voice_outbox、voice_audit、voice_action_commands、voice_permits、voice_media_owners，另将模型账户 budgets/usage 迁为 voice_model_budgets/voice_model_usage。共 34 业务 + 8 语音 + 2 模型 = 44 应用表，另有 1 张 Flyway 元数据表。voiceLedgerDataSource 独立连接池及显式独立事务不加入控制面事务；事务取得 voice_flags 的命名写者行 FOR UPDATE，保留两个原账本各自单写者范围。启动校验 FULL 等价持久性参数及 RR。保留 ESL、签名、配额、录音校验、worker/epoch/session/attempt 围栏；原工作进程为同 JVM 对象，音频连接 PIPECAT_MEDIA_WS_BASE。备份为 MySQL + 对象存储。原本地账本是开发数据，不做数据迁移，新库从空开始；当前应用不使用第三种数据库。
12. **位置：DirectAudioDispatcher / NativeMedia。** 原 WS 输入在 HTTP WebSocket 线程直接处理、短命 byte[] 缓冲。按人工单进程媒体硬约束改为专属 mono-media-pcm 池，全局有界任务队列、每连接最多 4 帧、过载丢最旧待处理帧；池化 DirectByteBuffer 承载输入，重采样与 TTS PCM 使用可复用堆外缓冲。JSON/Base64 供应商协议编码仍需文本对象。影响：正常负载 PCM 与原 Java 算法逐字节一致；过载明确丢帧，避免无限延迟。仅本机合成协议验证通过，媒体压测与真机/PBX 验证未做。
13. **位置：Shiro 原服务路由映射。** 原辅助服务使用各自 Service Token、命令签名或签名下载许可；合并后仅这些具体原路径由 Shiro 放行至原校验器，不扩展 API Key/JWT 权限，不将 /** 设置为 anon。主 /api/** 继续经 JwtFilter；管理员接口继续权限码判定。影响：原服务凭证边界保留，未新增业务接口。

14. **位置：CallStateTransitions / CallLifecycle / DialService / TaskWorker / Campaigns / Handoffs / AiRuntime。** 通用迁移函数保持单调；业务层显式回退路径（重拨 / 退回 / 超时回收）按原实现保留。13 个枚举与原 rank 不变：created=0、queued=1、dialing=2、answered=3、in_ai 与 handoff_transferring=4、waiting_human=5、in_human=6、终态=7。通用 status() 不重开终态，重复同状态仍可补 CDR；显式 retry 保留 attempts 先 queued 后 dispatch，耗尽或非终态 409；后台按 retryAt 到期翻 queued；活动重启复用 failed 且未耗尽的原 ID；准入前拒绝 queued 且 attempts-1、清开始时间和提供方标识；transfer 失败 waiting_human、释放坐席和标识并保留原错误；handoff 超时回 in_ai，end 时按原提示挂断；human_unavailable 回 waiting_human、释放坐席、重置计时。删除 _ai_resumed 间接标记，恢复可见状态。影响：撤销上一轮过度收紧，恢复原系统业务语义，未新增状态或接口。
15. **位置：拨号前 DialService.claim。** 原文档把选网关放在活动并发之前，但当前原 Java 源码先检查活动运行/并发，再选择网关。最终“功能等价转译”与连续执行的“最贴近原实现”优先，保留源码顺序和 15 个实际判断阶段：租户启用、可拨状态、attempts 上限、业务时间窗、平台并发、回调通道、合规预检、租户日限额、线路 priority/load、租户/线路容量、活动运行/并发、节点 TTL/CPS、AI 分桶、签名配置验证、落库及 dial_attempt_claimed 事件。影响：不改变当前源码对网关 CPS 预占与活动并发失败的先后效果；单进程节点仅保留原逻辑身份/容量能力，不另起网关进程。

16. **位置：Pages / Vue 路由 / Maven generate-resources。** 原 React 页面、字段与实际接口提交转为 Vue3 + Element Plus；以原 sessionStorage 令牌键、用户身份缓存边界、语言存储键与退出吊销语义为准。构建绑定 Maven，产物放入 classpath static，原外部静态目录改为 jar 内资源。原 Boot4 的 {*path} 写法在 Shiro 所用 AntPathMatcher 下不兼容，改为等价 /admin/** 与 /agent/**；后台和坐席地址不变。影响：组件实现与资源位置变化，页面业务输入输出保持原接口约定，重拨按钮按原终态且 attempts 未耗尽条件启用，并实际提交原 retry 接口。
17. **位置：S3Store / UnifiedServiceSettings / ServiceBoundary。** 录音对象存储通过已锁定 AWS SDK 2.29.52（含同版本 apache-client），保留 path-style、无隐式重试、原超时、对象键、元数据、上传后下载核验及删除墓碑。原 Compose 对 SERVICE_TOKEN、TELEPHONY_WEBHOOK_TOKEN/SECRET 和 RECORDING_S3_* 的角色映射移入设置适配器，未新增权限配置。原辅助服务具体路径保留 1 MiB 请求体与单值 ASCII 请求头校验；主 API 的原过滤器不重复套在这些旧服务路径上。影响：移除进程间 HTTP，供应商/存储交互及凭证检查保留。
18. **位置：OperationalMetrics / MetricsRegistry / ActuatorMetricsBoundary。** 原手写 Prometheus 改由 Micrometer 注册与导出，保留原指标名、标签、最近样本分位数及数据库查询口径；移除已消失维度的旧 meter。/metrics/runtime 保持不查询数据库，/metrics 及已有 Actuator exporter 使用同一 METRICS_TOKEN。影响：Prometheus 输出增加标准 HELP/TYPE 元信息及浮点格式，指标语义保留；这属于批准的监控实现替换。
19. **位置：ProductOperations.simulate / Store.insert / 26 个自增表 Mapper。** 原试跑用临时负 tenant.id 并以异常回滚全部副作用。MySQL 对显式负 ID 返回的 generated key 会按无符号解码，不能覆盖原有 Integer ID。选择：仅在调用方已给 ID 时走同表 insertExplicitId（不读 generated keys），未给 ID 仍使用原 insert 获取自增值。DDL 与 ID 类型不变。影响：保留原负 ID、模拟隔离及回滚语义，避免错误地改成真实租户或删除隔离逻辑。
20. **位置：ConversationStateMapper.deliveryFunnel。** 原 PG 按原始 JSON 提取结果分组后再 COALESCE 展示缺省值，JSON null/缺字段与实际字符串 "null" 不等价，缺省与显式 "pending" 也不能提前合并。MySQL ONLY_FULL_GROUP_BY 不接受原表达式组合，采用派生表先提取原始值，再按原始别名分组、最后 COALESCE；保留 bin 大小写敏感排序规则，不关闭 ONLY_FULL_GROUP_BY。影响：分组语义保留，修复的是转译兼容问题。

21. **位置：tools/build-frontend.sh / FatJarSmokeIT。** Maven 的资源复制不会自动删除旧哈希资源或已移除的类。选择：每次 generate-resources 构建前只清理派生的 target/classes，重新复制资源并编译；不删除源码、数据或本地依赖缓存。Fat jar 验证逐一比对其 static 条目与本次 Vue 构建清单，并断言已删除的手写 Metrics.class 不再残留。影响：交付包只含当前代码与当前前端资源，没有业务语义变化。

22. **位置：NativeMedia.connectAsync / requestFailure / MediaWebSocket。** 原 ASR 握手与断开清理运行在 HTTP WebSocket 回调线程。按人工媒体专属线程硬约束，认领连接后将供应商握手、停止确认与清理移入独立 mono-media-lifecycle 有界池；HTTP 回调只认领、入队与立即围栏，不等待供应商或持有会话锁的慢回调。启动前音频仍经过原每连接 4 帧有界队列，确认后送达，过载按第 12 条丢帧。清理队列拒绝时只在有容量上限的活跃会话集合内登记，由专属 media-deadlines 线程兜底；断开立即使 ASR 启动等待失败，清理绑定原 Session 对象，不影响后续同 call 的新代会话。影响：改变执行线程，不改变供应商协议、超时、凭证、回调内容与正常 PCM 输出。新增合成 ASR/TTS 断言验证异步启动、早到音频送达、慢回调期间断开不阻塞，仍未做真机或媒体压测。

## 连续执行历史记录（早期快照，最终统计见下节）

- 当前接口对照 34/108、手册完整功能 0/36；手册功能必须连同页面与闭环完成后才计数。
- 联系人 CRUD、过滤、分页、DNC 批量、同意状态、CSV 导入/导出和原错误状态已移入 MyBatis；CSV request_key、Outbox idempotency_key 与归档 TaskReceipt 的真实幂等路径已断言。
- CallPolicy 原 DNC、同意、时间窗、目的地、每日频次、重试间隔顺序保留；数据保留实际删除过期转录、清空关联证据、脱敏、录音删除任务和归档幂等回执已有数据库断言。
- 话术版本/软删除、知识版本/软删除、流程图校验、编辑、发布、不可修改、克隆、模拟、变量收集及否定词匹配已通过实际 HTTP 或服务断言。业务交付策略仍随后续 AI 依赖继续转译，尚未计完成。
- 完整 mvn -B verify：45 单元 + 171 集成 = 216，失败/错误/跳过均 0。证据：verification/customer-script-verify.txt、continuous-test-cases.json。该轮包含完整 mock 通话闭环；前端浏览器验收仍在执行。
- 新增 A 类精确机器白名单：5 个平台表 patchFields、TenantMapper.lockForTransaction、TenantMapper.allForMaintenance；原 schema 无 tenant_id 的依据延用本文件逐表 CREATE TABLE 原文。无新增 B 类身份查询。当前 35 XML / 221 条 select-update-delete / 190 条显式 tenant 谓词 / 31 条精确 A/B 例外；每条例外在集中 JSON 中显式登记。
- 开发环境容器与 HTTP 已验证；测试环境发布、真机和生产发布均未执行。媒体压测/真实 PBX/真实 ASR-LLM-TTS 仍未验证，继续作为最终交付边界保留。

## 上一轮验收记录（历史快照，不作本轮通过依据）

- `mvn -B verify`（实际指定项目本地 Maven 仓库）：133 单元 + 178 集成 = 311；失败 0、错误 0、跳过 0；完整日志 `verification/final-verify.log`。对照表与机器清单均为 108 接口 / 36 功能，不适用均 0。
- `mvn -B enforcer:enforce`：Java / Maven / 原依赖禁用规则通过，`verification/final-enforcer.log`；未改变锁定核心版本或扩大 javax.validation 例外。
- `BusinessHttpIT.repeatableMockOutboundLifecycleProducesQualityReportsAndBilling`：真实 HTTP/MySQL/Redis mock 闭环；至少 2 轮客户与 AI 回合、转人工接听、完成、自动质检、报表计费、重复 CDR 幂等。严格终态与转人工恢复另有 84 状态断言和 3 个 HTTP 场景。
- `FrontendBrowserIT.allVueEntrancesFormsSessionBoundariesAndMockEvidenceViewsWorkInChromium`：18 组实际 Chromium 交互检查；原 16 后台页面和坐席工作台、共用通话页、登录/退出/错误及角色边界均覆盖。用真实后端保存并回显，未 mock 浏览器 API 返回。
- `FatJarSmokeIT.packagedSingleProcessServesHealthAndBuiltVueWithoutOtherApplicationProcesses`：JDK 17 单 jar 进程，`GET /healthz` 200 且原 JSON、登录 HTML 200、内置 JS 200；匿名 runtime metrics 401，正确原指标凭证 200。运行进程由测试关闭，未作为本地/生产部署保留。
- 数据层复核：34 表、379 列、缺失 0/多余 0；20 唯一约束；78 DATETIME(3) 业务时间列，无业务 TIMESTAMP / MySQL 原生 ENUM；35 XML；401 条 select/update/delete，352 条 tenant 谓词、49 条集中 A/B 例外；新增 26 条显式 ID insert 仍一表一 XML。Flyway 空库执行 1 个迁移，再执行 0 个，均成功。DDL 没有为业务转译再修改。
- G1 使用 `-XX:+UseG1GC -XX:MaxGCPauseMillis=20`；原因是锁定 JDK 17 下保留默认收集器并设清晰暂停目标，配合专属有界媒体池和 DirectByteBuffer。`verification/fatjar-smoke-gc.log` 仅启动与探针样本，**没有做媒体压测，不能据此判断音频质量、容量或暂停目标已达标**；真实 SIP/RTP、云模型、ASR/TTS、设备与目标宿主环境仍未验证。
- 收尾 dependency-check 仅执行 1 次，`autoUpdate=false` / `failOnError=false`；命令日志报告 Fatal exception(s)，未生成工具标准 HTML。未将退出 0 当作报告成功，未制作虚假的零结果报告。执行状态页为 `verification/dependency-check-execution-status.html`，只说明此次未产出报告；按人工裁决不阻塞，不重扫、不逐条处置。
- 状态分别为：源代码已修改；静态及构建检查通过；开发环境功能测试通过；测试环境未发布；真机未验证；生产未发布；未 commit / push。环境限制与正式上线前置条件详见 README.md。


## 2026-10-20 存储、串行化与回退收尾

### 单实例边界

本系统事务串行化依赖 JVM 内锁，仅支持单实例部署。需要使用多实例时，必须先把 PlatformLock 替换为分布式实现并重做并发验证。

当前唯一实现为 JvmPlatformLock，业务 Transactions 只依赖 PlatformLock。可重入、等待者引用计数、最后一个持有者/等待者释放后清理、事务 commit/rollback 后释放及命名 tryLock 范围保留。按追加裁决不实现 Redis 单实例哨兵。升级必须先停止旧进程并确认退出，再启动新进程，禁止滚动重叠。

### 独立事务与串行路径

所有以下 voice 写事务经 LocalLedger.transaction → LedgerDatabase.transaction("__voice_writer__")，先锁 `voice_flags.key='__voice_writer__' FOR UPDATE`，提交或回滚才释放；同线程同命名重入复用连接且失败标记 rollback-only：

| 原/新类调用点 | 写者串行范围 |
|---|---|
| LocalLedger.stopped | 停机标志 + 审计一起提交 |
| VoiceSecurity.command / admit / finish | nonce 单次消耗；attempt/预算/每日及小时额度检查与认领；结束结算 |
| VoiceGateway.dial / reconcile / command / onEvent / quota | 拒绝审计、拨号认领结果、命令幂等键/结果、ESL observed、配额 permit |
| VoiceCallbacks.atomic / enqueue / deliver / purge | 呼叫事件与回调入队原子组；去重、重试/删除、清理，stream_key 同流 FIFO |
| MediaCluster.start / remove / quota | worker/epoch owner 认领与释放、媒体账户 permit |

AccountQuota 构造配置指纹登记、reserve（窗口清理、预算校验和用量预占）、block（限流截止）均锁独立 `__model_writer__` 行，保持原第二文件的独立单写者边界。声音账本与模型账户各自独立提交，不并入控制面事务。LocalLedger.read 在原事务中读取当前连接，否则取独立池连接。真实 MySQL 测试覆盖控制面回滚后账本提交仍存在，以及两个账本连接池的写者互斥。

voiceLedgerDataSource 与主业务池是不同 Druid 对象、独立连接，连接主 MySQL 同库；初始 0/minIdle 0/maxActive 4/maxWait 5000ms，RR。启动实际查询 `@@GLOBAL.innodb_flush_log_at_trx_commit`、`@@GLOBAL.sync_binlog`、`@@SESSION.transaction_isolation`，非 1/1/REPEATABLE-READ 拒绝启动。测试使用隔离容器将 sync_binlog 改 0 验证拒绝，再还原；没有修改部署数据库。

### 逐表 DDL 对账（原定义 → V2）

原 Java `services-java/.../LocalLedger.java` 的 7 表及其后增列/索引、MediaCluster 第 8 表、AccountQuota 的 2 表均以源代码取证。以下原定义为历史介质原文，运行代码不再执行它们。V1 不改；本轮只新增 V2。

原 `LocalLedger.java`：

```sql
CREATE TABLE IF NOT EXISTS attempts(call_id TEXT NOT NULL,attempt INTEGER NOT NULL,tenant INTEGER NOT NULL,line TEXT NOT NULL,uuid TEXT NOT NULL UNIQUE,digest TEXT NOT NULL,payload TEXT NOT NULL,created REAL NOT NULL,deadline REAL NOT NULL,cost INTEGER NOT NULL,rate INTEGER NOT NULL,multiplier INTEGER NOT NULL,state TEXT NOT NULL DEFAULT 'pending',result TEXT,ended REAL,observed INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(call_id,attempt));
CREATE INDEX IF NOT EXISTS attempts_scope ON attempts(tenant,line,created);
CREATE TABLE IF NOT EXISTS flags(key TEXT PRIMARY KEY,value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS nonces(nonce TEXT PRIMARY KEY,expires REAL NOT NULL);
CREATE TABLE IF NOT EXISTS outbox(id TEXT PRIMARY KEY,url TEXT NOT NULL,body BLOB NOT NULL,created REAL NOT NULL,due REAL NOT NULL,failures INTEGER NOT NULL DEFAULT 0,stream_key TEXT NOT NULL DEFAULT '');
CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY,at REAL NOT NULL,action TEXT NOT NULL,detail TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS voice_action_commands(id TEXT PRIMARY KEY,digest TEXT NOT NULL,result TEXT,call_id TEXT NOT NULL DEFAULT '',attempt INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS voice_permits(id TEXT PRIMARY KEY,scope TEXT NOT NULL,kind TEXT NOT NULL,call_id TEXT NOT NULL,attempt INTEGER NOT NULL,owner TEXT NOT NULL,state TEXT NOT NULL,created REAL NOT NULL);
CREATE INDEX IF NOT EXISTS voice_permits_scope ON voice_permits(scope,state);
CREATE INDEX IF NOT EXISTS outbox_stream_order ON outbox(stream_key,created);
CREATE INDEX IF NOT EXISTS outbox_created_order ON outbox(created);
CREATE INDEX IF NOT EXISTS voice_commands_call ON voice_action_commands(call_id,attempt);
CREATE INDEX IF NOT EXISTS voice_permits_retention ON voice_permits(state,created);
CREATE INDEX IF NOT EXISTS attempts_ended ON attempts(state,ended);
CREATE INDEX IF NOT EXISTS attempts_created ON attempts(created);
```

原 `MediaCluster.java`：

```sql
CREATE TABLE IF NOT EXISTS media_owners(call_id TEXT PRIMARY KEY,worker_id TEXT NOT NULL,epoch TEXT NOT NULL,session_json TEXT NOT NULL,state TEXT NOT NULL);
```

原 `AccountQuota.java`：

```sql
CREATE TABLE IF NOT EXISTS budgets(scope TEXT PRIMARY KEY,config TEXT NOT NULL,blocked_until REAL NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS usage(scope TEXT NOT NULL,second INTEGER NOT NULL,requests INTEGER NOT NULL,tokens INTEGER NOT NULL,PRIMARY KEY(scope,second));
```

| 原表 | MySQL V2 表 | 列/约束/索引与方言适配 |
|---|---|---|
| attempts | voice_attempts | 16 列；(call_id,attempt) 主键、uuid 唯一、attempts_scope/ended/created 索引保留；INTEGER→BIGINT、REAL→DOUBLE、未索引正文→LONGTEXT |
| flags | voice_flags | key 主键/value；key 加反引号；额外两行内部写者互斥元数据，不暴露为业务 flags |
| nonces | voice_nonces | nonce 主键/expires；过期时间保持 DOUBLE 秒 |
| outbox | voice_outbox | 原 id/url/body/created/due/failures/stream_key；原隐式 rowid 用 enqueue_order AUTO_INCREMENT UNIQUE 显式表达；保留 (stream_key,created) 及 created 索引，FIFO 同时间戳用 enqueue_order 打破平局 |
| audit | voice_audit | id INTEGER PRIMARY KEY 隐式自增→BIGINT AUTO_INCREMENT；at/action/detail 保留 |
| voice_action_commands | voice_action_commands | id 主键/digest/result/call_id/attempt；(call_id,attempt) 索引保留 |
| voice_permits | voice_permits | id 主键/scope/kind/call_id/attempt/owner/state/created；(scope,state)、(state,created) 索引保留 |
| media_owners | voice_media_owners | call_id 主键/worker_id/epoch/session_json/state；ON CONFLICT→ON DUPLICATE KEY UPDATE |
| budgets | voice_model_budgets | scope 主键/config/blocked_until；配置指纹与最大阻断时间语义保留 |
| usage | voice_model_usage | (scope,second) 复合主键/requests/tokens；窗口预占 UPSERT 累加保留 |

所有 V2 表 InnoDB、utf8mb4_0900_bin；时间原本均为 REAL epoch/INTEGER 秒而非 TEXT 时间，因此 DOUBLE/BIGINT 保留原秒值，不将其误改 DATETIME。原 TEXT 无长度声明；MySQL 为完整索引使用 VARCHAR（call_id/uuid/nonce/digest/id=128，命令/permit id/owner/worker=255，scope=512，state/kind=32），正文 LONGTEXT、二进制 LONGBLOB；这是索引方言适配的实际宽度限制，不能宣称任意长度输入无差异。正常源 UUID、SHA-256、受配置约束的状态/种类均在界内，超长自定义 scope/owner/行标识尚未验证，列为已知边界，不隐瞒。FIFO 辅助列与两行写者元数据只承担原介质隐式行为，不是新增业务能力。

### 引用清理边界

运行源码、测试、环境样例、Compose 不再引用旧嵌入数据库或其文件配置；驱动已移除，Enforcer 按人工要求明确禁用 `org.xerial:sqlite-jdbc`。该必需禁用坐标与“任何文本零命中”的字面要求相冲突，保留禁用更能防止重新引入。原 source-baseline.json 的文件名/hash 以及 verification 旧报告作为历史取证保留原位；不通过删除历史或改写基线伪造全仓零命中。自动审批拒绝了批量搬移历史取证与改写基线清单，故未执行。原工程不在目标清理范围。没有开发数据迁移，也未改变本地部署。

### 当前行为差异结算

唯一批准的业务行为差异仍是 API Key 管理员越权修复：无显式权限码 403、有权限码 200；admin 用户 200、agent 用户 403。JWT 六字段及 token_version 原样。存储介质、锁接口、Vue、MyBatis 等为已批准实现替换；显式回退按第 14 条恢复。上文 MySQL 索引宽度为已说明的输入边界，未声称未测输入等价。

### 新 MySQL DDL 原文

```sql
-- Independent voice/model transactions. Epoch REAL values remain DOUBLE; there were no TEXT time columns.
-- enqueue_order represents the former implicit insertion ordinal used for exact FIFO ties.
CREATE TABLE voice_attempts (
  call_id VARCHAR(128) NOT NULL,
  attempt BIGINT NOT NULL,
  tenant BIGINT NOT NULL,
  `line` VARCHAR(255) NOT NULL,
  uuid VARCHAR(128) NOT NULL,
  digest VARCHAR(128) NOT NULL,
  payload LONGTEXT NOT NULL,
  created DOUBLE NOT NULL,
  deadline DOUBLE NOT NULL,
  cost BIGINT NOT NULL,
  rate BIGINT NOT NULL,
  multiplier BIGINT NOT NULL,
  state VARCHAR(32) NOT NULL DEFAULT 'pending',
  result LONGTEXT,
  ended DOUBLE,
  observed BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(call_id,attempt),
  UNIQUE KEY attempts_uuid(uuid),
  KEY attempts_scope(tenant,`line`,created),
  KEY attempts_ended(state,ended),
  KEY attempts_created(created)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE voice_flags (
  `key` VARCHAR(255) NOT NULL PRIMARY KEY,
  value LONGTEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE voice_nonces (
  nonce VARCHAR(128) NOT NULL PRIMARY KEY,
  expires DOUBLE NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE voice_outbox (
  id VARCHAR(128) NOT NULL PRIMARY KEY,
  url LONGTEXT NOT NULL,
  body LONGBLOB NOT NULL,
  created DOUBLE NOT NULL,
  due DOUBLE NOT NULL,
  failures BIGINT NOT NULL DEFAULT 0,
  stream_key VARCHAR(128) NOT NULL DEFAULT '',
  enqueue_order BIGINT NOT NULL AUTO_INCREMENT,
  UNIQUE KEY outbox_implicit_order(enqueue_order),
  KEY outbox_stream_order(stream_key,created),
  KEY outbox_created_order(created)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE voice_audit (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  at DOUBLE NOT NULL,
  action VARCHAR(128) NOT NULL,
  detail LONGTEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE voice_action_commands (
  id VARCHAR(255) NOT NULL PRIMARY KEY,
  digest VARCHAR(128) NOT NULL,
  result LONGTEXT,
  call_id VARCHAR(128) NOT NULL DEFAULT '',
  attempt BIGINT NOT NULL DEFAULT 0,
  KEY voice_commands_call(call_id,attempt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE voice_permits (
  id VARCHAR(255) NOT NULL PRIMARY KEY,
  scope VARCHAR(512) NOT NULL,
  kind VARCHAR(32) NOT NULL,
  call_id VARCHAR(128) NOT NULL,
  attempt BIGINT NOT NULL,
  owner VARCHAR(255) NOT NULL,
  state VARCHAR(32) NOT NULL,
  created DOUBLE NOT NULL,
  KEY voice_permits_scope(scope,state),
  KEY voice_permits_retention(state,created)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE voice_media_owners (
  call_id VARCHAR(128) NOT NULL PRIMARY KEY,
  worker_id VARCHAR(255) NOT NULL,
  epoch VARCHAR(128) NOT NULL,
  session_json LONGTEXT NOT NULL,
  state VARCHAR(32) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE voice_model_budgets (
  scope VARCHAR(512) NOT NULL PRIMARY KEY,
  config VARCHAR(128) NOT NULL,
  blocked_until DOUBLE NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE voice_model_usage (
  scope VARCHAR(512) NOT NULL,
  second BIGINT NOT NULL,
  requests BIGINT NOT NULL,
  tokens BIGINT NOT NULL,
  PRIMARY KEY(scope,second)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

INSERT INTO voice_flags (`key`,value) VALUES ('__voice_writer__','0'),('__model_writer__','0');
```

### 配额配置迁移说明

位置：AccountQuota 构造/reserve/block。原实现用“旧文件路径非空”同时表示启用持久窗口；路径为空时仅连接数 Semaphore 有效，reserve=0、block 不记录。删除文件路径时不能把所有默认环境静默改成启用窗口。选择等价替换为 `LLM_QUOTA_ENABLED`（默认 false），原来配路径的部署改为 true，未配路径保持 false；环境样例原本配置了路径，故改为 true，scope/限额名称及默认值不变。没有新增业务限流功能，是原隐式配置开关的显式替换。独立 MySQL 事务只在启用时持久化；V2 的两张模型表仍统一创建。新增断言验证默认关闭时不消耗窗口、block 不生效、连接数仍受限且数据库不生成 budget，启用路径保持原持久/并发测试。构造时配置指纹不符会关闭自有连接池再抛原错误，避免失败初始化泄漏。

## 2026-10-20 本轮最终实测记录

- 完整 `mvn -B verify`：139 单元 + 184 集成 = **323**，失败 0、错误 0、跳过 0；Chromium **19 组**（含真实重拨点击）通过；独立 jar `/healthz` 200、HTML/JS 200。
- 独立 Enforcer 通过，精确驱动依赖树无条目；V1 SHA-256 `943be81aef312cb926ec022b07184b94186236b69c2e6b5247d54fe5785d1160` 原样；126 源文件哈希全部一致。
- 原 34 表/379 列/20 唯一约束一致；当前 44 应用表 + 1 Flyway 表，首次执行 2 个迁移、重放 0 个。接口 108/108，手册功能 36/36，不适用 0。
- 8 条指定恢复回归全部各 1 次通过；失败→重拨→成功完整闭环通过；独立 fat jar 实际 curl 两次重拨至 attempts=3，最终 409，并得到 3 个 dial_attempt_claimed。
- 早期失败修正均有记录：新增表清单未更新、浏览器夹具列名误写、Tomcat Started 日志早于开发账号种子、持久冷却夹具漏启用开关；未放宽预期 HTTP 状态、字段或业务断言。
- 本轮证据和完整汇报：[verification/closeout-20261020/REPORT.md](verification/closeout-20261020/REPORT.md)，机器统计 result.json，完整 verify/enforcer/定向 retry/HTTP 日志同目录。运行引用 0、必需 POM 禁用项 1、历史取证引用保留；因此不宣称字面全仓零引用。超长索引标识与媒体/真实线路/500 总在途仍未验证。未 commit/push/部署。

## 修改后单机500总在途模拟实测（2026-10-20）

沿用本轮修改后的同一Jar，SHA-256 `81fca8a628719e009b249b4bb765f3525d40a6fc1460eaba3052ff19167e6fd6`，263个应用主文件未变；仅补压测夹具与证据。三轮120秒：50轮/秒+16MiB缓冲池17.608有效轮/秒、P95/P99=25.907/27.865秒，失败；50轮/秒+128MiB为19.000轮/秒、23.985/24.894秒，失败；10轮/秒+128MiB/JFR为9.967轮/秒、460/512ms，按事先门槛通过但有1次401。全部保持500个模拟IN_AI、第501个queued。

已捕获taskoutbox领取/废弃租约扫描与更新的死锁、AI单通道消费不足和多次SQL事务路径；128MiB时物理读增量0但仍未达50轮/秒，不将内存或死锁单独说成全部根因。不能把本轮控制面模拟或低载通过当作真实500线路/媒体容量达标；真实音频、云模型、录音、长稳、目标Linux未验证。未改业务行为，未优化应用池/线程，未部署。原测试资料保留，三进程和六个自建容器清理、原13容器仍运行的取证及详细结果见 verification/capacity500-postmysql-20261020/REPORT.md。

### 2026-10-20：500总在途积压、死锁与异常401修复（验证记录以本轮报告为准）

- 位置：TaskWorker.claim / TaskOutboxMapper、Outbox.enqueue。原实现以范围FOR UPDATE扫描待处理/过期任务和待替代转写，实测扫描持有大量行锁并与状态更新交叉死锁；改为不加锁的候选扫描，取得单条主键锁后复核状态、attempts、available_at、locked_at，再持原租约与顺序约束更新。Outbox同样逐主键锁后复核pending/failed，绝不替代processing任务。全局领取互斥仍依赖PlatformLock；同一流排序与录音共流/after_playback原例外保留。
- 位置：TaskWorker.tick。原实现每批最多16项后必等下一轮定时调度；改为处理满16项时继续提交下一有界批次，空队列才回定时轮询。仍为4个线程、每通道最多1个所有者，AI仍单执行通道，没有通过扩大线程/连接池掩盖瓶颈。
- 位置：CallbackInbox.receive/consume/maintenance。原接收先锁分区、消费先锁回执后改分区；改为接收/消费统一先分区后单回执。回执候选扫描不加范围锁，选中后主键锁重检；清理从范围DELETE改为扫描到期记录后按主键及原到期条件删除。计数、容量、去重摘要、事务原子性、重试/死信和同通话顺序保留。
- 位置：JwtSecurityConfiguration.shiroRegistration。原ERROR派发再次经/error的Basic兜底，把已签名回调的服务器异常改成401；仅停止对容器ERROR派发重复鉴权，REQUEST/ASYNC/FORWARD/INCLUDE保留。直接请求/error及API仍需鉴权。数据库异常应保留500；这属于本轮人工明确授权的错误修复，不改正常JWT/API Key权限语义。
- 数据库：新增V3，仅3个非唯一索引（任务候选、任务同流、回执到期），V1/V2校验和不变。回放断言从2次精确改为3次迁移，并增加V3及索引列顺序断言，保留原45表、所有列和唯一约束断言。
- 白名单：callbackinbox原A类平台表清理方法拆分为expiredReceipts/deleteExpiredReceipt，原依据不变；未新增业务表租户例外。
- 压测：保留500个真实HTTP准入模拟呼叫、每秒50轮、120秒、最多30秒排空及全部原验收阈值。仅两条EXPLAIN诊断字符串随SQL更新，其余压测源文与快照完全一致。本轮不更新已有部署、不推送Git，不把模拟结果外推为真实线路容量。

#### 同日后续自决：跨通话AI任务利用现有线程池

首轮修复的稳态快照仍有数百条AI积压，AI唯一工作线程在JDBC往返，其余3个任务线程多次采样处于executor空闲，且无数据库锁等待。决定增加3个逻辑AI领取通道，所有7个逻辑通道共用原4线程池、每个逻辑通道至多1个批次，满批次排队到队尾；物理线程数及连接池不变。原3个非AI通道保留，FIFO批次提交防止被AI连续占满；同一aggregate的前驱约束、全局领取互斥、主键锁复核、租约/attempt隔离不变，跨通话才可并行。压测元数据相应标明逻辑通道7/物理线程4/AI最多4；负载、阈值、断言及统计未改。首轮随后遇到宿主磁盘满使Docker引擎停止，其结果标记环境中断，不作为完整容量验收。

### 2026-10-20 容量修复后续自决：锁等待、回调分区与重复读库

- 位置：Transactions.serialized、AiRuntime、CallbackInbox.consume。原来进入事务借出连接后才等PlatformLock；60秒线程样本显示平台锁与连接等待叠加。选择先取得原平台锁再开事务，嵌套仍保留REQUIRED回滚与事务完成后释放；不移除全局串行化、不改隔离/刷盘或连接数。公平可重入锁用于避免连续抢占。主业务池useLocalSessionState仅避免Connector/J重复获取会话标志，不改变提交。
- 位置：TaskWorker.receiveTick。原来每次250ms消费预算从分区列表头开始；R3计划→客户话语落库P99=47384ms，客户话语→AI回复P99=9941ms。选择从上次处理的分区之后轮转，保留分区内前驱约束、单回执事务、容量限制及250ms预算，影响范围仅跨分区调度公平性。
- 位置：Store.writeLocked、ConversationPolicy.save、VoiceCallbacks.speech、AiRuntime实时状态写入。原update有前后两次回读，而上述已定位/持锁且不使用返回值的路径丢弃这些结果；改用原patchFields直接写，保留tenant_id、不可修改字段、updated_at、缺失404、事务回滚，无业务缓存。Store.appendMetric仍原SQL插入并生成键和默认值，只省未使用回读。
- 当前各迭代实际验收、Jar与源文件哈希见verification/capacity500-fix-20261020/。R1磁盘满引擎停止，不作有效容量验收；R2/R3完整120秒均未通过原SLO，不能据此声称500容量达标。后续修改的通过结论须以其独立复测为准。真实媒体、SIP/RTP、云模型、ASR/TTS、录音、长稳及目标Linux仍未验证，未更新部署。

- 后续位置：AiRuntime.current、TaskWorker任务上下文、CallSessionMapper.currentAiRead/currentAiLock。原来一次current分多条SQL定位呼叫、查turn_sequence、查generation，且每次列举所有租户；选择把原谓词合为一条有tenant_id的SQL，FOR UPDATE模式保留。只将已领取持久任务的tenant_id作为线程局部定位提示，不取payload/HTTP；不命中仍逐租户定位，finally清理，原permitted租约校验次数不减。影响仅定位/校验往返；缺失实时会话仍允许turn_sequence，给定generation但缺少状态仍拒绝；锁/非锁、错误attempt/序号/代次、跨租户与终态拒绝均有断言。当前容量是否通过仍须其独立复测，不复用上轮结果。
