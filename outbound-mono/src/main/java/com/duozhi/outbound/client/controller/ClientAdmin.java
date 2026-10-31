package com.duozhi.outbound.client.controller;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.client.dao.ClientStore;
import com.duozhi.outbound.client.security.ClientAuthenticator;
import com.duozhi.outbound.client.service.*;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.system.service.Audit;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.*;

/** Staff management of client organisations, their campaigns, accounts and delivered leads. */
@RestController
@RequestMapping("/api/v1/ops")
public class ClientAdmin {
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]{2,64}");
    private final ClientStore db;
    private final ClientAccess access;
    private final ClientSettings settings;
    private final ClientAccounts accounts;
    private final ClientDelivery delivery;
    private final ClientLeadQuery leads;
    private final ClientAuthenticator clients;
    private final Transactions tx;
    private final Audit audit;

    public ClientAdmin(
            ClientStore db,
            ClientAccess access,
            ClientSettings settings,
            ClientAccounts accounts,
            ClientDelivery delivery,
            ClientLeadQuery leads,
            ClientAuthenticator clients,
            Transactions tx,
            Audit audit) {
        this.db = db;
        this.access = access;
        this.settings = settings;
        this.accounts = accounts;
        this.delivery = delivery;
        this.leads = leads;
        this.clients = clients;
        this.tx = tx;
        this.audit = audit;
    }

    private static String text(Object value, int max, boolean required, String label) {
        String s = Objects.toString(value, "").trim();
        if (required && s.isEmpty()) throw new ApiError(422, label + "不能为空");
        if (s.length() > max) throw new ApiError(422, label + "过长");
        return s.isEmpty() ? null : s;
    }

    private static LocalDate date(Object value, String label) {
        if (value == null || value.toString().isBlank()) return null;
        try {
            return LocalDate.parse(
                    value.toString().substring(0, Math.min(10, value.toString().length())));
        } catch (Exception e) {
            throw new ApiError(422, label + "格式应为 yyyy-MM-dd");
        }
    }

    private Map<String, Object> orgFields(Map<String, Object> body, Map<String, Object> current) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (current != null) merged.putAll(current);
        merged.putAll(body);
        LocalDate start = date(merged.get("contract_start"), "合同开始日期");
        LocalDate end = date(merged.get("contract_end"), "合同结束日期");
        if (start != null && end != null && end.isBefore(start))
            throw new ApiError(422, "合同结束日期不能早于开始日期");
        String status = Objects.toString(merged.getOrDefault("status", "active"), "active");
        if (!Set.of("active", "disabled").contains(status)) throw new ApiError(422, "不支持的状态");
        return JsonSupport.map(
                "name",
                text(merged.get("name"), 200, true, "企业名称"),
                "contact_name",
                text(merged.get("contact_name"), 100, false, "联系人"),
                "contact_phone",
                text(merged.get("contact_phone"), 32, false, "联系电话"),
                "contract_start",
                start,
                "contract_end",
                end,
                "status",
                status,
                "remark",
                text(merged.get("remark"), 500, false, "备注"));
    }

    @GetMapping("/clients")
    public List<Map<String, Object>> list(
            @RequestParam
            Map<String, String> q, HttpServletRequest request) {
        Identity i = access.staff(request);
        int page = (int) Math.max(1, JsonSupport.integer(q.getOrDefault("page", "1"), 1));
        int size =
                Math.min(200, Math.max(1, JsonSupport.integer(q.getOrDefault("size", "50"), 50)));
        String status = q.get("status");
        if (status != null && !status.isBlank() && !Set.of("active", "disabled").contains(status))
            throw new ApiError(422, "不支持的状态");
        String keyword = q.get("keyword");
        if (keyword != null && keyword.length() > 100) throw new ApiError(422, "关键词过长");
        return db.rows(
                "orgList",
                JsonSupport.map(
                        "tenantId",
                        i.tenant(),
                        "scopeUser",
                        access.scopeUser(i),
                        "status",
                        status == null || status.isBlank() ? null : status,
                        "keyword",
                        keyword == null || keyword.isBlank() ? null : keyword.trim(),
                        "since",
                        Store.now().minusDays(7),
                        "limit",
                        size,
                        "offset",
                        (page - 1) * size));
    }

    @PostMapping("/clients")
    public Map<String, Object> create(
            @RequestBody
            Map<String, Object> body, HttpServletRequest request) {
        Identity i = access.requireAdmin(request);
        String code = Objects.toString(body.get("code"), "").trim();
        if (!CODE.matcher(code).matches()) throw new ApiError(422, "企业编码须为 2-64 位字母、数字、_ 或 -");
        Map<String, Object> p = orgFields(body, null);
        p.putAll(JsonSupport.map("tenantId", i.tenant(), "code", code, "actorId", access.actor(i)));
        return tx.run(
                () -> {
                    try {
                        db.write("orgInsert", p);
                    } catch (DuplicateKeyException e) {
                        throw new ApiError(409, "企业编码已存在");
                    }
                    long id = ((Number) p.get("client_id")).longValue();
                    audit.record(i, "create", "client_org", id, "code=" + code);
                    return access.client(i, id);
                });
    }

    @GetMapping("/clients/{id}")
    public Map<String, Object> get(@PathVariable
    long id, HttpServletRequest request) {
        Identity i = access.staff(request);
        Map<String, Object> org = new LinkedHashMap<>(access.client(i, id));
        org.put("active", clients.active(org));
        org.put("can_manage_org", access.admin(i));
        return org;
    }

    @PutMapping("/clients/{id}")
    public Map<String, Object> update(
            @PathVariable
            long id,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest request) {
        Identity i = access.requireAdmin(request);
        return tx.run(
                () -> {
                    Map<String, Object> current = access.client(i, id);
                    Map<String, Object> p = orgFields(body, current);
                    p.putAll(
                            JsonSupport.map(
                                    "tenantId",
                                    i.tenant(),
                                    "clientId",
                                    id,
                                    "revision",
                                    JsonSupport.integer(body.get("revision"), -1)));
                    if (db.write("orgUpdate", p) != 1) throw new ApiError(409, "企业信息已被修改，请刷新后重试");
                    audit.record(
                            i,
                            "update",
                            "client_org",
                            id,
                            "status=" + p.get("status") + ",contract_end=" + p.get("contract_end"));
                    return access.client(i, id);
                });
    }

    @GetMapping("/clients/{id}/settings")
    public Map<String, Object> settings(@PathVariable
    long id, HttpServletRequest request) {
        Identity i = access.staff(request);
        access.client(i, id);
        return settings.read(i.tenant(), id);
    }

    @PutMapping("/clients/{id}/settings")
    public Map<String, Object> saveSettings(
            @PathVariable
            long id,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest request) {
        Identity i = access.staff(request);
        return tx.run(
                () -> {
                    db.one("orgLock", JsonSupport.map("tenantId", i.tenant(), "clientId", id));
                    access.client(i, id);
                    Map<String, Object> saved =
                            settings.save(i.tenant(), id, body, access.actor(i));
                    audit.record(
                            i, "settings", "client_org", id, "revision=" + saved.get("revision"));
                    return saved;
                });
    }

    // ---- campaign binding ----

    @GetMapping("/clients/{id}/campaigns")
    public List<Map<String, Object>> campaigns(@PathVariable
    long id, HttpServletRequest request) {
        Identity i = access.staff(request);
        access.client(i, id);
        return db.rows("bindingsByOrg", JsonSupport.map("tenantId", i.tenant(), "clientId", id));
    }

    @PostMapping("/clients/{id}/campaigns")
    public Map<String, Object> bindMany(
            @PathVariable
            long id,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest request) {
        Identity i = access.staff(request);
        if (!(body.get("campaign_ids") instanceof List<?> ids) || ids.isEmpty() || ids.size() > 200)
            throw new ApiError(422, "请选择 1-200 个任务");
        boolean history = Boolean.TRUE.equals(body.get("deliver_history"));
        var results = new ArrayList<Map<String, Object>>();
        for (Object campaign : ids)
            results.add(bind(i, JsonSupport.integer(campaign, -1), id, history, null));
        return JsonSupport.map("results", results);
    }

    @DeleteMapping("/clients/{id}/campaigns/{campaignId}")
    public Map<String, Object> unbind(
            @PathVariable
            long id, @PathVariable
            int campaignId, HttpServletRequest request) {
        Identity i = access.staff(request);
        access.client(i, id);
        Map<String, Object> current = binding(i.tenant(), campaignId);
        if (current == null || ((Number) current.get("client_id")).longValue() != id)
            throw new ApiError(404, "该任务未绑定此企业");
        return bind(i, campaignId, null, false, null);
    }

    private Map<String, Object> binding(long tenant, int campaignId) {
        return db.one("bindingGet", JsonSupport.map("tenantId", tenant, "campaignId", campaignId));
    }

    @GetMapping("/campaigns/{campaignId}/client")
    public Map<String, Object> campaignClient(
            @PathVariable
            int campaignId, HttpServletRequest request) {
        Identity i = access.staff(request);
        Map<String, Object> current = binding(i.tenant(), campaignId);
        return current == null
                ? JsonSupport.map("campaign_id", campaignId, "client_id", null)
                : current;
    }

    @PutMapping("/campaigns/{campaignId}/client")
    public Map<String, Object> setCampaignClient(
            @PathVariable
            int campaignId,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest request) {
        Identity i = access.staff(request);
        Object client = body.get("client_id");
        Long target = client == null ? null : (long) JsonSupport.integer(client, -1);
        if (target != null && target < 1) throw new ApiError(422, "企业租户无效");
        Integer revision =
                body.get("revision") == null ? null : JsonSupport.integer(body.get("revision"), -1);
        return bind(
                i, campaignId, target, Boolean.TRUE.equals(body.get("deliver_history")), revision);
    }

    @GetMapping("/campaigns/{campaignId}/client/log")
    public List<Map<String, Object>> campaignLog(
            @PathVariable
            int campaignId, HttpServletRequest request) {
        Identity i = access.staff(request);
        return db.rows(
                "bindingLog", JsonSupport.map("tenantId", i.tenant(), "campaignId", campaignId));
    }

    @GetMapping("/clients/bindings")
    public List<Map<String, Object>> bindings(
            @RequestParam(required = false)
            String campaign_ids, HttpServletRequest request) {
        Identity i = access.staff(request);
        List<Integer> ids = null;
        if (campaign_ids != null && !campaign_ids.isBlank()) {
            ids = new ArrayList<>();
            for (String part : campaign_ids.split(",")) {
                int n = JsonSupport.integer(part.trim(), -1);
                if (n < 1) throw new ApiError(422, "任务 ID 无效");
                ids.add(n);
            }
            if (ids.size() > 500) throw new ApiError(422, "一次最多查询 500 个任务");
        }
        return db.rows("bindingsFor", JsonSupport.map("tenantId", i.tenant(), "ids", ids));
    }

    /**
     * Binds, rebinds or unbinds one campaign. Only later deliveries follow the new binding;
     * delivered leads keep their original client.
     */
    private Map<String, Object> bind(
            Identity i, int campaignId, Long target, boolean history, Integer revision) {
        if (campaignId < 1) throw new ApiError(422, "任务 ID 无效");
        Map<String, Object> result =
                tx.run(
                        () -> {
                            Map<String, Object> campaign =
                                    db.one(
                                            "campaignLock",
                                            JsonSupport.map(
                                                    "tenantId",
                                                    i.tenant(),
                                                    "campaignId",
                                                    campaignId));
                            if (campaign == null) throw new ApiError(404, "任务不存在");
                            Map<String, Object> current = binding(i.tenant(), campaignId);
                            if (revision != null
                                    && current != null
                                    && ((Number) current.get("revision")).intValue() != revision)
                                throw new ApiError(409, "任务归属已被修改，请刷新后重试");
                            Long from =
                                    current == null
                                            ? null
                                            : ((Number) current.get("client_id")).longValue();
                            if (from != null) access.client(i, from);
                            if (target != null) {
                                Map<String, Object> org = access.client(i, target);
                                if (!"active".equals(org.get("status")))
                                    throw new ApiError(422, "停用中的企业租户不能绑定任务");
                            }
                            if (Objects.equals(from, target))
                                return JsonSupport.map("changed", false);
                            Map<String, Object> p =
                                    JsonSupport.map(
                                            "tenantId",
                                            i.tenant(),
                                            "campaignId",
                                            campaignId,
                                            "clientId",
                                            target,
                                            "deliver_history",
                                            history,
                                            "actorId",
                                            access.actor(i),
                                            "now",
                                            Store.now(),
                                            "fromClient",
                                            from,
                                            "toClient",
                                            target);
                            if (target == null) db.write("bindingDelete", p);
                            else db.write("bindingUpsert", p);
                            db.write("bindingLogInsert", p);
                            audit.record(
                                    i,
                                    target == null ? "unbind" : "bind",
                                    "campaign",
                                    campaignId,
                                    "from=" + from + ",to=" + target);
                            return JsonSupport.map("changed", true);
                        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("campaign_id", campaignId);
        out.put("changed", result.get("changed"));
        Map<String, Object> current = binding(i.tenant(), campaignId);
        out.put("binding", current);
        if (target != null && history && Boolean.TRUE.equals(result.get("changed")))
            out.put("backfill", delivery.backfill(i.tenant(), campaignId, 0, 500));
        return out;
    }

    @PostMapping("/clients/{id}/backfill")
    public Map<String, Object> backfill(
            @PathVariable
            long id,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest request) {
        Identity i = access.staff(request);
        access.client(i, id);
        int campaign = JsonSupport.integer(body.get("campaign_id"), -1);
        Map<String, Object> current = binding(i.tenant(), campaign);
        if (current == null || ((Number) current.get("client_id")).longValue() != id)
            throw new ApiError(422, "只能回溯当前绑定到此企业的任务");
        long after = Math.max(0, JsonSupport.integer(body.get("after_id"), 0));
        Map<String, Object> result = delivery.backfill(i.tenant(), campaign, after, 500);
        audit.record(i, "backfill", "client_org", id, "campaign=" + campaign + ",after=" + after);
        return result;
    }

    // ---- accounts ----

    @GetMapping("/clients/{id}/users")
    public List<Map<String, Object>> users(@PathVariable
    long id, HttpServletRequest request) {
        Identity i = access.staff(request);
        access.client(i, id);
        var out = new ArrayList<Map<String, Object>>();
        for (var row :
                db.rows("usersByOrg", JsonSupport.map("tenantId", i.tenant(), "clientId", id)))
            out.add(accounts.publicUser(row));
        return out;
    }

    @PostMapping("/clients/{id}/users")
    public Map<String, Object> createUser(
            @PathVariable
            long id,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest request) {
        Identity i = access.staff(request);
        return tx.run(
                () -> {
                    access.client(i, id);
                    String role = Objects.toString(body.getOrDefault("role", "owner"), "owner");
                    Map<String, Object> user = accounts.create(i.tenant(), id, body, role);
                    audit.record(
                            i,
                            "create_user",
                            "client_org",
                            id,
                            "username=" + user.get("username") + ",role=" + role);
                    return user;
                });
    }

    @PutMapping("/clients/{id}/users/{userId}")
    public Map<String, Object> updateUser(
            @PathVariable
            long id,
            @PathVariable
            long userId,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest request) {
        Identity i = access.staff(request);
        return tx.run(
                () -> {
                    access.client(i, id);
                    Map<String, Object> user = accounts.user(i.tenant(), userId);
                    if (user == null
                            || ((Number) user.get("tenant_id")).longValue() != i.tenant()
                            || ((Number) user.get("client_id")).longValue() != id)
                        throw new ApiError(404, "账号不存在");
                    String action = Objects.toString(body.get("action"), "update");
                    Map<String, Object> out;
                    switch (action) {
                        case "reset_password" -> {
                            String password = accounts.reset(i.tenant(), userId);
                            out = accounts.publicUser(accounts.user(i.tenant(), userId));
                            out.put("initial_password", password);
                        }
                        case "revoke" -> {
                            accounts.revoke(i.tenant(), userId);
                            out = accounts.publicUser(accounts.user(i.tenant(), userId));
                        }
                        case "update" -> out = accounts.update(i.tenant(), id, user, body);
                        default -> throw new ApiError(422, "不支持的操作");
                    }
                    audit.record(i, "user_" + action, "client_user", userId, "client=" + id);
                    return out;
                });
    }

    // ---- delivered leads ----

    @GetMapping("/clients/{id}/leads")
    public Map<String, Object> leads(
            @PathVariable
            long id,
            @RequestParam
            Map<String, String> q,
            HttpServletRequest request) {
        Identity i = access.staff(request);
        access.client(i, id);
        Map<String, Object> page =
                leads.page(leads.filters(i.tenant(), id, null, q, clients.zone()), q);
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) page.get("items");
        page.put("items", leads.outputs(rows, null));
        return page;
    }

    @GetMapping("/clients/{id}/leads/{leadId}")
    public Map<String, Object> lead(
            @PathVariable
            long id, @PathVariable
            long leadId, HttpServletRequest request) {
        Identity i = access.staff(request);
        access.client(i, id);
        Map<String, Object> row =
                db.one(
                        "leadGet",
                        JsonSupport.map("tenantId", i.tenant(), "clientId", id, "leadId", leadId));
        if (row == null) throw new ApiError(404, "线索不存在");
        Map<String, Object> out = leads.output(row, null);
        out.put("events", leads.events(i.tenant(), id, leadId));
        return out;
    }

    @PostMapping("/clients/{id}/leads/{leadId}/withdraw")
    public Map<String, Object> withdraw(
            @PathVariable
            long id,
            @PathVariable
            long leadId,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest request) {
        Identity i = access.staff(request);
        String note = text(body.get("note"), 500, true, "撤回原因");
        return tx.run(
                () -> {
                    access.client(i, id);
                    db.one("orgLock", JsonSupport.map("tenantId", i.tenant(), "clientId", id));
                    Map<String, Object> row =
                            db.one(
                                    "leadGet",
                                    JsonSupport.map(
                                            "tenantId",
                                            i.tenant(),
                                            "clientId",
                                            id,
                                            "leadId",
                                            leadId));
                    if (row == null) throw new ApiError(404, "线索不存在");
                    if (!delivery.withdraw(
                            i.tenant(), id, leadId, "manual", note, "staff", access.actor(i), null))
                        throw new ApiError(409, "线索已撤回");
                    audit.record(i, "withdraw", "client_lead", leadId, "client=" + id);
                    return lead(id, leadId, request);
                });
    }

    @PutMapping("/clients/{id}/leads/{leadId}/dispute")
    public Map<String, Object> decideDispute(
            @PathVariable
            long id,
            @PathVariable
            long leadId,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest request) {
        Identity i = access.staff(request);
        String decision = Objects.toString(body.get("decision"), "");
        if (!Set.of("accepted", "rejected").contains(decision))
            throw new ApiError(422, "审核结果须为 accepted 或 rejected");
        String note = text(body.get("note"), 1000, true, "审核说明");
        return tx.run(
                () -> {
                    access.client(i, id);
                    Map<String, Object> row =
                            db.one(
                                    "leadGet",
                                    JsonSupport.map(
                                            "tenantId",
                                            i.tenant(),
                                            "clientId",
                                            id,
                                            "leadId",
                                            leadId));
                    if (row == null) throw new ApiError(404, "线索不存在");
                    if (!"submitted".equals(row.get("dispute_state")))
                        throw new ApiError(409, "该线索没有待审核的申诉");
                    if (db.write(
                                    "leadDispute",
                                    JsonSupport.map(
                                            "tenantId",
                                            i.tenant(),
                                            "clientId",
                                            id,
                                            "leadId",
                                            leadId,
                                            "revision",
                                            row.get("revision"),
                                            "dispute_state",
                                            decision,
                                            "dispute_note",
                                            row.get("dispute_note")))
                            != 1) throw new ApiError(409, "线索已变化，请刷新");
                    delivery.event(
                            i.tenant(),
                            id,
                            leadId,
                            "staff",
                            access.actor(i),
                            "dispute_" + decision,
                            JsonSupport.map("note", note),
                            "dispute:" + leadId + ":" + row.get("revision"));
                    audit.record(i, "dispute_" + decision, "client_lead", leadId, "client=" + id);
                    return lead(id, leadId, request);
                });
    }

    @GetMapping("/clients/{id}/stats")
    public Map<String, Object> stats(
            @PathVariable
            long id,
            @RequestParam(defaultValue = "30")
            int days,
            HttpServletRequest request) {
        Identity i = access.staff(request);
        access.client(i, id);
        if (days < 1 || days > 366) throw new ApiError(422, "统计天数须为 1-366");
        return leads.stats(i.tenant(), id, null, days, clients.zone());
    }

    // ---- delivery specialists ----

    @GetMapping("/clients/{id}/staff")
    public List<Map<String, Object>> staff(@PathVariable
    long id, HttpServletRequest request) {
        Identity i = access.staff(request);
        access.client(i, id);
        return db.rows("staffScopes", JsonSupport.map("tenantId", i.tenant(), "clientId", id));
    }

    @PutMapping("/clients/{id}/staff")
    public List<Map<String, Object>> saveStaff(
            @PathVariable
            long id,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest request) {
        Identity i = access.requireAdmin(request);
        if (!(body.get("user_ids") instanceof List<?> ids) || ids.size() > 200)
            throw new ApiError(422, "user_ids 必须是最多 200 个坐席 ID 的数组");
        return tx.run(
                () -> {
                    access.client(i, id);
                    Map<String, Object> p = JsonSupport.map("tenantId", i.tenant(), "clientId", id);
                    db.write("staffClear", p);
                    for (Object user : new LinkedHashSet<>(ids)) {
                        p.put("userId", JsonSupport.integer(user, -1));
                        if (db.write("staffAdd", p) != 1)
                            throw new ApiError(422, "只能授权本租户的坐席账号：" + user);
                    }
                    audit.record(i, "staff", "client_org", id, "users=" + ids);
                    return staff(id, request);
                });
    }
}
