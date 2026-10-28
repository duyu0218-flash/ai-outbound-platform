package com.duozhi.outbound.messaging.service;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.messaging.dao.MessageStore;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Transactional message application service. The initial adapter is explicitly sandbox-only. */
@Service
public class Messaging {
    public record Principal(long tenant, String application) {}

    private final MessageStore db;
    private final Transactions tx;
    private final MessageSecrets secrets;
    private final Config config;
    private final Map<String, MessageAdapter> adapters;
    private final java.util.concurrent.atomic.AtomicLong nextRetention =
            new java.util.concurrent.atomic.AtomicLong();
    private static final Set<String> FORMS = Set.of("sms", "rcs", "yuexin", "oem_ip");

    public Messaging(
            MessageStore db,
            Transactions tx,
            MessageSecrets secrets,
            Config config,
            List<MessageAdapter> adapters) {
        this.db = db;
        this.tx = tx;
        this.secrets = secrets;
        this.config = config;
        var registered = new HashMap<String, MessageAdapter>();
        for (var adapter : adapters)
            if (registered.put(adapter.id(), adapter) != null)
                throw new IllegalStateException("duplicate adapter");
        this.adapters = Map.copyOf(registered);
    }

    public static String id(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    public static Map<String, Object> object(Object value) {
        if (value == null) throw new ApiError(422, "缺少对象字段");
        try {
            return value instanceof Map<?, ?>
                    ? Support.map(value)
                    : Support.object(value.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Support.Failure invalid) {
            throw new ApiError(422, "对象字段无效");
        }
    }

    static java.time.LocalDateTime time(Instant instant) {
        return java.time.LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }

    static Instant instant(Object value) {
        return (value instanceof java.sql.Timestamp t
                        ? t.toLocalDateTime()
                        : (java.time.LocalDateTime) value)
                .toInstant(java.time.ZoneOffset.UTC);
    }

    static Map<String, Object> publicDates(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>(row);
        result.replaceAll(
                (k, v) ->
                        v instanceof java.sql.Timestamp || v instanceof java.time.LocalDateTime
                                ? instant(v).toString()
                                : v);
        return result;
    }

    static String json(Object value) {
        return new String(Support.json(value), StandardCharsets.UTF_8);
    }

    static String required(Map<String, Object> value, String key, int max) {
        try {
            return Support.required(value, key, max);
        } catch (Support.Failure e) {
            throw new ApiError(422, e.getMessage());
        }
    }

    static long number(Map<String, Object> value, String key, long fallback, long max) {
        long result;
        try {
            result = Support.integer(value, key, fallback);
        } catch (Support.Failure e) {
            throw new ApiError(422, e.getMessage());
        }
        if (result < 0 || result > max) throw new ApiError(422, "invalid " + key);
        return result;
    }

    private void enabled() {
        if (!config.flag("MESSAGING_ENABLED", false)) throw new ApiError(503, "消息模块尚未启用");
    }

    private void sandbox() {
        enabled();
        if (config.production()) throw new ApiError(409, "尚未接入正式供应商，生产环境禁止模拟发送和模拟记账");
    }

    private Map<String, Object> lock(long tenant) {
        var t = db.one("SELECT id, enabled FROM tenant WHERE id=? FOR UPDATE", tenant);
        if (t == null || !Boolean.TRUE.equals(t.get("enabled"))) throw new ApiError(403, "企业已停用");
        db.jdbc().update("INSERT IGNORE INTO msg_account(tenant_id) VALUES(?)", tenant);
        return db.one("SELECT * FROM msg_account WHERE tenant_id=? FOR UPDATE", tenant);
    }

    public Map<String, Object> capabilities() {
        return Map.of(
                "enabled",
                config.flag("MESSAGING_ENABLED", false),
                "environment",
                "sandbox",
                "live_supplier_connected",
                false,
                "forms",
                FORMS.stream()
                        .sorted()
                        .map(
                                f ->
                                        Map.of(
                                                "form",
                                                f,
                                                "adapter",
                                                "mock",
                                                "real_delivery_verified",
                                                false))
                        .toList(),
                "recipient_capability_probe",
                false,
                "billing_currency",
                "CNY",
                "amount_unit",
                "micro_CNY");
    }

    public List<Map<String, Object>> resources(long tenant, String kind) {
        if (!Set.of("product", "sender", "template", "audience", "campaign", "call_rule")
                .contains(kind)) throw new ApiError(404, "资源不存在");
        return db
                .jdbc()
                .queryForList(
                        "SELECT * FROM msg_resource WHERE tenant_id=? AND kind=? ORDER BY created_at DESC,id DESC LIMIT 200",
                        tenant,
                        kind)
                .stream()
                .map(this::decodeResource)
                .toList();
    }

    private Map<String, Object> decodeResource(Map<String, Object> row) {
        var result = new LinkedHashMap<>(row);
        result.put("payload", object(row.get("payload")));
        return result;
    }

    private Map<String, Object> resource(long tenant, String id, String kind, boolean approved) {
        var row =
                db.one(
                        "SELECT * FROM msg_resource WHERE tenant_id=? AND id=? AND kind=?",
                        tenant,
                        id,
                        kind);
        if (row == null) throw new ApiError(404, "消息资源不存在");
        if (approved && !"APPROVED".equals(row.get("state")))
            throw new ApiError(409, "消息资源尚未批准或已停用");
        return decodeResource(row);
    }

    public Object createResource(long tenant, String kind, Map<String, Object> input) {
        sandbox();
        String name = required(input, "name", 128);
        var payload = new LinkedHashMap<>(object(input.getOrDefault("payload", Map.of())));
        switch (kind) {
            case "product" -> {
                if (!FORMS.contains(required(payload, "form", 16)))
                    throw new ApiError(422, "消息形态无效");
                if (!"mock".equals(payload.getOrDefault("adapter", "mock")))
                    throw new ApiError(409, "供应商未接入");
                payload.put("adapter", "mock");
                payload.put("simulated", true);
                payload.put("unit_price", number(payload, "unit_price", 1000, 1_000_000_000));
                payload.put(
                        "daily_recipient_limit", number(payload, "daily_recipient_limit", 3, 100));
                if (!Set.of("DELIVERED", "REJECTED", "UNKNOWN")
                        .contains(payload.getOrDefault("simulation_outcome", "DELIVERED")))
                    throw new ApiError(422, "模拟结果无效");
            }
            case "template" -> {
                required(payload, "body", 4000);
                if (!FORMS.contains(required(payload, "form", 16)))
                    throw new ApiError(422, "消息形态无效");
            }
            case "sender" -> required(payload, "brand", 128);
            case "audience" -> {
                if (!(payload.get("contact_ids") instanceof List<?> members)
                        || members.isEmpty()
                        || members.size() > 1000) throw new ApiError(422, "人群应包含 1 至 1000 个联系人");
                var unique = new LinkedHashSet<Long>();
                for (var member : members) {
                    if (!(member instanceof Number n)
                            || n.longValue() <= 0
                            || n.doubleValue() != n.longValue()) throw new ApiError(422, "联系人编号无效");
                    if (db.one(
                                    "SELECT id FROM contact WHERE tenant_id=? AND id=?",
                                    tenant,
                                    n.longValue())
                            == null) throw new ApiError(404, "联系人不存在");
                    unique.add(n.longValue());
                }
                payload.put("contact_ids", unique);
            }
            case "call_rule" -> {
                long campaign = number(payload, "call_campaign_id", 0, Integer.MAX_VALUE);
                if (campaign > 0
                        && db.one(
                                        "SELECT id FROM campaign WHERE tenant_id=? AND id=?",
                                        tenant,
                                        campaign)
                                == null) throw new ApiError(404, "外呼任务不存在");
                payload.put("call_campaign_id", campaign);
                for (var entry :
                        Map.of(
                                        "product_id",
                                        "product",
                                        "template_id",
                                        "template",
                                        "sender_id",
                                        "sender")
                                .entrySet())
                    resource(tenant, required(payload, entry.getKey(), 64), entry.getValue(), true);
                payload.put("variables", object(payload.getOrDefault("variables", Map.of())));
            }
            case "campaign" -> {
                for (var entry :
                        Map.of(
                                        "audience_id",
                                        "audience",
                                        "product_id",
                                        "product",
                                        "template_id",
                                        "template",
                                        "sender_id",
                                        "sender")
                                .entrySet())
                    resource(tenant, required(payload, entry.getKey(), 64), entry.getValue(), true);
                payload.put("budget", number(payload, "budget", 1_000_000, 1_000_000_000_000L));
                required(payload, "expires_at", 64);
                instant(payload, "expires_at", Instant.now());
            }
            default -> throw new ApiError(404, "资源不存在");
        }
        if (Support.json(payload).length > 64 * 1024) throw new ApiError(422, "资源内容过大");
        return tx.run(
                () -> {
                    lock(tenant);
                    String id = id(kind);
                    db.jdbc()
                            .update(
                                    "INSERT INTO msg_resource(id,tenant_id,kind,name,payload) VALUES(?,?,?,?,?)",
                                    id,
                                    tenant,
                                    kind,
                                    name,
                                    json(payload));
                    return resource(tenant, id, kind, false);
                });
    }

    public Object resourceState(long tenant, String id, String state) {
        enabled();
        if (!Set.of("APPROVED", "DISABLED").contains(state)) throw new ApiError(422, "状态无效");
        return tx.run(
                () -> {
                    lock(tenant);
                    var row =
                            db.one(
                                    "SELECT * FROM msg_resource WHERE tenant_id=? AND id=?",
                                    tenant,
                                    id);
                    if (row == null) throw new ApiError(404, "资源不存在");
                    if ("APPROVED".equals(state) && !"DRAFT".equals(row.get("state")))
                        throw new ApiError(409, "只有草稿可批准；变更内容应新建版本");
                    if ("APPROVED".equals(state) && "call_rule".equals(row.get("kind"))) {
                        long campaign =
                                number(
                                        object(row.get("payload")),
                                        "call_campaign_id",
                                        0,
                                        Integer.MAX_VALUE);
                        for (var rule : resources(tenant, "call_rule"))
                            if ("APPROVED".equals(rule.get("state"))
                                    && number(
                                                    object(rule.get("payload")),
                                                    "call_campaign_id",
                                                    0,
                                                    Integer.MAX_VALUE)
                                            == campaign)
                                throw new ApiError(409, "同一外呼任务只能启用一个消息联动规则");
                    }
                    db.jdbc()
                            .update(
                                    "UPDATE msg_resource SET state=? WHERE tenant_id=? AND id=?",
                                    state,
                                    tenant,
                                    id);
                    return Map.of("id", id, "state", state, "provider_approval", "not_submitted");
                });
    }

    public List<Map<String, Object>> applications(long tenant) {
        return db.jdbc()
                .queryForList(
                        "SELECT id,name,enabled,grants,created_at FROM msg_application WHERE tenant_id=? ORDER BY created_at DESC LIMIT 200",
                        tenant);
    }

    public Object createApplication(long tenant, Map<String, Object> input) {
        sandbox();
        String name = required(input, "name", 128);
        Map<String, Object> grants = object(input.getOrDefault("grants", Map.of()));
        for (var entry :
                Map.of("products", "product", "templates", "template", "senders", "sender")
                        .entrySet()) {
            if (!(grants.get(entry.getKey()) instanceof List<?> ids)
                    || ids.isEmpty()
                    || ids.size() > 100) throw new ApiError(422, "应用需要明确授权产品、模板及发送主体");
            for (Object id : ids) resource(tenant, id.toString(), entry.getValue(), true);
        }
        return tx.run(
                () -> {
                    lock(tenant);
                    String id = id("app"), secret = MessageSecrets.random();
                    db.jdbc()
                            .update(
                                    "INSERT INTO msg_application(id,tenant_id,name,secret_cipher,grants) VALUES(?,?,?,?,?)",
                                    id,
                                    tenant,
                                    name,
                                    secrets.encrypt("app:" + tenant + ":" + id, secret),
                                    json(grants));
                    return Map.of(
                            "application_id",
                            id,
                            "secret",
                            secret,
                            "environment",
                            "sandbox",
                            "notice",
                            "密钥只展示一次，请安全保存");
                });
    }

    public Object revokeApplication(long tenant, String id) {
        return tx.run(
                () -> {
                    lock(tenant);
                    if (db.jdbc()
                                    .update(
                                            "UPDATE msg_application SET enabled=FALSE WHERE tenant_id=? AND id=?",
                                            tenant,
                                            id)
                            == 0) throw new ApiError(404, "应用不存在");
                    return Map.of("id", id, "enabled", false);
                });
    }

    private void grants(Principal p, String product, String sender, String template) {
        if (p.application().equals("console")) return;
        var row =
                db.one(
                        "SELECT grants FROM msg_application WHERE tenant_id=? AND id=? AND enabled=TRUE",
                        p.tenant(),
                        p.application());
        if (row == null) throw new ApiError(403, "应用授权已撤销");
        var grants = object(row.get("grants"));
        for (var e :
                Map.of("products", product, "senders", sender, "templates", template).entrySet())
            if (!(grants.get(e.getKey()) instanceof List<?> ids) || !ids.contains(e.getValue()))
                throw new ApiError(403, "资源未授权给应用");
    }

    private String phone(String value) {
        String result =
                value.startsWith("+") ? value : value.matches("1[0-9]{10}") ? "+86" + value : value;
        if (!result.matches("\\+[1-9][0-9]{7,14}")) throw new ApiError(422, "号码必须为 E.164 格式");
        return result;
    }

    private void consent(long tenant, String reference, String recipient) {
        if (!reference.matches("[0-9]{1,12}")) throw new ApiError(422, "授权证据应引用本企业的联系人编号");
        var c =
                db.one(
                        "SELECT phone,consent_state,dnc FROM contact WHERE tenant_id=? AND id=?",
                        tenant,
                        Long.parseLong(reference));
        if (c == null
                || !phone(c.get("phone").toString()).equals(recipient)
                || !"CONSENTED".equals(c.get("consent_state"))
                || Boolean.TRUE.equals(c.get("dnc"))) throw new ApiError(409, "接收人缺少有效触达授权或已拒绝联系");
        if (db.one(
                        "SELECT recipient_hash FROM msg_suppression WHERE tenant_id=? AND recipient_hash=?",
                        tenant,
                        secrets.hash(tenant, recipient))
                != null) throw new ApiError(409, "接收人已退订");
    }

    private String render(Map<String, Object> template, Map<String, Object> variables) {
        String body = required(object(template.get("payload")), "body", 4000);
        var pattern = java.util.regex.Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]{0,63})}}");
        var matcher = pattern.matcher(body);
        var names = new HashSet<String>();
        while (matcher.find()) names.add(matcher.group(1));
        if (!variables.keySet().equals(names)) throw new ApiError(422, "模板变量不匹配");
        for (String key : names)
            body = body.replace("{{" + key + "}}", required(variables, key, 512));
        if (body.length() > 4000 || body.contains("{{")) throw new ApiError(422, "模板内容无效或过长");
        return body;
    }

    private Instant instant(Map<String, Object> body, String key, Instant fallback) {
        try {
            return body.containsKey(key) ? Instant.parse(body.get(key).toString()) : fallback;
        } catch (Exception e) {
            throw new ApiError(422, "时间格式无效: " + key);
        }
    }

    private void journal(
            long tenant,
            String key,
            String message,
            String kind,
            long available,
            long frozen,
            long spent,
            long clearing,
            String reason) {
        if (Math.addExact(Math.addExact(available, frozen), Math.addExact(spent, clearing)) != 0)
            throw new IllegalStateException("unbalanced journal");
        db.jdbc()
                .update(
                        "INSERT INTO msg_journal(id,tenant_id,operation_key,message_id,kind,available_delta,frozen_delta,spent_delta,clearing_delta,reason) VALUES(?,?,?,?,?,?,?,?,?,?)",
                        id("jnl"),
                        tenant,
                        key,
                        message,
                        kind,
                        available,
                        frozen,
                        spent,
                        clearing,
                        reason);
        if (db.jdbc()
                        .update(
                                "UPDATE msg_account SET available=available+?,frozen=frozen+?,spent=spent+? WHERE tenant_id=? AND available+?>=0 AND frozen+?>=0 AND spent+?>=0",
                                available,
                                frozen,
                                spent,
                                tenant,
                                available,
                                frozen,
                                spent)
                != 1) throw new ApiError(409, "可用额度不足或账户状态冲突");
    }

    public Object credit(long tenant, String key, long amount) {
        sandbox();
        if (amount <= 0 || amount > 1_000_000_000_000L) throw new ApiError(422, "模拟额度无效");
        return tx.run(
                () -> {
                    lock(tenant);
                    var old =
                            db.one(
                                    "SELECT available_delta FROM msg_journal WHERE tenant_id=? AND operation_key=?",
                                    tenant,
                                    "credit:" + key);
                    if (old == null)
                        journal(
                                tenant,
                                "credit:" + key,
                                null,
                                "SANDBOX_CREDIT",
                                amount,
                                0,
                                0,
                                -amount,
                                "模拟额度，无真实资金");
                    else if (((Number) old.get("available_delta")).longValue() != amount)
                        throw new ApiError(409, "幂等键对应不同金额");
                    return balance(tenant);
                });
    }

    public Object balance(long tenant) {
        var row =
                db.one("SELECT available,frozen,spent FROM msg_account WHERE tenant_id=?", tenant);
        return Map.of(
                "environment",
                "sandbox",
                "currency",
                "CNY",
                "amount_unit",
                "micro_CNY",
                "account",
                row == null ? Map.of("available", 0, "frozen", 0, "spent", 0) : row);
    }

    public List<Map<String, Object>> ledger(long tenant) {
        return db.jdbc()
                .queryForList(
                        "SELECT * FROM msg_journal WHERE tenant_id=? ORDER BY created_at DESC,id DESC LIMIT 200",
                        tenant);
    }

    public Map<String, Object> create(Principal p, String key, Map<String, Object> body) {
        sandbox();
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{8,128}"))
            throw new ApiError(422, "需要 8 至 128 字符幂等键");
        if (Support.json(body).length > 32768) throw new ApiError(413, "消息请求过大");
        Set<String> allowed =
                Set.of(
                        "client_message_id",
                        "service_product_id",
                        "sender_identity_id",
                        "recipient",
                        "template_version_id",
                        "variables",
                        "channel_policy",
                        "purpose",
                        "consent_reference",
                        "scheduled_at",
                        "expires_at",
                        "marketing_campaign_id",
                        "max_sales_amount");
        if (!allowed.containsAll(body.keySet())) throw new ApiError(422, "存在未支持的请求字段");
        String digest = Support.hex(Support.sha(Support.canonical(body)));
        return tx.run(
                () -> {
                    lock(p.tenant());
                    var old =
                            db.one(
                                    "SELECT id,request_hash FROM msg_message WHERE tenant_id=? AND application_id=? AND idempotency_key=?",
                                    p.tenant(),
                                    p.application(),
                                    key);
                    if (old != null) {
                        if (!digest.equals(old.get("request_hash")))
                            throw new ApiError(409, "幂等键对应不同请求");
                        return message(p, old.get("id").toString());
                    }
                    String product = required(body, "service_product_id", 64),
                            sender = required(body, "sender_identity_id", 64),
                            template = required(body, "template_version_id", 64);
                    grants(p, product, sender, template);
                    var pr = resource(p.tenant(), product, "product", true);
                    var productConfig = object(pr.get("payload"));
                    resource(p.tenant(), sender, "sender", true);
                    var t = resource(p.tenant(), template, "template", true);
                    String form = required(productConfig, "form", 16);
                    if (!form.equals(object(t.get("payload")).get("form")))
                        throw new ApiError(422, "产品与模板形态不匹配");
                    var policy = object(body.getOrDefault("channel_policy", Map.of()));
                    if (!Set.of("fixed", "automatic").contains(policy.get("mode"))
                            || !(policy.get("allowed_forms") instanceof List<?> forms)
                            || !forms.contains(form)) throw new ApiError(422, "消息形态不在允许范围");
                    if (forms.isEmpty()
                            || forms.size() != new HashSet<>(forms).size()
                            || !Set.of("sms", "rcs", "yuexin", "oem_ip").containsAll(forms)
                            || ("fixed".equals(policy.get("mode")) && forms.size() != 1))
                        throw new ApiError(422, "消息形态策略无效");
                    if (Boolean.TRUE.equals(policy.get("allow_fallback")))
                        throw new ApiError(409, "供应商未确定，暂未开放跨通道降级");
                    String purpose = required(body, "purpose", 32);
                    if (!Set.of("marketing", "service_notification").contains(purpose))
                        throw new ApiError(422, "用途无效");
                    var recipient = object(body.getOrDefault("recipient", Map.of()));
                    if (!"phone".equals(recipient.get("type")))
                        throw new ApiError(422, "目前仅接受已授权手机号");
                    String destination = phone(required(recipient, "value", 32));
                    consent(p.tenant(), required(body, "consent_reference", 64), destination);
                    render(t, object(body.getOrDefault("variables", Map.of())));
                    Instant now = Instant.now(),
                            scheduled = instant(body, "scheduled_at", now),
                            expires = instant(body, "expires_at", now.plusSeconds(86400));
                    if (expires.isBefore(now)
                            || !expires.isAfter(scheduled)
                            || expires.isAfter(now.plusSeconds(30L * 86400)))
                        throw new ApiError(422, "消息有效期应在未来 30 天内且晚于发送时间");
                    String hash = secrets.hash(p.tenant(), destination);
                    Long count =
                            db.jdbc()
                                    .queryForObject(
                                            "SELECT COUNT(*) FROM msg_message WHERE tenant_id=? AND recipient_hash=? AND created_at>=? AND state NOT IN ('CANCELLED','BLOCKED','EXPIRED','REJECTED')",
                                            Long.class,
                                            p.tenant(),
                                            hash,
                                            time(now.minusSeconds(86400)));
                    if (count >= number(productConfig, "daily_recipient_limit", 3, 100))
                        throw new ApiError(429, "接收人日触达频次已达上限");
                    long price = number(productConfig, "unit_price", 1000, 1_000_000_000);
                    if (body.containsKey("max_sales_amount")) {
                        try {
                            if (new java.math.BigDecimal(required(body, "max_sales_amount", 32))
                                            .movePointRight(6)
                                            .longValueExact()
                                    < price) throw new ApiError(409, "超过客户费用上限");
                        } catch (ArithmeticException | NumberFormatException e) {
                            throw new ApiError(422, "金额格式无效");
                        }
                    }
                    String campaign = Objects.toString(body.get("marketing_campaign_id"), null);
                    if (campaign != null) {
                        var cp =
                                object(
                                        resource(p.tenant(), campaign, "campaign", true)
                                                .get("payload"));
                        if (!product.equals(cp.get("product_id"))
                                || !template.equals(cp.get("template_id"))
                                || !sender.equals(cp.get("sender_id")))
                            throw new ApiError(422, "活动资源不匹配");
                        Long used =
                                db.jdbc()
                                        .queryForObject(
                                                "SELECT COALESCE(SUM(sales_amount),0) FROM msg_message WHERE tenant_id=? AND campaign_id=? AND billing_state IN ('HELD','SETTLED')",
                                                Long.class,
                                                p.tenant(),
                                                campaign);
                        if (used + price > number(cp, "budget", 0, 1_000_000_000_000L))
                            throw new ApiError(409, "活动预算不足");
                    }
                    String id = id("msg");
                    var encrypted =
                            Map.of(
                                    "cipher",
                                    secrets.encrypt(
                                            "message:" + p.tenant() + ":" + id, json(body)));
                    db.jdbc()
                            .update(
                                    "INSERT INTO msg_message(id,tenant_id,application_id,idempotency_key,request_hash,product_id,template_id,sender_id,campaign_id,recipient_cipher,recipient_hash,recipient_mask,payload,state,requested_form,sales_amount,price_version,scheduled_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                                    id,
                                    p.tenant(),
                                    p.application(),
                                    key,
                                    digest,
                                    product,
                                    template,
                                    sender,
                                    campaign,
                                    secrets.encrypt(
                                            "recipient:" + p.tenant() + ":" + id, destination),
                                    hash,
                                    destination.substring(0, Math.min(4, destination.length()))
                                            + "****"
                                            + destination.substring(destination.length() - 4),
                                    json(encrypted),
                                    "QUEUED",
                                    form,
                                    price,
                                    product,
                                    time(scheduled),
                                    time(expires));
                    db.jdbc()
                            .update(
                                    "UPDATE msg_message SET contact_id=? WHERE tenant_id=? AND id=?",
                                    Long.parseLong(body.get("consent_reference").toString()),
                                    p.tenant(),
                                    id);
                    journal(p.tenant(), "hold:" + id, id, "HOLD", -price, price, 0, 0, "受理预占");
                    event(p, id, "message.queued", Map.of("status", "QUEUED"));
                    return message(p, id);
                });
    }

    private void event(Principal p, String message, String type, Map<String, Object> data) {
        var payload = new LinkedHashMap<>(data);
        payload.put("message_id", message);
        payload.put("simulated", true);
        db.jdbc()
                .update(
                        "INSERT INTO msg_event(id,tenant_id,application_id,message_id,type,payload) VALUES(?,?,?,?,?,?)",
                        id("evt"),
                        p.tenant(),
                        p.application(),
                        message,
                        type,
                        json(payload));
    }

    private Map<String, Object> publicMessage(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        for (String key :
                List.of(
                        "id",
                        "state",
                        "recipient_mask",
                        "requested_form",
                        "actual_transport",
                        "billing_state",
                        "sales_amount",
                        "created_at",
                        "scheduled_at",
                        "expires_at",
                        "finished_at",
                        "campaign_id")) result.put(key, row.get(key));
        result.put("message_id", row.get("id"));
        result.put("status", row.get("state"));
        result.put("simulated", true);
        result.put("amount_unit", "micro_CNY");
        return publicDates(result);
    }

    public Map<String, Object> message(Principal p, String id) {
        var row =
                db.one(
                        "SELECT * FROM msg_message WHERE tenant_id=? AND id=?"
                                + (p.application().equals("console")
                                        ? ""
                                        : " AND application_id=?"),
                        p.application().equals("console")
                                ? new Object[] {p.tenant(), id}
                                : new Object[] {p.tenant(), id, p.application()});
        if (row == null) throw new ApiError(404, "消息不存在");
        return publicMessage(row);
    }

    public List<Map<String, Object>> messages(long tenant) {
        return db
                .jdbc()
                .queryForList(
                        "SELECT * FROM msg_message WHERE tenant_id=? ORDER BY created_at DESC,id DESC LIMIT 200",
                        tenant)
                .stream()
                .map(this::publicMessage)
                .toList();
    }

    public Object events(Principal p, String message, long after) {
        if (message != null) message(p, message);
        String sql =
                "SELECT sequence,id,message_id,type,payload,created_at FROM msg_event WHERE tenant_id=? AND sequence>?";
        var args = new ArrayList<Object>(List.of(p.tenant(), after));
        if (!p.application().equals("console")) {
            sql += " AND application_id=?";
            args.add(p.application());
        }
        if (message != null) {
            sql += " AND message_id=?";
            args.add(message);
        }
        return db.jdbc().queryForList(sql + " ORDER BY sequence LIMIT 200", args.toArray()).stream()
                .map(
                        row -> {
                            row.put("payload", object(row.get("payload")));
                            return publicDates(row);
                        })
                .toList();
    }

    public Object cancel(Principal p, String id) {
        return tx.run(
                () -> {
                    lock(p.tenant());
                    message(p, id);
                    var row =
                            db.one(
                                    "SELECT * FROM msg_message WHERE tenant_id=? AND id=? FOR UPDATE",
                                    p.tenant(),
                                    id);
                    if ("CANCELLED".equals(row.get("state"))) return message(p, id);
                    if (!"QUEUED".equals(row.get("state"))) throw new ApiError(409, "消息已开始提交，无法撤回");
                    complete(row, "CANCELLED");
                    return message(p, id);
                });
    }

    private void complete(Map<String, Object> row, String state) {
        long tenant = ((Number) row.get("tenant_id")).longValue(),
                amount = ((Number) row.get("sales_amount")).longValue();
        String id = row.get("id").toString();
        if (!"HELD".equals(row.get("billing_state"))) return;
        boolean delivered = state.equals("DELIVERED");
        if (!state.equals("UNKNOWN"))
            journal(
                    tenant,
                    "finish:" + id,
                    id,
                    delivered ? "SETTLE" : "RELEASE",
                    delivered ? 0 : amount,
                    -amount,
                    delivered ? amount : 0,
                    0,
                    delivered ? "模拟交付结算" : "明确未计费释放");
        db.jdbc()
                .update(
                        "UPDATE msg_message SET state=?,billing_state=?,finished_at=? WHERE tenant_id=? AND id=?",
                        state,
                        state.equals("UNKNOWN") ? "HELD" : delivered ? "SETTLED" : "RELEASED",
                        state.equals("UNKNOWN") ? null : time(Instant.now()),
                        tenant,
                        id);
        event(
                new Principal(tenant, row.get("application_id").toString()),
                id,
                "message." + state.toLowerCase(Locale.ROOT),
                Map.of(
                        "status",
                        state,
                        "billing_state",
                        state.equals("UNKNOWN") ? "HELD" : delivered ? "SETTLED" : "RELEASED"));
    }

    private boolean workerLock(long tenant) {
        var row = db.one("SELECT enabled FROM tenant WHERE id=? FOR UPDATE", tenant);
        db.one("SELECT tenant_id FROM msg_account WHERE tenant_id=? FOR UPDATE", tenant);
        return row != null && Boolean.TRUE.equals(row.get("enabled"));
    }

    public boolean processOne() {
        if (!config.flag("MESSAGING_ENABLED", false) || config.production()) return false;
        var candidate =
                db.one(
                        "SELECT id,tenant_id FROM msg_message WHERE state='QUEUED' AND scheduled_at<=CURRENT_TIMESTAMP(6) ORDER BY scheduled_at,id LIMIT 1");
        if (candidate == null) return false;
        long tenant = ((Number) candidate.get("tenant_id")).longValue();
        String id = candidate.get("id").toString();
        Map<String, Object> claimed =
                tx.run(
                        () -> {
                            boolean tenantActive = workerLock(tenant);
                            var row =
                                    db.one(
                                            "SELECT * FROM msg_message WHERE tenant_id=? AND id=? FOR UPDATE",
                                            tenant,
                                            id);
                            if (!"QUEUED".equals(row.get("state"))) return null;
                            if (!tenantActive) {
                                complete(row, "BLOCKED");
                                return null;
                            }
                            if (instant(row.get("expires_at")).isBefore(Instant.now())) {
                                complete(row, "EXPIRED");
                                return null;
                            }
                            var p = new Principal(tenant, row.get("application_id").toString());
                            try {
                                grants(
                                        p,
                                        row.get("product_id").toString(),
                                        row.get("sender_id").toString(),
                                        row.get("template_id").toString());
                                resource(tenant, row.get("product_id").toString(), "product", true);
                                resource(tenant, row.get("sender_id").toString(), "sender", true);
                                resource(
                                        tenant,
                                        row.get("template_id").toString(),
                                        "template",
                                        true);
                                if (row.get("campaign_id") != null)
                                    resource(
                                            tenant,
                                            row.get("campaign_id").toString(),
                                            "campaign",
                                            true);
                                var body = body(row);
                                consent(
                                        tenant,
                                        body.get("consent_reference").toString(),
                                        secrets.decrypt(
                                                "recipient:" + tenant + ":" + id,
                                                row.get("recipient_cipher").toString()));
                            } catch (ApiError rejected) {
                                if (rejected.status >= 500) throw rejected;
                                complete(row, "BLOCKED");
                                return null;
                            }
                            db.jdbc()
                                    .update(
                                            "UPDATE msg_message SET state='SUBMITTING',submitted_at=CURRENT_TIMESTAMP(6),actual_transport='mock' WHERE tenant_id=? AND id=?",
                                            tenant,
                                            id);
                            db.jdbc()
                                    .update(
                                            "INSERT INTO msg_attempt(id,tenant_id,message_id,attempt_no,state,adapter) VALUES(?,?,?,1,'SUBMITTING','mock')",
                                            id("att"),
                                            tenant,
                                            id);
                            event(p, id, "message.submitting", Map.of("status", "SUBMITTING"));
                            return row;
                        });
        if (claimed == null) return true;
        MessageAdapter.Result result;
        try {
            var product =
                    object(
                            resource(tenant, claimed.get("product_id").toString(), "product", false)
                                    .get("payload"));
            var template =
                    resource(tenant, claimed.get("template_id").toString(), "template", false);
            String recipient =
                    secrets.decrypt(
                            "recipient:" + tenant + ":" + id,
                            claimed.get("recipient_cipher").toString());
            result =
                    adapters.get("mock")
                            .submit(
                                    new MessageAdapter.Submission(
                                            id,
                                            claimed.get("requested_form").toString(),
                                            recipient,
                                            render(
                                                    template,
                                                    object(
                                                            body(claimed)
                                                                    .getOrDefault(
                                                                            "variables",
                                                                            Map.of()))),
                                            product));
        } catch (Exception error) {
            result = new MessageAdapter.Result("UNKNOWN", "", 0, true);
        }
        var outcome = result;
        tx.run(
                () -> {
                    workerLock(tenant);
                    var row =
                            db.one(
                                    "SELECT * FROM msg_message WHERE tenant_id=? AND id=? FOR UPDATE",
                                    tenant,
                                    id);
                    if (!"SUBMITTING".equals(row.get("state"))) return null;
                    db.jdbc()
                            .update(
                                    "UPDATE msg_attempt SET state=?,provider_id=? WHERE tenant_id=? AND message_id=? AND attempt_no=1",
                                    outcome.state(),
                                    outcome.providerId(),
                                    tenant,
                                    id);
                    db.jdbc()
                            .update(
                                    "UPDATE msg_message SET purchase_amount=? WHERE tenant_id=? AND id=?",
                                    outcome.purchaseAmount(),
                                    tenant,
                                    id);
                    complete(row, outcome.state());
                    return null;
                });
        return true;
    }

    private Map<String, Object> body(Map<String, Object> row) {
        return object(
                secrets.decrypt(
                        "message:" + row.get("tenant_id") + ":" + row.get("id"),
                        object(row.get("payload")).get("cipher").toString()));
    }

    public void recoverUnknown() {
        for (var item :
                db.jdbc()
                        .queryForList(
                                "SELECT tenant_id,id FROM msg_message WHERE state='SUBMITTING' AND submitted_at<CURRENT_TIMESTAMP(6)-INTERVAL 2 MINUTE LIMIT 100"))
            tx.run(
                    () -> {
                        long tenant = ((Number) item.get("tenant_id")).longValue();
                        workerLock(tenant);
                        var row =
                                db.one(
                                        "SELECT * FROM msg_message WHERE tenant_id=? AND id=? FOR UPDATE",
                                        tenant,
                                        item.get("id"));
                        if ("SUBMITTING".equals(row.get("state"))) {
                            complete(row, "UNKNOWN");
                            db.jdbc()
                                    .update(
                                            "UPDATE msg_attempt SET state='UNKNOWN' WHERE tenant_id=? AND message_id=?",
                                            tenant,
                                            item.get("id"));
                        }
                        return null;
                    });
        db.jdbc().update("DELETE FROM msg_nonce WHERE expires_at<CURRENT_TIMESTAMP(6) LIMIT 1000");
        long now = System.currentTimeMillis(), next = nextRetention.get();
        if (config.flag("MESSAGING_RETENTION_ENABLED", true)
                && now >= next
                && nextRetention.compareAndSet(next, now + 3600000)) {
            try {
                retain();
            } catch (RuntimeException failure) {
                nextRetention.set(now + 60000);
                throw failure;
            }
        }
    }

    public Object receipt(long tenant, String id, String eventKey, String state) {
        sandbox();
        if (!Set.of("DELIVERED", "REJECTED", "UNKNOWN").contains(state))
            throw new ApiError(422, "回执状态无效");
        return tx.run(
                () -> {
                    lock(tenant);
                    var row =
                            db.one(
                                    "SELECT * FROM msg_message WHERE tenant_id=? AND id=? FOR UPDATE",
                                    tenant,
                                    id);
                    if (row == null) throw new ApiError(404, "消息不存在");
                    String digest = state + ":" + id;
                    var prior =
                            db.one(
                                    "SELECT payload FROM msg_inbox WHERE tenant_id=? AND adapter='mock' AND event_key=?",
                                    tenant,
                                    eventKey);
                    if (prior != null) {
                        if (!digest.equals(object(prior.get("payload")).get("identity")))
                            throw new ApiError(409, "回执标识对应不同内容");
                        return publicMessage(row);
                    }
                    db.jdbc()
                            .update(
                                    "INSERT INTO msg_inbox(tenant_id,adapter,event_key,message_id,payload) VALUES(?,'mock',?,?,?)",
                                    tenant,
                                    eventKey,
                                    id,
                                    json(Map.of("identity", digest)));
                    if (Set.of("UNKNOWN", "SUBMITTING").contains(row.get("state")))
                        complete(row, state);
                    return message(new Principal(tenant, "console"), id);
                });
    }

    public Object suppress(long tenant, String contactId, String reason) {
        return tx.run(
                () -> {
                    lock(tenant);
                    var c =
                            db.one(
                                    "SELECT phone FROM contact WHERE tenant_id=? AND id=?",
                                    tenant,
                                    contactId);
                    if (c == null) throw new ApiError(404, "联系人不存在");
                    String hash = secrets.hash(tenant, phone(c.get("phone").toString()));
                    db.jdbc()
                            .update(
                                    "INSERT INTO msg_suppression(tenant_id,recipient_hash,reason) VALUES(?,?,?) ON DUPLICATE KEY UPDATE reason=VALUES(reason)",
                                    tenant,
                                    hash,
                                    reason);
                    return Map.of("suppressed", true, "contact_id", contactId);
                });
    }

    public Object refund(long tenant, String id, String reason) {
        sandbox();
        return tx.run(
                () -> {
                    lock(tenant);
                    var row =
                            db.one(
                                    "SELECT * FROM msg_message WHERE tenant_id=? AND id=? FOR UPDATE",
                                    tenant,
                                    id);
                    if (row == null) throw new ApiError(404, "消息不存在");
                    if ("REFUNDED".equals(row.get("billing_state"))) return publicMessage(row);
                    if (!"SETTLED".equals(row.get("billing_state")))
                        throw new ApiError(409, "只有已结算消息可退款");
                    long amount = ((Number) row.get("sales_amount")).longValue();
                    journal(tenant, "refund:" + id, id, "REFUND", amount, 0, -amount, 0, reason);
                    db.jdbc()
                            .update(
                                    "UPDATE msg_message SET billing_state='REFUNDED' WHERE tenant_id=? AND id=?",
                                    tenant,
                                    id);
                    event(
                            new Principal(tenant, row.get("application_id").toString()),
                            id,
                            "billing.refunded",
                            Map.of("billing_state", "REFUNDED"));
                    return message(new Principal(tenant, "console"), id);
                });
    }

    public Object batch(Principal p, String key, Map<String, Object> input) {
        sandbox();
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{8,128}"))
            throw new ApiError(422, "批次需要幂等键");
        if (!(input.get("items") instanceof List<?> items) || items.isEmpty() || items.size() > 100)
            throw new ApiError(422, "批次应包含 1 至 100 条消息");
        if (Support.json(input).length > 512 * 1024) throw new ApiError(413, "批次过大");
        String hash = Support.hex(Support.sha(Support.canonical(input)));
        var clientIds = new HashSet<String>();
        for (var item : items) {
            var entry = object(item);
            object(entry.get("message"));
            if (!clientIds.add(required(entry, "client_item_id", 64)))
                throw new ApiError(422, "批次子项标识重复");
        }
        return tx.run(
                () -> {
                    lock(p.tenant());
                    var old =
                            db.one(
                                    "SELECT id,request_hash FROM msg_batch WHERE tenant_id=? AND application_id=? AND idempotency_key=?",
                                    p.tenant(),
                                    p.application(),
                                    key);
                    if (old != null) {
                        if (!hash.equals(old.get("request_hash")))
                            throw new ApiError(409, "批次幂等键对应不同请求");
                        return batchStatus(p, old.get("id").toString());
                    }
                    String id = id("batch");
                    db.jdbc()
                            .update(
                                    "INSERT INTO msg_batch(id,tenant_id,application_id,idempotency_key,request_hash,payload,results) VALUES(?,?,?,?,?,?,?)",
                                    id,
                                    p.tenant(),
                                    p.application(),
                                    key,
                                    hash,
                                    json(
                                            Map.of(
                                                    "cipher",
                                                    secrets.encrypt(
                                                            "batch:" + p.tenant() + ":" + id,
                                                            json(input)))),
                                    "[]");
                    return batchStatus(p, id);
                });
    }

    public Map<String, Object> batchStatus(Principal p, String id) {
        var row =
                db.one(
                        "SELECT id,application_id,state,results,created_at FROM msg_batch WHERE tenant_id=? AND id=?",
                        p.tenant(),
                        id);
        if (row == null
                || (!p.application().equals("console")
                        && !p.application().equals(row.get("application_id"))))
            throw new ApiError(404, "批次不存在");
        var result = new LinkedHashMap<>(row);
        result.remove("application_id");
        try {
            result.put(
                    "results", Support.JSON.readValue(row.get("results").toString(), List.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        result.put("batch_id", id);
        result.put("simulated", true);
        return publicDates(result);
    }

    public boolean processBatch() {
        if (!config.flag("MESSAGING_ENABLED", false) || config.production()) return false;
        var row =
                db.one(
                        "SELECT * FROM msg_batch WHERE state IN ('QUEUED','PROCESSING') ORDER BY created_at,id LIMIT 1");
        if (row == null) return false;
        long tenant = ((Number) row.get("tenant_id")).longValue();
        String id = row.get("id").toString();
        var p = new Principal(tenant, row.get("application_id").toString());
        db.jdbc()
                .update(
                        "UPDATE msg_batch SET state='PROCESSING' WHERE tenant_id=? AND id=?",
                        tenant,
                        id);
        var input =
                object(
                        secrets.decrypt(
                                "batch:" + tenant + ":" + id,
                                object(row.get("payload")).get("cipher").toString()));
        List<Map<String, Object>> results;
        try {
            results =
                    new ArrayList<>((List<Map<String, Object>>) batchStatus(p, id).get("results"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        var items = (List<?>) input.get("items");
        for (int i = results.size(); i < items.size(); i++) {
            var item = object(items.get(i));
            var result = new LinkedHashMap<String, Object>();
            result.put("client_item_id", item.get("client_item_id"));
            try {
                var message = create(p, "batch:" + id + ":" + i, object(item.get("message")));
                result.put("message_id", message.get("message_id"));
                result.put("status", "ACCEPTED");
            } catch (ApiError error) {
                if (error.status >= 500) throw error;
                result.put("status", "REJECTED");
                result.put("error", Map.of("status", error.status, "message", error.getMessage()));
            }
            results.add(result);
            db.jdbc()
                    .update(
                            "UPDATE msg_batch SET results=? WHERE tenant_id=? AND id=?",
                            json(results),
                            tenant,
                            id);
        }
        db.jdbc()
                .update(
                        "UPDATE msg_batch SET state='COMPLETED' WHERE tenant_id=? AND id=?",
                        tenant,
                        id);
        return true;
    }

    public Object launchCampaign(long tenant, String id, String key) {
        var cp = object(resource(tenant, id, "campaign", true).get("payload"));
        var audience =
                object(
                        resource(tenant, cp.get("audience_id").toString(), "audience", true)
                                .get("payload"));
        var members = (List<?>) audience.get("contact_ids");
        if (members.size() > 100) throw new ApiError(422, "本阶段单活动最多 100 人；请拆分人群");
        var items = new ArrayList<Object>();
        // Freeze the expiry in the campaign version so retrying launch uses an identical request.
        String expiry = required(cp, "expires_at", 64);
        String form =
                required(
                        object(
                                resource(tenant, cp.get("product_id").toString(), "product", true)
                                        .get("payload")),
                        "form",
                        16);
        for (Object member : members) {
            var c =
                    db.one(
                            "SELECT id,phone FROM contact WHERE tenant_id=? AND id=?",
                            tenant,
                            member);
            if (c == null) continue;
            var body = new LinkedHashMap<String, Object>();
            body.put("service_product_id", cp.get("product_id"));
            body.put("template_version_id", cp.get("template_id"));
            body.put("sender_identity_id", cp.get("sender_id"));
            body.put(
                    "recipient",
                    Map.of("type", "phone", "value", phone(c.get("phone").toString())));
            body.put("consent_reference", member.toString());
            body.put("variables", cp.getOrDefault("variables", Map.of()));
            body.put("purpose", "marketing");
            body.put("expires_at", expiry);
            if (cp.containsKey("scheduled_at")) body.put("scheduled_at", cp.get("scheduled_at"));
            body.put("marketing_campaign_id", id);
            body.put(
                    "channel_policy",
                    Map.of(
                            "mode",
                            "fixed",
                            "allowed_forms",
                            List.of(form),
                            "allow_fallback",
                            false));
            items.add(Map.of("client_item_id", member.toString(), "message", body));
        }
        return batch(new Principal(tenant, "console"), key, Map.of("items", items));
    }

    public void deferCall(long tenant, String call, int attempt) {
        sandbox();
        tx.run(
                () -> {
                    lock(tenant);
                    var c =
                            db.one(
                                    "SELECT id,campaign_id,attempts FROM callsession WHERE tenant_id=? AND id=?",
                                    tenant,
                                    call);
                    if (c == null || ((Number) c.get("attempts")).intValue() != attempt)
                        return null;
                    String rule = null;
                    long campaign =
                            c.get("campaign_id") == null
                                    ? 0
                                    : ((Number) c.get("campaign_id")).longValue();
                    for (var candidate : resources(tenant, "call_rule"))
                        if ("APPROVED".equals(candidate.get("state"))
                                && number(
                                                object(candidate.get("payload")),
                                                "call_campaign_id",
                                                0,
                                                Integer.MAX_VALUE)
                                        == campaign) {
                            rule = candidate.get("id").toString();
                            break;
                        }
                    db.jdbc()
                            .update(
                                    "INSERT IGNORE INTO msg_call_intent(id,tenant_id,call_id,attempt,rule_id,state,reason) VALUES(?,?,?,?,?,?,?)",
                                    id("intent"),
                                    tenant,
                                    call,
                                    attempt,
                                    rule,
                                    rule == null ? "BLOCKED" : "WAITING_CALL",
                                    rule == null ? "缺少已批准联动规则" : "");
                    return null;
                });
    }

    public boolean processCallIntent() {
        if (!config.flag("MESSAGING_ENABLED", false) || config.production()) return false;
        var intent =
                db.one(
                        "SELECT i.* FROM msg_call_intent i JOIN callsession c ON c.id=i.call_id AND c.tenant_id=i.tenant_id WHERE i.state='WAITING_CALL' AND (c.status IN ('COMPLETED','FAILED','NO_ANSWER','BUSY','VOICEMAIL') OR c.attempts<>i.attempt OR i.created_at<CURRENT_TIMESTAMP(6)-INTERVAL 1 DAY) ORDER BY i.created_at LIMIT 1");
        if (intent == null) return false;
        long tenant = ((Number) intent.get("tenant_id")).longValue();
        String id = intent.get("id").toString();
        try {
            tx.run(
                    () -> {
                        lock(tenant);
                        var current =
                                db.one(
                                        "SELECT state FROM msg_call_intent WHERE tenant_id=? AND id=? FOR UPDATE",
                                        tenant,
                                        id);
                        if (current == null || !"WAITING_CALL".equals(current.get("state")))
                            return null;
                        var call =
                                db.one(
                                        "SELECT * FROM callsession WHERE tenant_id=? AND id=? FOR UPDATE",
                                        tenant,
                                        intent.get("call_id"));
                        if (call == null) throw new ApiError(409, "通话不存在");
                        if (!"COMPLETED".equals(call.get("status"))
                                || ((Number) call.get("attempts")).intValue()
                                        != ((Number) intent.get("attempt")).intValue())
                            throw new ApiError(409, "通话未正常完成或重拨代次已改变");
                        var rule =
                                object(
                                        resource(
                                                        tenant,
                                                        intent.get("rule_id").toString(),
                                                        "call_rule",
                                                        true)
                                                .get("payload"));
                        if (call.get("contact_id") == null) throw new ApiError(409, "缺少授权联系人");
                        String form =
                                object(
                                                resource(
                                                                tenant,
                                                                rule.get("product_id").toString(),
                                                                "product",
                                                                true)
                                                        .get("payload"))
                                        .get("form")
                                        .toString();
                        var body = new LinkedHashMap<String, Object>();
                        body.put("service_product_id", rule.get("product_id"));
                        body.put("sender_identity_id", rule.get("sender_id"));
                        body.put("template_version_id", rule.get("template_id"));
                        body.put(
                                "recipient",
                                Map.of(
                                        "type",
                                        "phone",
                                        "value",
                                        phone(call.get("phone").toString())));
                        body.put("consent_reference", call.get("contact_id").toString());
                        body.put("variables", rule.get("variables"));
                        body.put("purpose", "marketing");
                        body.put(
                                "expires_at",
                                instant(intent.get("created_at")).plusSeconds(86400).toString());
                        body.put(
                                "channel_policy",
                                Map.of(
                                        "mode",
                                        "fixed",
                                        "allowed_forms",
                                        List.of(form),
                                        "allow_fallback",
                                        false));
                        var message = create(new Principal(tenant, "console"), "call:" + id, body);
                        db.jdbc()
                                .update(
                                        "UPDATE msg_message SET call_id=? WHERE tenant_id=? AND id=?",
                                        intent.get("call_id"),
                                        tenant,
                                        message.get("message_id"));
                        db.jdbc()
                                .update(
                                        "UPDATE msg_call_intent SET state='CREATED',message_id=? WHERE tenant_id=? AND id=?",
                                        message.get("message_id"),
                                        tenant,
                                        id);
                        return null;
                    });
        } catch (ApiError blocked) {
            if (blocked.status >= 500) throw blocked;
            db.jdbc()
                    .update(
                            "UPDATE msg_call_intent SET state='BLOCKED',reason=? WHERE tenant_id=? AND id=? AND state='WAITING_CALL'",
                            blocked.getMessage(),
                            tenant,
                            id);
        }
        return true;
    }

    public Object callIntents(long tenant) {
        return db.jdbc()
                .queryForList(
                        "SELECT id,call_id,attempt,rule_id,message_id,state,reason,created_at FROM msg_call_intent WHERE tenant_id=? ORDER BY created_at DESC LIMIT 200",
                        tenant);
    }

    public void retain() {
        // Financial facts and idempotency tombstones remain; unknown charges never expire by time
        // alone.
        db.jdbc()
                .update(
                        "UPDATE msg_message SET recipient_cipher='',recipient_mask='已脱敏',payload=JSON_OBJECT('redacted',TRUE) WHERE finished_at<CURRENT_TIMESTAMP(6)-INTERVAL 90 DAY AND billing_state IN ('SETTLED','RELEASED','REFUNDED') AND recipient_cipher<>'' LIMIT 500");
        db.jdbc()
                .update(
                        "UPDATE msg_batch SET payload=JSON_OBJECT('redacted',TRUE) WHERE state='COMPLETED' AND created_at<CURRENT_TIMESTAMP(6)-INTERVAL 90 DAY AND JSON_CONTAINS_PATH(payload,'one','$.cipher')=1 LIMIT 100");
        db.jdbc()
                .update(
                        "UPDATE msg_interaction SET payload_cipher='' WHERE created_at<CURRENT_TIMESTAMP(6)-INTERVAL 90 DAY AND payload_cipher<>'' LIMIT 500");
        db.jdbc()
                .update(
                        "DELETE FROM msg_webhook_delivery WHERE state IN ('DELIVERED','DEAD') AND created_at<CURRENT_TIMESTAMP(6)-INTERVAL 30 DAY LIMIT 500");
        // Retain an event while a subscription cursor has not passed it; no silent webhook loss.
        db.jdbc()
                .update(
                        "DELETE FROM msg_event WHERE created_at<CURRENT_TIMESTAMP(6)-INTERVAL 30 DAY AND sequence<COALESCE((SELECT MIN(cursor_sequence) FROM msg_webhook WHERE state='ACTIVE'),9223372036854775807) LIMIT 500");
    }

    public Object interaction(Principal p, String message, String key, Map<String, Object> input) {
        enabled();
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{8,128}"))
            throw new ApiError(422, "互动事件需要稳定幂等键");
        String kind = required(input, "kind", 32);
        if (!Set.of("reply", "callback_requested", "unsubscribe", "conversion", "human_takeover")
                .contains(kind)) throw new ApiError(422, "互动类型无效");
        if (Support.json(input).length > 8192) throw new ApiError(413, "互动内容过大");
        if (kind.equals("reply")) required(input, "text", 2000);
        String businessEvent =
                kind.equals("conversion") ? required(input, "business_event_id", 128) : null;
        if (businessEvent != null && !businessEvent.matches("[A-Za-z0-9_.:-]{1,128}"))
            throw new ApiError(422, "业务事件编号格式无效");
        String hash =
                Support.hex(
                        Support.sha(
                                Support.canonical(Map.of("message_id", message, "data", input))));
        return tx.run(
                () -> {
                    lock(p.tenant());
                    message(p, message);
                    var previous =
                            db.one(
                                    "SELECT id,request_hash FROM msg_interaction WHERE tenant_id=? AND application_id=? AND event_key=?",
                                    p.tenant(),
                                    p.application(),
                                    key);
                    if (previous != null) {
                        if (!hash.equals(previous.get("request_hash")))
                            throw new ApiError(409, "互动幂等键对应不同内容");
                        return Map.of("interaction_id", previous.get("id"), "duplicate", true);
                    }
                    if (businessEvent != null) {
                        var conversion =
                                db.one(
                                        "SELECT id,request_hash FROM msg_interaction WHERE tenant_id=? AND application_id=? AND business_event_id=?",
                                        p.tenant(),
                                        p.application(),
                                        businessEvent);
                        if (conversion != null) {
                            if (!hash.equals(conversion.get("request_hash")))
                                throw new ApiError(409, "业务转化事件已经归属其他内容或消息");
                            return Map.of(
                                    "interaction_id", conversion.get("id"), "duplicate", true);
                        }
                    }
                    var row =
                            db.one(
                                    "SELECT * FROM msg_message WHERE tenant_id=? AND id=?",
                                    p.tenant(),
                                    message);
                    if (row.get("contact_id") == null) throw new ApiError(409, "消息缺少联系人关联");
                    String id = id("interaction"),
                            source = p.application().equals("console") ? "SANDBOX" : "REPORTED";
                    db.jdbc()
                            .update(
                                    "INSERT INTO msg_interaction(id,tenant_id,application_id,message_id,contact_id,event_key,request_hash,kind,source,business_event_id,payload_cipher) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                                    id,
                                    p.tenant(),
                                    p.application(),
                                    message,
                                    row.get("contact_id"),
                                    key,
                                    hash,
                                    kind,
                                    source,
                                    businessEvent,
                                    secrets.encrypt(
                                            "interaction:" + p.tenant() + ":" + id, json(input)));
                    if (kind.equals("unsubscribe"))
                        db.jdbc()
                                .update(
                                        "INSERT IGNORE INTO msg_suppression(tenant_id,recipient_hash,reason) VALUES(?,?,?)",
                                        p.tenant(),
                                        row.get("recipient_hash"),
                                        "消息互动退订");
                    if (Set.of("reply", "callback_requested", "human_takeover").contains(kind)) {
                        // Existing ownership and follow-up fields are never overwritten by an
                        // external report.
                        db.jdbc()
                                .update(
                                        "INSERT IGNORE INTO ops_lead(tenant_id,contact_id,fields_json,tags_json) VALUES(?,?,JSON_OBJECT(),JSON_ARRAY())",
                                        p.tenant(),
                                        row.get("contact_id"));
                        db.jdbc()
                                .update(
                                        "INSERT INTO ops_history(tenant_id,contact_id,actor_id,event_key,payload_hash,action,detail_json) VALUES(?,?,0,?,?,'message_interaction',?)",
                                        p.tenant(),
                                        row.get("contact_id"),
                                        "msg:" + id,
                                        hash,
                                        json(
                                                Map.of(
                                                        "message_id",
                                                        message,
                                                        "kind",
                                                        kind,
                                                        "source",
                                                        source)));
                    }
                    event(
                            new Principal(p.tenant(), row.get("application_id").toString()),
                            message,
                            "message.interaction",
                            Map.of("interaction_id", id, "kind", kind, "source", source));
                    return Map.of("interaction_id", id, "source", source, "duplicate", false);
                });
    }

    public Object interactions(long tenant) {
        return db
                .jdbc()
                .queryForList(
                        "SELECT id,message_id,contact_id,kind,source,payload_cipher,created_at FROM msg_interaction WHERE tenant_id=? ORDER BY created_at DESC LIMIT 200",
                        tenant)
                .stream()
                .map(
                        row -> {
                            String cipher = row.remove("payload_cipher").toString();
                            if (cipher.isEmpty()) row.put("text", "已按保留期限脱敏");
                            else {
                                var payload =
                                        object(
                                                secrets.decrypt(
                                                        "interaction:"
                                                                + tenant
                                                                + ":"
                                                                + row.get("id"),
                                                        cipher));
                                row.put("text", payload.getOrDefault("text", ""));
                                row.put(
                                        "business_event_id",
                                        payload.getOrDefault("business_event_id", ""));
                            }
                            return publicDates(row);
                        })
                .toList();
    }

    public Object report(long tenant) {
        return Map.of(
                "simulated",
                true,
                "messages",
                db.jdbc()
                        .queryForList(
                                "SELECT requested_form,state,billing_state,COUNT(*) AS messages,SUM(sales_amount) AS sales_amount_micro_cny FROM msg_message WHERE tenant_id=? GROUP BY requested_form,state,billing_state",
                                tenant),
                "interactions",
                db.jdbc()
                        .queryForList(
                                "SELECT kind,source,COUNT(*) AS events FROM msg_interaction WHERE tenant_id=? GROUP BY kind,source",
                                tenant));
    }
}
