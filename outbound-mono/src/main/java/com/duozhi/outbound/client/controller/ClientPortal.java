package com.duozhi.outbound.client.controller;

import com.duozhi.outbound.auth.service.Crypto;
import com.duozhi.outbound.client.dao.ClientStore;
import com.duozhi.outbound.client.security.ClientAuthenticator;
import com.duozhi.outbound.client.security.ClientPrincipal;
import com.duozhi.outbound.client.service.*;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.common.support.JsonSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.apache.commons.csv.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Client portal API. Every query is bound to the authenticated client and, for members, to them.
 */
@RestController
@RequestMapping("/api/v1/client")
public class ClientPortal {
    private static final int PHONE_REVEALS_PER_HOUR = 60;
    private final ClientStore db;
    private final ClientAccounts accounts;
    private final ClientSettings settings;
    private final ClientDelivery delivery;
    private final ClientLeadQuery leads;
    private final ClientAuthenticator clients;
    private final Crypto crypto;
    private final Transactions tx;

    public ClientPortal(
            ClientStore db,
            ClientAccounts accounts,
            ClientSettings settings,
            ClientDelivery delivery,
            ClientLeadQuery leads,
            ClientAuthenticator clients,
            Crypto crypto,
            Transactions tx) {
        this.db = db;
        this.accounts = accounts;
        this.settings = settings;
        this.delivery = delivery;
        this.leads = leads;
        this.clients = clients;
        this.crypto = crypto;
        this.tx = tx;
    }

    private void event(ClientPrincipal p, Long lead, String action, Map<String, Object> detail) {
        delivery.event(
                p.tenant(),
                p.clientId(),
                lead,
                "client",
                p.userId(),
                action,
                detail,
                action + ":" + p.userId() + ":" + UUID.randomUUID());
    }

    /** Effective abilities: platform ceiling per client, narrowed by the owner for members. */
    private Map<String, Object> abilities(ClientPrincipal p, Map<String, Object> config) {
        Map<String, Object> visible = ClientSettings.visible(config);
        return JsonSupport.map(
                "phone_reveal",
                Boolean.TRUE.equals(config.get("phone_reveal")) && p.granted("phone_reveal"),
                "recording",
                Boolean.TRUE.equals(visible.get("recording")) && p.granted("recording"),
                "transcript",
                Boolean.TRUE.equals(visible.get("transcript")),
                "export",
                p.owner() && Boolean.TRUE.equals(ClientSettings.export(config).get("enabled")),
                "manage_members",
                p.owner(),
                "assign",
                p.owner(),
                "dispute_days",
                config.get("dispute_days"));
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody
    Map<String, Object> body) {
        if (!clients.enabled()) throw new ApiError(404, "Not Found");
        String username = Objects.toString(body.get("username"), "");
        String password = Objects.toString(body.get("password"), "");
        if (username.isEmpty()
                || username.length() > 200
                || password.isEmpty()
                || password.length() > 1024) throw new ApiError(422, "请输入用户名和密码");
        Map<String, Object> user = tx.run(() -> accounts.login(username, password));
        if (user == null) throw new ApiError(401, "用户名或密码错误");
        delivery.event(
                ((Number) user.get("tenant_id")).longValue(),
                ((Number) user.get("client_id")).longValue(),
                null,
                "client",
                ((Number) user.get("id")).longValue(),
                "login",
                Collections.emptyMap(),
                "login:" + user.get("id") + ":" + UUID.randomUUID());
        return JsonSupport.map(
                "access_token",
                crypto.clientToken(user),
                "token_type",
                "bearer",
                "must_change_password",
                user.get("must_change_password"),
                "user",
                accounts.publicUser(user));
    }

    @PostMapping("/auth/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        ClientPrincipal p = ClientPrincipal.from(request);
        accounts.revoke(p.tenant(), p.userId());
        return JsonSupport.map("result", "ok");
    }

