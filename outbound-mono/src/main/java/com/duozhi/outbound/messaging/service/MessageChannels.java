package com.duozhi.outbound.messaging.service;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.messaging.dao.MessageStore;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Tenant-owned immutable connection versions. Secrets never appear in public channel results. */
@Service
public class MessageChannels {
    private final MessageStore db;
    private final MessageSecrets secrets;
    private final Transactions tx;
    private final Config config;

    public MessageChannels(
            MessageStore db, MessageSecrets secrets, Transactions tx, Config config) {
        this.db = db;
        this.secrets = secrets;
        this.tx = tx;
        this.config = config;
    }

    public static long integer(Map<String, Object> body, String key, long fallback, long maximum) {
        return Messaging.number(body, key, fallback, maximum);
    }

    public Map<String, Object> create(long tenant, Map<String, Object> body) {
        String name = Messaging.required(body, "name", 128),
                protocol = Messaging.required(body, "protocol", 16);
        if (!Set.of("http", "cmpp").contains(protocol))
            throw new ApiError(422, "仅支持 HTTP 和 CMPP 通道");
        String env = Objects.toString(body.getOrDefault("environment", "test"));
        if (!Set.of("test", "production").contains(env)) throw new ApiError(422, "通道环境无效");
        if (!(body.get("forms") instanceof List<?> forms)
                || forms.isEmpty()
                || forms.size() > 4
                || forms.size() != new HashSet<>(forms).size()
                || !MessageContent.FORMS.containsAll(forms))
            throw new ApiError(422, "请选择通道支持的消息形态");
        if (protocol.equals("cmpp") && !forms.equals(List.of("sms")))
            throw new ApiError(422, "标准 CMPP 通道只支持普通短信");
        var settings = new LinkedHashMap<>(Messaging.object(body.get("config")));
        var credential = new LinkedHashMap<>(Messaging.object(body.get("credentials")));
        if (!Set.of(
                        "endpoint",
                        "host",
                        "port",
                        "username",
                        "sp_code",
                        "service_id",
                        "tps",
                        "daily_limit",
                        "query_supported",
                        "billing_basis",
                        "purchase_unit_price",
                        "supports")
                .containsAll(settings.keySet())) throw new ApiError(422, "存在未支持的通道配置字段");
        if (!Set.of("api_key", "callback_secret", "password").containsAll(credential.keySet()))
            throw new ApiError(422, "凭据字段无效");
        long tps = integer(settings, "tps", 10, 1000),
                daily = integer(settings, "daily_limit", 10000, 10000000);
        if (tps < 1 || daily < 1) throw new ApiError(422, "通道限额必须为正整数");
        settings.put("tps", tps);
        settings.put("daily_limit", daily);
        settings.put(
                "purchase_unit_price", integer(settings, "purchase_unit_price", 0, 1000000000));
        String basis = Objects.toString(settings.getOrDefault("billing_basis", "delivered"));
        if (!Set.of("accepted", "delivered").contains(basis))
            throw new ApiError(422, "计费口径须为受理或送达");
        settings.put("billing_basis", basis);
        if (!(settings.getOrDefault("query_supported", false) instanceof Boolean))
            throw new ApiError(422, "查询能力须为布尔值");
        settings.putIfAbsent("query_supported", false);
        Object support = settings.getOrDefault("supports", List.of("delivery"));
        if (!(support instanceof List<?> capabilities)
                || !Set.of("delivery", "display", "read", "click", "reply", "unsubscribe")
                        .containsAll(capabilities)) throw new ApiError(422, "回执能力无效");
        settings.put("supports", support);
        if (protocol.equals("http")) {
            validateHttp(Messaging.required(settings, "endpoint", 2048), env);
            Messaging.required(credential, "api_key", 2048);
            if (Messaging.required(credential, "callback_secret", 256).length() < 32)
                throw new ApiError(422, "回执密钥至少 32 字符");
        } else {
            validateCmpp(Messaging.required(settings, "host", 253), env);
            long port = integer(settings, "port", 7890, 65535);
            if (port < 1) throw new ApiError(422, "CMPP 端口无效");
            settings.put("port", port);
            String username = Messaging.required(settings, "username", 6),
                    sp = Messaging.required(settings, "sp_code", 21);
            if (!username.matches("[A-Za-z0-9]{1,6}") || !sp.matches("[0-9]{1,21}"))
                throw new ApiError(422, "CMPP 账号或接入码无效");
            Messaging.required(credential, "password", 64);
            String service = Objects.toString(settings.getOrDefault("service_id", ""));
            if (!service.matches("[A-Za-z0-9]{0,10}")) throw new ApiError(422, "CMPP 业务代码无效");
            settings.put("service_id", service);
            if (Boolean.TRUE.equals(settings.get("query_supported")))
                throw new ApiError(422, "CMPP 不提供按平台消息编号查询的通用接口");
            if (!Set.of("delivery", "reply", "unsubscribe").containsAll((List<?>) support))
                throw new ApiError(422, "CMPP 不提供展示、已读或点击回执");
        }
        return tx.run(
                () -> {
                    lock(tenant);
                    String id = Messaging.id("chn");
                    db.jdbc()
                            .update(
                                    "INSERT INTO msg_channel(id,tenant_id,name,protocol,environment,forms,config,credential_cipher) VALUES(?,?,?,?,?,?,?,?)",
                                    id,
                                    tenant,
                                    name,
                                    protocol,
                                    env,
                                    Messaging.json(forms),
                                    Messaging.json(settings),
                                    secrets.encrypt(
                                            "channel:" + tenant + ":" + id,
                                            Messaging.json(credential)));
                    return get(tenant, id);
                });
    }

