package com.duozhi.outbound.operations.service;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Internal account allocations and imported voice charges never create a payment. */
@Service
public class AccountResources {
    private final WorkspaceDocuments docs;
    private final Config config;

    public AccountResources(WorkspaceDocuments docs, Config config) {
        this.docs = docs;
        this.config = config;
    }

    private String mode() {
        return config.production() ? "statement" : "simulation";
    }

    public Map<String, Object> profile(long tenant, Object user) {
        var d = docs.find(tenant, "account_profile", user.toString());
        return d == null
                ? Map.of(
                        "revision",
                        0,
                        "draft",
                        Map.of("name", "账号资源", "expires_at", "", "balance_enabled", false))
                : d;
    }

    public BigDecimal balance(long tenant, Object user) {
        var credits =
                docs.jdbc.queryForObject(
                        "SELECT COALESCE(SUM(amount),0) FROM ops_account_transfer WHERE tenant_id=? AND user_id=? AND source_mode=?",
                        BigDecimal.class,
                        tenant,
                        user,
                        mode());
        var charges =
                docs.jdbc.queryForObject(
                        "SELECT COALESCE(SUM(v.signed_amount),0) FROM voice_statement_entry v JOIN callsession c ON c.tenant_id=v.tenant_id AND c.id=v.call_id LEFT JOIN campaign g ON g.tenant_id=c.tenant_id AND g.id=c.campaign_id WHERE v.tenant_id=? AND v.kind='charge' AND v.source_mode=? AND COALESCE(g.created_by,c.human_agent_id)=?",
                        BigDecimal.class,
                        tenant,
                        mode(),
                        user);
        return credits.add(charges);
    }

    public boolean unexpired(long tenant, Object user) {
        var p = WorkspaceDocuments.map(profile(tenant, user).get("draft"));
        String expires = Objects.toString(p.get("expires_at"), "");
        return expires.isBlank() || Instant.now().isBefore(Instant.parse(expires));
    }

    public boolean available(long tenant, Object user) {
        if (user == null) return true;
        var batch = com.duozhi.outbound.dial.domain.DialBatchScope.current();
        String key = "account-available:" + tenant + ":" + user;
        if (batch != null && batch.hours.containsKey(key)) return batch.hours.get(key);
        var row = docs.one("SELECT enabled FROM sys_user WHERE tenant_id=? AND id=?", tenant, user);
        if (row == null || !Boolean.TRUE.equals(row.get("enabled"))) return false;
        var p = WorkspaceDocuments.map(profile(tenant, user).get("draft"));
        String expires = Objects.toString(p.get("expires_at"), "");
        if (!expires.isBlank() && !Instant.now().isBefore(Instant.parse(expires))) return false;
        boolean result =
                !Boolean.TRUE.equals(p.get("balance_enabled"))
                        || balance(tenant, user).signum() > 0;
        if (batch != null) batch.hours.put(key, result);
        return result;
    }

    public Object save(Identity i, long user, Map<String, Object> body) {
        if (docs.one("SELECT id FROM sys_user WHERE tenant_id=? AND id=?", i.tenant(), user)
                == null) throw new ApiError(404, "账号不存在");
        var value = WorkspaceDocuments.map(body.get("config"));
        String expires = Objects.toString(value.get("expires_at"), "");
        try {
            if (!expires.isBlank()) Instant.parse(expires);
        } catch (DateTimeException e) {
            throw new ApiError(422, "到期时间须包含时区");
        }
        if (!(value.get("balance_enabled") instanceof Boolean)) throw new ApiError(422, "余额控制选项无效");
        return docs.save(
                i,
                "account_profile",
                "" + user,
                "",
                Map.of(
                        "name",
                        "账号资源",
                        "expires_at",
                        expires,
                        "balance_enabled",
                        value.get("balance_enabled")),
                WorkspaceDocuments.number(body.get("revision")));
    }