    @PostMapping("/auth/password")
    public Map<String, Object> password(
            @RequestBody
            Map<String, Object> body, HttpServletRequest request) {
        ClientPrincipal p = ClientPrincipal.from(request);
        String current = Objects.toString(body.get("old_password"), "");
        String next = Objects.toString(body.get("new_password"), "");
        if (!accounts.verify(p.tenant(), p.userId(), current)) throw new ApiError(422, "原密码不正确");
        if (current.equals(next)) throw new ApiError(422, "新密码不能与原密码相同");
        accounts.password(p.tenant(), p.userId(), next, false);
        event(p, null, "password_changed", Collections.emptyMap());
        // The version bump revoked the old token; hand back a fresh one for this session.
        return JsonSupport.map(
                "access_token", crypto.clientToken(accounts.user(p.tenant(), p.userId())));
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        ClientPrincipal p = ClientPrincipal.from(request);
        Map<String, Object> user = accounts.publicUser(accounts.user(p.tenant(), p.userId()));
        Map<String, Object> org =
                db.one("orgGet", JsonSupport.map("tenantId", p.tenant(), "clientId", p.clientId()));
        Map<String, Object> config = settings.read(p.tenant(), p.clientId());
        user.put(
                "client",
                JsonSupport.map(
                        "id",
                        p.clientId(),
                        "name",
                        org.get("name"),
                        "code",
                        org.get("code"),
                        "contract_end",
                        org.get("contract_end")));
        user.put("abilities", abilities(p, config));
        user.put("visible", ClientSettings.visible(config));
        return user;
    }

    // ---- leads ----