    public boolean internalTest(String environment) {
        return "internal-test".equals(config.get("ENV", "")) && "test".equals(environment);
    }

    public void validateHttp(String endpoint, String environment) {
        try {
            URI uri = URI.create(endpoint);
            if (internalTest(environment)
                    && "http".equals(uri.getScheme())
                    && "127.0.0.1".equals(uri.getHost())
                    && uri.getUserInfo() == null
                    && uri.getFragment() == null
                    && uri.getRawQuery() == null
                    && uri.getPort() > 0) return;
            PinnedWebhookTransport.validate(uri);
            if (uri.getRawQuery() != null) throw new IllegalArgumentException();
        } catch (Exception e) {
            throw new ApiError(422, "HTTP 通道需要公开 HTTPS 接口；仅隔离测试允许本机 HTTP");
        }
    }

    public void validateCmpp(String host, String environment) {
        if (host.contains("/") || host.contains(":") || !host.matches("[A-Za-z0-9.-]{1,253}"))
            throw new ApiError(422, "CMPP 主机无效");
        if (internalTest(environment) && host.equals("127.0.0.1")) return;
        var allowed =
                Arrays.stream(config.get("MESSAGING_CMPP_ALLOWED_HOSTS", "").split(","))
                        .map(String::strip)
                        .filter(v -> !v.isEmpty())
                        .toList();
        if (!allowed.contains(host)) throw new ApiError(422, "CMPP 主机尚未加入平台运营商地址白名单");
    }

    private void lock(long tenant) {
        var t = db.one("SELECT enabled FROM tenant WHERE id=? FOR UPDATE", tenant);
        if (t == null || !Boolean.TRUE.equals(t.get("enabled"))) throw new ApiError(403, "企业已停用");
    }