    public Object allocate(Identity i, long user, Map<String, Object> b) {
        String key = WorkspaceDocuments.text(b, "request_key", 64, true),
                note = WorkspaceDocuments.text(b, "note", 300, true);
        BigDecimal amount;
        try {
            amount = new BigDecimal(Objects.toString(b.get("amount"), ""));
            if (amount.signum() == 0
                    || amount.scale() > 2
                    || amount.abs().compareTo(new BigDecimal("10000000")) > 0)
                throw new NumberFormatException();
        } catch (NumberFormatException e) {
            throw new ApiError(422, "金额须为非零金额，最多两位小数");
        }
        var change = amount;
        return docs.tx.serialized(
                () -> {
                    docs.jdbc.queryForObject(
                            "SELECT id FROM tenant WHERE id=? FOR UPDATE", Long.class, i.tenant());
                    if (docs.one(
                                    "SELECT id FROM sys_user WHERE tenant_id=? AND id=? AND enabled=1",
                                    i.tenant(),
                                    user)
                            == null) throw new ApiError(422, "账号不可用");
                    var old =
                            docs.one(
                                    "SELECT * FROM ops_account_transfer WHERE tenant_id=? AND request_key=?",
                                    i.tenant(),
                                    key);
                    if (old != null) {
                        if (((Number) old.get("user_id")).longValue() != user
                                || new BigDecimal(old.get("amount").toString()).compareTo(change)
                                        != 0
                                || !note.equals(old.get("note")))
                            throw new ApiError(409, "重复请求内容不同");
                        return Map.of("replayed", true);
                    }
                    var tenantBalance =
                            docs.jdbc.queryForMap(
                                    "SELECT COUNT(CASE WHEN kind='opening' THEN 1 END) AS n,COALESCE(SUM(signed_amount),0) AS balance FROM voice_statement_entry WHERE tenant_id=? AND source_mode=?",
                                    i.tenant(),
                                    mode());
                    if (((Number) tenantBalance.get("n")).intValue() != 1)
                        throw new ApiError(409, "请先导入有效语音期初余额");
                    BigDecimal outstanding = BigDecimal.ZERO;
                    for (var row :
                            docs.jdbc.queryForList(
                                    "SELECT DISTINCT user_id FROM ops_account_transfer WHERE tenant_id=? AND source_mode=?",
                                    i.tenant(),
                                    mode()))
                        outstanding =
                                outstanding.add(
                                        balance(i.tenant(), row.get("user_id"))
                                                .max(BigDecimal.ZERO));
                    var current = balance(i.tenant(), user);
                    if (change.signum() < 0 && current.add(change).signum() < 0)
                        throw new ApiError(409, "回收额度超过账号剩余额度");
                    var increase =
                            current.add(change)
                                    .max(BigDecimal.ZERO)
                                    .subtract(current.max(BigDecimal.ZERO));
                    if (change.signum() > 0
                            && outstanding
                                            .add(increase)
                                            .compareTo(
                                                    new BigDecimal(
                                                            tenantBalance
                                                                    .get("balance")
                                                                    .toString()))
                                    > 0) throw new ApiError(409, "分配超过租户可用余额");
                    docs.jdbc.update(
                            "INSERT INTO ops_account_transfer(tenant_id,user_id,request_key,amount,note,actor_id,source_mode,created_at) VALUES(?,?,?,?,?,?,?,UTC_TIMESTAMP(3))",
                            i.tenant(),
                            user,
                            key,
                            change,
                            note,
                            i.userId(),
                            mode());
                    docs.recordTransition(i, "account_profile", "" + user, "allocation");
                    return Map.of("balance", balance(i.tenant(), user));
                });
    }

    public List<Map<String, Object>> list(Identity i) {
        var rows =
                docs.jdbc.queryForList(
                        "SELECT u.id,u.username,u.full_name,u.role,u.enabled,IF(u.role='admin',1,COALESCE(p.can_export,0)) AS can_export,IF(u.role='admin',1,COALESCE(p.can_unmask,0)) AS can_unmask FROM sys_user u LEFT JOIN ops_permission p ON p.tenant_id=u.tenant_id AND p.user_id=u.id WHERE u.tenant_id=? ORDER BY u.id LIMIT 2000",
                        i.tenant());
        for (var row : rows) {
            var p = profile(i.tenant(), row.get("id"));
            row.put("profile", p);
            row.put("balance", balance(i.tenant(), row.get("id")));
            var cap = docs.find(i.tenant(), "owner_dial_limit", row.get("id").toString());
            row.put(
                    "concurrency",
                    cap == null ? null : WorkspaceDocuments.map(cap.get("draft")).get("limit"));
        }
        return rows;
    }
}