    @GetMapping("/leads")
    public Map<String, Object> leads(
            @RequestParam
            Map<String, String> q, HttpServletRequest request) {
        ClientPrincipal p = ClientPrincipal.from(request);
        if (!p.owner()) q = withoutUnassigned(q);
        Map<String, Object> config = settings.read(p.tenant(), p.clientId());
        Map<String, Object> page =
                leads.page(
                        leads.filters(
                                p.tenant(), p.clientId(), p.assigneeFilter(), q, clients.zone()),
                        q);
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) page.get("items");
        page.put("items", leads.outputs(rows, ClientSettings.visible(config)));
        return page;
    }

    private static Map<String, String> withoutUnassigned(Map<String, String> q) {
        var copy = new LinkedHashMap<>(q);
        copy.remove("unassigned");
        return copy;
    }

    @GetMapping("/leads/summary")
    public Map<String, Object> summary(HttpServletRequest request) {
        ClientPrincipal p = ClientPrincipal.from(request);
        return db.one(
                "leadSummary",
                JsonSupport.map(
                        "tenantId",
                        p.tenant(),
                        "clientId",
                        p.clientId(),
                        "assignee",
                        p.assigneeFilter()));
    }

    private Map<String, Object> row(ClientPrincipal p, long id) {
        Map<String, Object> row =
                db.one(
                        "leadGet",
                        JsonSupport.map(
                                "tenantId",
                                p.tenant(),
                                "clientId",
                                p.clientId(),
                                "leadId",
                                id,
                                "assignee",
                                p.assigneeFilter()));
        if (row == null) throw new ApiError(404, "线索不存在");
        return row;
    }

    @GetMapping("/leads/{id}")
    public Map<String, Object> lead(@PathVariable
    long id, HttpServletRequest request) {
        ClientPrincipal p = ClientPrincipal.from(request);
        Map<String, Object> row = row(p, id);
        if (row.get("first_viewed_at") == null) {
            db.write(
                    "leadViewed",
                    JsonSupport.map("tenantId", p.tenant(), "leadId", id, "now", Store.now()));
            event(p, id, "viewed", Collections.emptyMap());
            row = row(p, id);
        }
        Map<String, Object> config = settings.read(p.tenant(), p.clientId());
        Map<String, Object> out = leads.output(row, ClientSettings.visible(config));
        out.put("abilities", abilities(p, config));
        out.put("events", clientEvents(leads.events(p.tenant(), p.clientId(), id)));
        return out;
    }

    /** Clients see the lead history without staff identities or internal QA versions. */
    private static List<Map<String, Object>> clientEvents(List<Map<String, Object>> events) {
        var out = new ArrayList<Map<String, Object>>();
        for (var e : events) {
            if (Set.of("refreshed", "phone_revealed", "recording_played", "viewed")
                    .contains(e.get("action"))) continue;
            var item = new LinkedHashMap<String, Object>();
            item.put("action", e.get("action"));
            item.put("actor_kind", e.get("actor_kind"));
            item.put("created_at", e.get("created_at"));
            @SuppressWarnings("unchecked")
            var detail = (Map<String, Object>) e.get("detail");
            for (String key : List.of("reason", "note", "follow_state", "invalid_reason"))
                if (detail.containsKey(key)) item.put(key, detail.get(key));
            out.add(item);
        }
        return out;
    }

    private void requireVisible(Map<String, Object> row) {
        if (!"delivered".equals(row.get("status"))) throw new ApiError(409, "线索已撤回");
        if (ClientLeadQuery.truthy(row.get("consent_revoked")))
            throw new ApiError(403, "客户已撤回授权，不能查看该信息");
    }

    @PostMapping("/leads/{id}/phone")
    public Map<String, Object> phone(@PathVariable
    long id, HttpServletRequest request) {
        ClientPrincipal p = ClientPrincipal.from(request);
        Map<String, Object> row = row(p, id);
        Map<String, Object> config = settings.read(p.tenant(), p.clientId());
        if (!Boolean.TRUE.equals(abilities(p, config).get("phone_reveal")))
            throw new ApiError(403, "当前账号无权查看完整号码");
        requireVisible(row);
        long recent =
                db.count(
                        "eventCount",
                        JsonSupport.map(
                                "tenantId",
                                p.tenant(),
                                "clientId",
                                p.clientId(),
                                "action",
                                "phone_revealed",
                                "since",
                                Store.now().minusHours(1),
                                "actorId",
                                p.userId()));
        if (recent >= PHONE_REVEALS_PER_HOUR) throw new ApiError(429, "查看完整号码过于频繁，请稍后再试");
        String phone =
                (String)
                        db.value(
                                "leadPhone", JsonSupport.map("tenantId", p.tenant(), "leadId", id));
        event(p, id, "phone_revealed", Collections.emptyMap());
        return JsonSupport.map("phone", phone);
    }

    @GetMapping("/leads/{id}/recording")
    public Map<String, Object> recording(@PathVariable
    long id, HttpServletRequest request) {
        ClientPrincipal p = ClientPrincipal.from(request);
        Map<String, Object> row = row(p, id);
        if (!Boolean.TRUE.equals(
                abilities(p, settings.read(p.tenant(), p.clientId())).get("recording")))
            throw new ApiError(403, "当前账号无权播放录音");
        requireVisible(row);
        Object url =
                db.value("leadRecording", JsonSupport.map("tenantId", p.tenant(), "leadId", id));
        if (url == null) throw new ApiError(404, "录音暂不可用");
        event(p, id, "recording_played", Collections.emptyMap());
        return JsonSupport.map("url", url);
    }

    @GetMapping("/leads/{id}/transcript")
    public List<Map<String, Object>> transcript(@PathVariable
    long id, HttpServletRequest request) {
        ClientPrincipal p = ClientPrincipal.from(request);
        Map<String, Object> row = row(p, id);
        if (!Boolean.TRUE.equals(
                ClientSettings.visible(settings.read(p.tenant(), p.clientId())).get("transcript")))
            throw new ApiError(403, "未开放通话转写");
        requireVisible(row);
        var out = new ArrayList<Map<String, Object>>();
        for (var turn :
                db.rows("leadTranscript", JsonSupport.map("tenantId", p.tenant(), "leadId", id))) {
            var item = new LinkedHashMap<>(turn);
            item.put("transcript", ClientLeadQuery.maskText(turn.get("transcript")));
            out.add(item);
        }
        return out;
    }

    @PutMapping("/leads/{id}/follow")
    public Map<String, Object> follow(
            @PathVariable
            long id,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest request) {
        ClientPrincipal p = ClientPrincipal.from(request);
        String state = Objects.toString(body.get("follow_state"), "");
        if (!ClientLeadQuery.FOLLOW_STATES.contains(state)) throw new ApiError(422, "不支持的跟进状态");
        String reason =
                body.get("invalid_reason") == null ? null : body.get("invalid_reason").toString();
        if ("invalid".equals(state)) {
            if (!ClientLeadQuery.INVALID_REASONS.contains(Objects.toString(reason, "")))
                throw new ApiError(422, "请选择无效原因");
        } else reason = null;
        String note = Objects.toString(body.get("note"), "").trim();
        if (note.length() > 1000) throw new ApiError(422, "备注过长");
        if ("other".equals(reason) && note.isEmpty()) throw new ApiError(422, "无效原因为“其他”时须填写备注");
        LocalDateTime next = null;
        if (body.get("next_follow_at") != null
                && !body.get("next_follow_at").toString().isBlank()) {
            try {
                next =
                        OffsetDateTime.parse(body.get("next_follow_at").toString())
                                .withOffsetSameInstant(ZoneOffset.UTC)
                                .toLocalDateTime();
            } catch (Exception e) {
                throw new ApiError(422, "下次跟进时间须包含时区");
            }
        }
        String finalReason = reason;
        LocalDateTime finalNext = next;
        return tx.run(
                () -> {
                    Map<String, Object> row = row(p, id);
                    if (!"delivered".equals(row.get("status"))) throw new ApiError(409, "线索已撤回");
                    if (db.write(
                                    "leadFollow",
                                    JsonSupport.map(
                                            "tenantId",
                                            p.tenant(),
                                            "clientId",
                                            p.clientId(),
                                            "leadId",
                                            id,
                                            "revision",
                                            JsonSupport.integer(body.get("revision"), -1),
                                            "follow_state",
                                            state,
                                            "invalid_reason",
                                            finalReason,
                                            "follow_note",
                                            note.isEmpty() ? null : note,
                                            "next_follow_at",
                                            finalNext))
                            != 1) throw new ApiError(409, "线索已被他人更新，请刷新后重试");
                    event(
                            p,
                            id,
                            "follow",
                            JsonSupport.map(
                                    "follow_state",
                                    state,
                                    "invalid_reason",
                                    finalReason,
                                    "note",
                                    note));
                    return lead(id, request);
                });
    }

    @PostMapping("/leads/{id}/dispute")
    public Map<String, Object> dispute(
            @PathVariable
            long id,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest request) {
        ClientPrincipal p = ClientPrincipal.from(request);
        String note = Objects.toString(body.get("note"), "").trim();
        if (note.isEmpty() || note.length() > 1000) throw new ApiError(422, "请填写申诉说明（最多 1000 字）");
        return tx.run(
                () -> {
                    Map<String, Object> row = row(p, id);
                    if (!"delivered".equals(row.get("status"))) throw new ApiError(409, "线索已撤回");
                    if (!"invalid".equals(row.get("follow_state")))
                        throw new ApiError(422, "只有标记为无效的线索可以申诉");
                    if (!"none".equals(row.get("dispute_state"))) throw new ApiError(409, "该线索已申诉");
                    int days =
                            ((Number) settings.read(p.tenant(), p.clientId()).get("dispute_days"))
                                    .intValue();
                    if (((LocalDateTime) row.get("delivered_at"))
                            .plusDays(days)
                            .isBefore(Store.now()))
                        throw new ApiError(422, "已超过申诉期（" + days + " 天）");
                    if (db.write(
                                    "leadDispute",
                                    JsonSupport.map(
                                            "tenantId",
                                            p.tenant(),
                                            "clientId",
                                            p.clientId(),
                                            "leadId",
                                            id,
                                            "revision",
                                            row.get("revision"),
                                            "dispute_state",
                                            "submitted",
                                            "dispute_note",
                                            note))
                            != 1) throw new ApiError(409, "线索已被他人更新，请刷新后重试");
                    event(p, id, "dispute_submitted", JsonSupport.map("note", note));
                    return lead(id, request);
                });
    }

    @PostMapping("/leads/assign")
    public Map<String, Object> assign(
            @RequestBody
            Map<String, Object> body, HttpServletRequest request) {
        ClientPrincipal p = ClientPrincipal.from(request);
        p.requireOwner();
        if (!(body.get("lead_ids") instanceof List<?> raw) || raw.isEmpty() || raw.size() > 500)
            throw new ApiError(422, "请选择 1-500 条线索");
        var ids = new ArrayList<Long>();
        for (Object item : raw) ids.add((long) JsonSupport.integer(item, -1));
        Long assignee =
                body.get("assignee_id") == null
                        ? null
                        : (long) JsonSupport.integer(body.get("assignee_id"), -1);
        return tx.run(
                () -> {
                    if (assignee != null) {
                        Map<String, Object> user = accounts.user(p.tenant(), assignee);
                        if (user == null
                                || ((Number) user.get("client_id")).longValue() != p.clientId()
                                || !Boolean.TRUE.equals(user.get("enabled")))
                            throw new ApiError(422, "请选择本企业启用中的成员");
                    }
                    int changed =
                            db.write(
                                    "leadAssign",
                                    JsonSupport.map(
                                            "tenantId",
                                            p.tenant(),
                                            "clientId",
                                            p.clientId(),
                                            "ids",
                                            ids,
                                            "assignee",
                                            assignee));
                    for (Long id : ids)
                        event(p, id, "assigned", JsonSupport.map("assignee_id", assignee));
                    return JsonSupport.map("updated", changed);
                });
    }

    @GetMapping("/leads/export")
    public ResponseEntity<byte[]> export(
            @RequestParam
            Map<String, String> q, HttpServletRequest request) throws IOException {
        ClientPrincipal p = ClientPrincipal.from(request);
        Map<String, Object> config = settings.read(p.tenant(), p.clientId());
        if (!Boolean.TRUE.equals(abilities(p, config).get("export")))
            throw new ApiError(403, "未开放导出");
        int limit = ((Number) ClientSettings.export(config).get("daily_limit")).intValue();
        LocalDateTime dayStart =
                clients.today()
                        .atStartOfDay(clients.zone())
                        .withZoneSameInstant(ZoneOffset.UTC)
                        .toLocalDateTime();
        long used =
                db.count(
                        "eventDetailSum",
                        JsonSupport.map(
                                "tenantId",
                                p.tenant(),
                                "clientId",
                                p.clientId(),
                                "action",
                                "exported",
                                "since",
                                dayStart));
        long remaining = limit <= 0 ? 10000 : Math.min(10000, limit - used);
        if (remaining <= 0) throw new ApiError(429, "今日导出额度已用完");
        Map<String, Object> filters =
                leads.filters(p.tenant(), p.clientId(), null, q, clients.zone());
        filters.put("limit", (int) remaining);
        List<Map<String, Object>> rows = db.rows("leadList", filters);
        Map<String, Object> visible = ClientSettings.visible(config);
        var out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        try (var csv =
                new CSVPrinter(
                        new OutputStreamWriter(out, StandardCharsets.UTF_8), CSVFormat.DEFAULT)) {
            csv.printRecord(
                    "交付时间", "来源任务", "客户姓名", "号码", "结果", "摘要", "跟进状态", "无效原因", "跟进备注", "交付状态");
            for (var row : rows) {
                var item = leads.output(row, visible);
                csv.printRecord(
                        item.get("delivered_at"),
                        item.get("campaign_name"),
                        safe(item.get("contact_name")),
                        item.get("phone"),
                        item.get("result_code"),
                        safe(item.getOrDefault("summary", "")),
                        item.get("follow_state"),
                        item.get("invalid_reason"),
                        safe(item.get("follow_note")),
                        item.get("status"));
            }
        }
        event(p, null, "exported", JsonSupport.map("rows", rows.size()));
        String name = "leads-" + clients.today() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(out.toByteArray());
    }

    /** Neutralizes spreadsheet formulas in free text. */
    private static String safe(Object value) {
        String s = Objects.toString(value, "");
        return !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 ? "'" + s : s;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(
            @RequestParam(defaultValue = "30")
            int days, HttpServletRequest request) {
        ClientPrincipal p = ClientPrincipal.from(request);
        if (days < 1 || days > 366) throw new ApiError(422, "统计天数须为 1-366");
        return leads.stats(p.tenant(), p.clientId(), p.assigneeFilter(), days, clients.zone());
    }

    // ---- members (owner only) ----

    @GetMapping("/members")
    public List<Map<String, Object>> members(HttpServletRequest request) {
        ClientPrincipal p = ClientPrincipal.from(request);
        var out = new ArrayList<Map<String, Object>>();
        for (var row :
                db.rows(
                        "usersByOrg",
                        JsonSupport.map("tenantId", p.tenant(), "clientId", p.clientId()))) {
            var user = accounts.publicUser(row);
            // Members only need names for display; contact data stays with owners.
            if (!p.owner()) {
                user.keySet().retainAll(Set.of("id", "full_name", "role", "enabled"));
            }
            out.add(user);
        }
        return out;
    }

    @PostMapping("/members")
    public Map<String, Object> createMember(
            @RequestBody
            Map<String, Object> body, HttpServletRequest request) {
        ClientPrincipal p = ClientPrincipal.from(request);
        p.requireOwner();
        return tx.run(
                () -> {
                    Map<String, Object> user =
                            accounts.create(p.tenant(), p.clientId(), body, "member");
                    event(p, null, "member_created", JsonSupport.map("member_id", user.get("id")));
                    return user;
                });
    }

    @PutMapping("/members/{id}")
    public Map<String, Object> updateMember(
            @PathVariable
            long id,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest request) {
        ClientPrincipal p = ClientPrincipal.from(request);
        p.requireOwner();
        return tx.run(
                () -> {
                    Map<String, Object> user = accounts.user(p.tenant(), id);
                    if (user == null
                            || ((Number) user.get("client_id")).longValue() != p.clientId()
                            || ((Number) user.get("tenant_id")).longValue() != p.tenant()
                            || !"member".equals(user.get("role"))) throw new ApiError(404, "成员不存在");
                    String action = Objects.toString(body.get("action"), "update");
                    Map<String, Object> out;
                    if ("reset_password".equals(action)) {
                        String password = accounts.reset(p.tenant(), id);
                        out = accounts.publicUser(accounts.user(p.tenant(), id));
                        out.put("initial_password", password);
                    } else if ("update".equals(action)) {
                        out = accounts.update(p.tenant(), p.clientId(), user, body);
                    } else throw new ApiError(422, "不支持的操作");
                    event(p, null, "member_" + action, JsonSupport.map("member_id", id));
                    return out;
                });
    }
}