    public Map<String, Object> internal(long tenant, String id) {
        var row = db.one("SELECT * FROM msg_channel WHERE tenant_id=? AND id=?", tenant, id);
        if (row == null) throw new ApiError(404, "通道不存在");
        row.put("config", Messaging.object(row.get("config")));
        try {
            row.put("forms", Support.JSON.readValue(row.get("forms").toString(), List.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return row;
    }

    public Map<String, Object> credentials(Map<String, Object> row) {
        return Messaging.object(
                secrets.decrypt(
                        "channel:" + row.get("tenant_id") + ":" + row.get("id"),
                        row.get("credential_cipher").toString()));
    }

    public Map<String, Object> get(long tenant, String id) {
        var row = new LinkedHashMap<>(internal(tenant, id));
        row.remove("credential_cipher");
        row.put("credentials_configured", true);
        return row;
    }

    public List<Map<String, Object>> list(long tenant) {
        return db
                .jdbc()
                .queryForList(
                        "SELECT id FROM msg_channel WHERE tenant_id=? ORDER BY created_at DESC,id DESC LIMIT 200",
                        String.class,
                        tenant)
                .stream()
                .map(id -> get(tenant, id))
                .toList();
    }

    public Object state(long tenant, String id, String state) {
        if (!Set.of("ACTIVE", "DISABLED").contains(state)) throw new ApiError(422, "通道状态无效");
        return tx.run(
                () -> {
                    lock(tenant);
                    var row = internal(tenant, id);
                    if (state.equals("ACTIVE") && "UNVERIFIED".equals(row.get("verification")))
                        throw new ApiError(409, "请先完成通道连接测试");
                    if (state.equals("ACTIVE")
                            && "production".equals(row.get("environment"))
                            && !"PROVIDER_VERIFIED".equals(row.get("verification")))
                        throw new ApiError(409, "生产通道尚未登记供应商实发验收");
                    db.jdbc()
                            .update(
                                    "UPDATE msg_channel SET state=? WHERE tenant_id=? AND id=?",
                                    state,
                                    tenant,
                                    id);
                    return get(tenant, id);
                });
    }

    public void connectionVerified(long tenant, String id) {
        db.jdbc()
                .update(
                        "UPDATE msg_channel SET verification=IF(verification='PROVIDER_VERIFIED',verification,'LOCAL_VERIFIED'),verified_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=?",
                        tenant,
                        id);
    }

    public Object recordAcceptance(long tenant, String id, Map<String, Object> input) {
        String note = Messaging.required(input, "evidence", 512);
        if (note.length() < 12 || !Boolean.TRUE.equals(input.get("real_device_confirmed")))
            throw new ApiError(422, "需登记真实终端送达与回执验收证据");
        return tx.run(
                () -> {
                    lock(tenant);
                    var row = internal(tenant, id);
                    if ("UNVERIFIED".equals(row.get("verification")))
                        throw new ApiError(409, "请先完成连接验证");
                    db.jdbc()
                            .update(
                                    "UPDATE msg_channel SET verification='PROVIDER_VERIFIED',verification_note=?,verified_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=?",
                                    note,
                                    tenant,
                                    id);
                    return get(tenant, id);
                });
    }

    public Object bind(long tenant, String id, Map<String, Object> body) {
        String resource = Messaging.required(body, "resource_id", 64),
                ref = Messaging.required(body, "provider_ref", 128),
                state = Objects.toString(body.getOrDefault("state", "PENDING"));
        if (!Set.of("PENDING", "APPROVED", "REJECTED", "DISABLED").contains(state))
            throw new ApiError(422, "渠道审核状态无效");
        String evidence = Objects.toString(body.getOrDefault("evidence", ""));
        if (evidence.length() > 512 || (state.equals("APPROVED") && evidence.isBlank()))
            throw new ApiError(422, "批准须记录供应商审核证据");
        java.sql.Timestamp expiry = null;
        if (body.get("expires_at") != null && !body.get("expires_at").toString().isBlank()) {
            try {
                expiry = java.sql.Timestamp.from(Instant.parse(body.get("expires_at").toString()));
            } catch (Exception e) {
                throw new ApiError(422, "审核有效期无效");
            }
        }
        var expires = expiry;
        return tx.run(
                () -> {
                    lock(tenant);
                    internal(tenant, id);
                    var r =
                            db.one(
                                    "SELECT kind,state FROM msg_resource WHERE tenant_id=? AND id=?",
                                    tenant,
                                    resource);
                    if (r == null) {
                        r =
                                db.one(
                                        "SELECT 'asset' AS kind,state FROM msg_asset WHERE tenant_id=? AND id=?",
                                        tenant,
                                        resource);
                    }
                    if (r == null || !Set.of("template", "sender", "asset").contains(r.get("kind")))
                        throw new ApiError(404, "模板、签名或素材不存在");
                    if (state.equals("APPROVED")
                            && !Set.of("APPROVED", "READY").contains(r.get("state")))
                        throw new ApiError(409, "资源尚未内部批准");
                    db.jdbc()
                            .update(
                                    "INSERT INTO msg_channel_binding(tenant_id,channel_id,resource_id,kind,provider_ref,state,evidence,expires_at) VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE provider_ref=VALUES(provider_ref),state=VALUES(state),evidence=VALUES(evidence),expires_at=VALUES(expires_at),updated_at=CURRENT_TIMESTAMP(6)",
                                    tenant,
                                    id,
                                    resource,
                                    r.get("kind"),
                                    ref,
                                    state,
                                    evidence,
                                    expires);
                    return bindings(tenant, id);
                });
    }

    public List<Map<String, Object>> bindings(long tenant, String id) {
        internal(tenant, id);
        return db.jdbc()
                .queryForList(
                        "SELECT resource_id,kind,provider_ref,state,evidence,expires_at,updated_at FROM msg_channel_binding WHERE tenant_id=? AND channel_id=? ORDER BY updated_at DESC LIMIT 500",
                        tenant,
                        id);
    }

    public String binding(long tenant, String id, String resource) {
        var r =
                db.one(
                        "SELECT provider_ref FROM msg_channel_binding WHERE tenant_id=? AND channel_id=? AND resource_id=? AND state='APPROVED' AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP(6))",
                        tenant,
                        id,
                        resource);
        if (r == null) throw new ApiError(409, "发送主体、模板或素材尚未完成有效的渠道审核");
        return r.get("provider_ref").toString();
    }

    public Map<String, Object> usable(long tenant, String id, String form) {
        var channel = internal(tenant, id);
        if (!"ACTIVE".equals(channel.get("state"))
                || !((List<?>) channel.get("forms")).contains(form))
            throw new ApiError(409, "通道未启用或不支持该消息形态");
        if (!config.flag("MESSAGING_TRANSPORT_ENABLED", false))
            throw new ApiError(503, "真实通道执行开关尚未开启");
        boolean production = "production".equals(channel.get("environment"));
        if (config.production() != production) throw new ApiError(409, "消息通道与当前运行环境不匹配");
        if (production
                && (!config.flag("MESSAGING_PRODUCTION_ENABLED", false)
                        || !"PROVIDER_VERIFIED".equals(channel.get("verification"))))
            throw new ApiError(409, "生产通道尚未通过准入");
        if ("UNVERIFIED".equals(channel.get("verification"))) throw new ApiError(409, "通道尚未验证");
        return channel;
    }

    /** Called under tenant lock before claiming a message; throttling leaves it queued. */
    public boolean acquire(Map<String, Object> channel, int units) {
        String id = channel.get("id").toString();
        var c = Messaging.object(channel.get("config"));
        long now = Instant.now().getEpochSecond();
        for (var e :
                Map.of(
                                "s:" + now,
                                integer(c, "tps", 10, 1000),
                                "d:" + Math.floorDiv(now, 86400),
                                integer(c, "daily_limit", 10000, 10000000))
                        .entrySet()) {
            Long used =
                    db.jdbc()
                            .query(
                                    "SELECT used FROM msg_channel_window WHERE channel_id=? AND window_key=?",
                                    rs -> rs.next() ? rs.getLong(1) : 0L,
                                    id,
                                    e.getKey());
            if (used + (e.getKey().startsWith("s:") ? 1 : units) > e.getValue()) return false;
        }
        for (String window : List.of("s:" + now, "d:" + Math.floorDiv(now, 86400)))
            db.jdbc()
                    .update(
                            "INSERT INTO msg_channel_window(channel_id,window_key,used,expires_at) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE used=used+VALUES(used)",
                            id,
                            window,
                            window.startsWith("s:") ? 1 : units,
                            java.sql.Timestamp.from(Instant.now().plusSeconds(172800)));
        return true;
    }
}
