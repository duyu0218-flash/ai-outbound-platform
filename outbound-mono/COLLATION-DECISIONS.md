# 关键列 collation 判定

依据：直接读取原 schema、原查询/唯一约束，以及本次 MySQL information_schema。表默认 utf8mb4_0900_ai_ci；所有文本列显式 utf8mb4_0900_bin（NO PAD）。本轮未修改 V1。

| 表.列 | 原 PG 行为（敏感/不敏感） | 依据（原查询/唯一约束/SQL 用法） | 新 collation | 判定理由 |
|---|---|---|---|---|
| sys_user.username | 敏感（PG 文本等值/LIKE 不折叠大小写） | 源 AuthService.login：WHERE username=?；schema-postgresql.sql 的 ix_user_username 全局 UNIQUE(username) | utf8mb4_0900_bin | 精确登录与唯一约束均须区分 Admin/admin；HTTP 登录/LIKE 与既有唯一键测试覆盖。 |
| tenant.code | 敏感（PG 文本等值/LIKE 不折叠大小写） | schema-postgresql.sql：CREATE UNIQUE INDEX ix_tenant_code ON tenant(code)；Bootstrap 内部生成 java-dev-<id>，ProductOperations.simulate 生成 UUID | utf8mb4_0900_bin | 保留全局代码唯一语义；生成值通常小写不等于数据库可以折叠大小写。 |
| contact.phone | PG 文本比较敏感；合法业务值只有数字 | schema-postgresql.sql UNIQUE(tenant_id,phone)；Contacts.phone 仅接受 6–15 位数字；DNC/联系人查询按 tenant_id+phone 等值 | utf8mb4_0900_bin | 数字无字母大小写；仍用 bin 保留原文本比较及租户唯一性，不新增清洗规则。 |
| phonesuppression.phone | PG 文本比较敏感；合法业务值只有数字 | schema-postgresql.sql UNIQUE(tenant_id,phone)；Contacts.phone 仅接受 6–15 位数字；DNC/联系人查询按 tenant_id+phone 等值 | utf8mb4_0900_bin | 数字无字母大小写；仍用 bin 保留原文本比较及租户唯一性，不新增清洗规则。 |
| taskoutbox.idempotency_key | 敏感（PG 文本等值/LIKE 不折叠大小写） | schema-postgresql.sql 全局 UNIQUE(idempotency_key)；Outbox.enqueue 对两个表均 WHERE idempotency_key=? | utf8mb4_0900_bin | 并非全是纯哈希，可能为带 dial:/ai:/callback: 等前缀的复合键；不做大小写归一，不能折叠两个原本不同的键。 |
| taskreceipt.idempotency_key | 敏感（PG 文本等值/LIKE 不折叠大小写） | schema-postgresql.sql 全局 UNIQUE(idempotency_key)；Outbox.enqueue 对两个表均 WHERE idempotency_key=? | utf8mb4_0900_bin | 并非全是纯哈希，可能为带 dial:/ai:/callback: 等前缀的复合键；不做大小写归一，不能折叠两个原本不同的键。 |
| webhookeventingest.provider_event_key | 敏感（PG 文本等值/LIKE 不折叠大小写） | schema-postgresql.sql 全局 UNIQUE；CallLifecycle.event 对 canonical JSON 求 JsonSupport.sha 后等值去重 | utf8mb4_0900_bin | sha 输出规范小写十六进制，原文与输入未改则生成结果不受 DB collation 影响；bin 仍拒绝把不同大小写文本视为同一键。 |
| callbackinbox.receipt_key | 敏感（PG 文本等值/LIKE 不折叠大小写） | schema-postgresql.sql 全局 UNIQUE；CallbackInbox.receive：sha(json([kind,callId,eventId或digest]))，WHERE receipt_key=? | utf8mb4_0900_bin | 规范小写 SHA-256 输出不被数据库改写；bin 保留原完整比较，不引入大小写折叠。 |
| campaign.name | 敏感（PG 文本等值/LIKE 不折叠大小写） | Campaigns.list 仅 tenant_id 过滤并按 created_at/id 排序；原 schema name 是 VARCHAR；当前源没有 name LIKE 搜索 | utf8mb4_0900_bin | 保留 PG 文本语义；不凭需求描述捏造或新增模糊接口。未来等价转译原查询时仍须保留原显式归一规则。 |
| scripttemplate.name | 敏感（PG 文本等值/LIKE 不折叠大小写） | 原 schema 与 Templates 使用 name，没有 title 列；Templates.list 当前只按 category/active 过滤 | utf8mb4_0900_bin | scripttemplate.title 是本轮要求中的名称误差，实际字段 name 已为 bin；不新增 title。 |
| knowledgeitem.title | PG 文本敏感；应用检索存在显式归一 | Knowledge.list 按 tenant_id 列出；AiRuntime.java:127–137 在 Java 显式归一文本后评分，未用数据库 CI LIKE 代替 | utf8mb4_0900_bin | 存储和 SQL 比较保留敏感；原检索中显式 lowercase/词元规则属于应用逻辑，后续原样转译，不依赖数据库 collation。 |
| knowledgeitem.keywords | PG 文本敏感；应用检索存在显式归一 | Knowledge.list 按 tenant_id 列出；AiRuntime.java:127–137 在 Java 显式归一文本后评分，未用数据库 CI LIKE 代替 | utf8mb4_0900_bin | 存储和 SQL 比较保留敏感；原检索中显式 lowercase/词元规则属于应用逻辑，后续原样转译，不依赖数据库 collation。 |
| taskoutbox.payload_json | 敏感（PG 文本等值/LIKE 不折叠大小写） | Outbox.enqueue 保存 json.json(payload)，领取后按原文解析 | utf8mb4_0900_bin | LONGTEXT 原文不被 JSON 类型重排或归一；大小写、键序、尾空格均保留。字符串比较使用 bin；哈希只作用于原应用明确编码/规范化后的 UTF-8 字节，数据库排序不参与计算。 |
| adminsetting.data_json | 敏感（PG 文本等值/LIKE 不折叠大小写） | AdminSettings / ProductRuntime 按原字符串保存、解析或更新；JSON 条件在原业务中另行显式提取 | utf8mb4_0900_bin | LONGTEXT 原文不被 JSON 类型重排或归一；大小写、键序、尾空格均保留。字符串比较使用 bin；哈希只作用于原应用明确编码/规范化后的 UTF-8 字节，数据库排序不参与计算。 |
| scriptflowversion.graph_json | 敏感（PG 文本等值/LIKE 不折叠大小写） | ScriptFlows 保存 graph_json，Campaigns 解析 nodes，发布/校验使用应用 JSON 逻辑 | utf8mb4_0900_bin | LONGTEXT 原文不被 JSON 类型重排或归一；大小写、键序、尾空格均保留。字符串比较使用 bin；哈希只作用于原应用明确编码/规范化后的 UTF-8 字节，数据库排序不参与计算。 |
| scenarioversion.policy_json | 敏感（PG 文本等值/LIKE 不折叠大小写） | ProductOperations / ProductRuntime 读取原文并解析；JsonSupport.json/canonical/sha 在应用层处理 | utf8mb4_0900_bin | LONGTEXT 原文不被 JSON 类型重排或归一；大小写、键序、尾空格均保留。字符串比较使用 bin；哈希只作用于原应用明确编码/规范化后的 UTF-8 字节，数据库排序不参与计算。 |
| conversationstate.policy_json | 敏感（PG 文本等值/LIKE 不折叠大小写） | ProductOperations / ProductRuntime 读取原文并解析；JsonSupport.json/canonical/sha 在应用层处理 | utf8mb4_0900_bin | LONGTEXT 原文不被 JSON 类型重排或归一；大小写、键序、尾空格均保留。字符串比较使用 bin；哈希只作用于原应用明确编码/规范化后的 UTF-8 字节，数据库排序不参与计算。 |
| conversationstate.data_json | 敏感（PG 文本等值/LIKE 不折叠大小写） | AdminSettings / ProductRuntime 按原字符串保存、解析或更新；JSON 条件在原业务中另行显式提取 | utf8mb4_0900_bin | LONGTEXT 原文不被 JSON 类型重排或归一；大小写、键序、尾空格均保留。字符串比较使用 bin；哈希只作用于原应用明确编码/规范化后的 UTF-8 字节，数据库排序不参与计算。 |

全部 164 个业务文本列的实库 collation 已核实为 bin，没有“原敏感却仍使用 ai_ci”的业务文本列。DataLayerIT 对全部字段类型/排序核对；AuthHttpIT.criticalColumnCollationsRetainPostgresCaseAndSpaceComparisons 对 18 个关键列实取 collation，断言 ABC!=abc、value!=value+空格、JSON 中 A!=a。

注意：SHA-256 的输入改变大小写会改变哈希，这是原行为；“不受 collation 影响”指数据库不会改写原文/参与应用哈希，不代表可以忽略哈希输入或复合幂等键的大小写。
