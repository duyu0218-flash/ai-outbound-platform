# 租户条件例外清单

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
