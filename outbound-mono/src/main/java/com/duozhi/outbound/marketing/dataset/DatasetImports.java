package com.duozhi.outbound.marketing.dataset;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.marketing.common.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Data package versions: an upload is parsed once, staged as encrypted chunks, then imported chunk
 * by chunk. Each chunk commits with its rows, so an interrupted import resumes where it stopped.
 * Phones are normalized, hashed, encrypted and masked; clear text is never stored.
 */
@Service
public class DatasetImports {
    static final int CHUNK_ROWS = 2000;
    static final int MAX_ROWS = 2_000_000;
    static final Set<String> PHONE_HEADERS = Set.of("phone", "usertel", "mobile", "手机号", "手机号码");
    static final Set<String> REGION_HEADERS = Set.of("region", "地区", "省份", "归属地", "城市");
    static final Set<String> CARRIER_HEADERS = Set.of("carrier", "运营商");
    static final Set<String> SOURCE_HEADERS = Set.of("source", "来源", "渠道");
    static final String AUTH_REF = "[A-Za-z0-9_./:-]{1,128}";

    private final MarketingStore db;
    private final PhoneIdentity phones;
    private final ContactPolicy policy;
    private final JsonSupport json;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public DatasetImports(
            MarketingStore db,
            PhoneIdentity phones,
            ContactPolicy policy,
            JsonSupport json,
            ObjectMapper mapper,
            TransactionTemplate tx) {
        this.db = db;
        this.phones = phones;
        this.policy = policy;
        this.json = json;
        this.mapper = mapper;
        this.tx = tx;
    }

    public Map<String, Object> createDataset(
            long tenant, String name, String description, long actor) {
        if (name == null || name.isBlank() || name.length() > 128)
            throw new ApiError(422, "数据包名称应为 1 至 128 个字符");
        String id = MarketingStore.id("ds");
        db.write(
                "INSERT INTO mk_dataset(id,tenant_id,name,description,created_by) VALUES(?,?,?,?,?)",
                id,
                tenant,
                name.trim(),
                description == null
                        ? ""
                        : description.substring(0, Math.min(512, description.length())),
                actor);
        return dataset(tenant, id);
    }

    public Map<String, Object> dataset(long tenant, String id) {
        var row = db.one("SELECT * FROM mk_dataset WHERE tenant_id=? AND id=?", tenant, id);
        if (row == null) throw new ApiError(404, "数据包不存在");
        return row;
    }

    public List<Map<String, Object>> datasets(long tenant) {
        return db.rows(
                "SELECT d.*,(SELECT COUNT(*) FROM mk_dataset_version v WHERE v.tenant_id=d.tenant_id AND v.dataset_id=d.id) AS version_count,"
                        + "(SELECT COALESCE(SUM(v.valid_count),0) FROM mk_dataset_version v WHERE v.tenant_id=d.tenant_id AND v.dataset_id=d.id) AS valid_count"
                        + " FROM mk_dataset d WHERE d.tenant_id=? ORDER BY d.created_at DESC LIMIT 500",
                tenant);
    }

    public List<Map<String, Object>> versions(long tenant, String datasetId) {
        dataset(tenant, datasetId);
        return db.rows(
                "SELECT * FROM mk_dataset_version WHERE tenant_id=? AND dataset_id=? ORDER BY version_no DESC",
                tenant,
                datasetId);
    }

    public Map<String, Object> version(long tenant, String id) {
        var row = db.one("SELECT * FROM mk_dataset_version WHERE tenant_id=? AND id=?", tenant, id);
        if (row == null) throw new ApiError(404, "数据包版本不存在");
        return row;
    }

    /** Ready versions the tenant may select in segments and matching. */
    public List<Map<String, Object>> readyVersions(long tenant) {
        return db.rows(
                "SELECT v.id,v.dataset_id,v.version_no,v.valid_count,v.authorization_ref,v.created_at,d.name AS dataset_name"
                        + " FROM mk_dataset_version v JOIN mk_dataset d ON d.id=v.dataset_id AND d.tenant_id=v.tenant_id"
                        + " WHERE v.tenant_id=? AND v.state='ready' ORDER BY v.created_at DESC LIMIT 500",
                tenant);
    }

    public Map<String, Object> authorize(long tenant, String versionId, String reference) {
        version(tenant, versionId);
        db.write(
                "UPDATE mk_dataset_version SET authorization_ref=? WHERE tenant_id=? AND id=?",
                authorization(reference),
                tenant,
                versionId);
        return version(tenant, versionId);
    }

    static String authorization(String reference) {
        if (reference == null || reference.isBlank()) return null;
        String value = reference.trim();
        if (!value.matches(AUTH_REF))
            throw new ApiError(422, "授权凭证编号格式不正确（字母、数字及 _ . / : -，最长 128 位）");
        return value;
    }

    /** Column roles detected from the first record. */
    record Layout(boolean header, int phone, Map<Integer, String> roles, List<String> names) {}

    static Layout layout(List<String> first) {
        int phone = -1;
        Map<Integer, String> roles = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < first.size(); i++) {
            String name = first.get(i).trim().replace("﻿", "");
            String key = name.toLowerCase(Locale.ROOT);
            names.add(name);
            if (phone < 0 && PHONE_HEADERS.contains(key)) phone = i;
            else if (REGION_HEADERS.contains(key)) roles.putIfAbsent(i, "region");
            else if (CARRIER_HEADERS.contains(key)) roles.putIfAbsent(i, "carrier");
            else if (SOURCE_HEADERS.contains(key)) roles.putIfAbsent(i, "source");
        }
        if (phone >= 0) return new Layout(true, phone, roles, names);
        List<String> generated = new ArrayList<>();
        for (int i = 0; i < first.size(); i++) generated.add(i == 0 ? "phone" : "col" + (i + 1));
        return new Layout(false, 0, Map.of(), generated);
    }

    public Map<String, Object> upload(
            long tenant,
            String datasetId,
            String fileName,
            byte[] content,
            String authorizationRef,
            String source,
            long actor) {
        dataset(tenant, datasetId);
        String reference = authorization(authorizationRef);
        if (content == null || content.length == 0) throw new ApiError(422, "文件为空");
        var decoded = TextFiles.decode(content);
        var records = TextFiles.csv(decoded.text());
        if (records.isEmpty()) throw new ApiError(422, "文件中没有数据行");
        var layout = layout(records.getFirst().fields());
        if (layout.header()) records = records.subList(1, records.size());
        if (records.isEmpty()) throw new ApiError(422, "文件只有表头，没有数据行");
        if (records.size() > MAX_ROWS) throw new ApiError(413, "单个版本最多 200 万行，请拆分文件");
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("has_header", layout.header());
        header.put("phone_column", layout.names().get(layout.phone()));
        header.put("columns", layout.names());
        Map<String, String> roles = new LinkedHashMap<>();
        layout.roles().forEach((i, role) -> roles.put(String.valueOf(i), role));
        header.put("roles", roles);
        header.put("phone_index", layout.phone());
        String id = MarketingStore.id("dsv");
        int chunks = (records.size() + CHUNK_ROWS - 1) / CHUNK_ROWS;
        final var all = records;
        tx.executeWithoutResult(
                s -> {
                    db.one(
                            "SELECT id FROM mk_dataset WHERE tenant_id=? AND id=? FOR UPDATE",
                            tenant,
                            datasetId);
                    long next =
                            db.count(
                                            "SELECT COALESCE(MAX(version_no),0) FROM mk_dataset_version WHERE tenant_id=? AND dataset_id=?",
                                            tenant,
                                            datasetId)
                                    + 1;
                    db.write(
                            "INSERT INTO mk_dataset_version(id,tenant_id,dataset_id,version_no,file_name,encoding,header,authorization_ref,source,state,total_rows,chunk_total,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            id,
                            tenant,
                            datasetId,
                            next,
                            fileName == null
                                    ? ""
                                    : fileName.substring(0, Math.min(255, fileName.length())),
                            decoded.encoding(),
                            json.json(header),
                            reference,
                            source == null
                                    ? ""
                                    : source.substring(0, Math.min(64, source.length())),
                            "importing",
                            all.size(),
                            chunks,
                            actor);
                    for (int c = 0; c < chunks; c++) {
                        var part =
                                all.subList(
                                        c * CHUNK_ROWS, Math.min(all.size(), (c + 1) * CHUNK_ROWS));
                        List<Object> payload = new ArrayList<>();
                        for (var r : part) payload.add(List.of(r.line(), r.fields()));
                        db.write(
                                "INSERT INTO mk_dataset_chunk(version_id,chunk_no,tenant_id,first_line,cipher,state) VALUES(?,?,?,?,?,'pending')",
                                id,
                                c,
                                tenant,
                                part.getFirst().line(),
                                phones.encrypt(tenant, json.json(payload)));
                    }
                });
        return version(tenant, id);
    }

    /** Imports up to {@code limit} pending chunks of one version (or of any version when null). */
    public int process(String versionId, int limit) {
        int done = 0;
        while (done < limit && processOne(versionId)) done++;
        return done;
    }

    /** Single step: imports one staged chunk. Returns false when nothing is pending. */
    public boolean processOne(String versionId) {
        Boolean worked =
                tx.execute(
                        s -> {
                            // Worker scan across tenants: the claimed chunk carries its tenant_id,
                            // which binds every following statement.
                            var chunk =
                                    versionId == null
                                            ? db.one(
                                                    "SELECT * FROM mk_dataset_chunk WHERE state='pending' ORDER BY version_id,chunk_no LIMIT 1 FOR UPDATE SKIP LOCKED")
                                            : db.one(
                                                    "SELECT * FROM mk_dataset_chunk WHERE version_id=? AND state='pending' ORDER BY chunk_no LIMIT 1 FOR UPDATE SKIP LOCKED",
                                                    versionId);
                            if (chunk == null) return false;
                            importChunk(chunk);
                            return true;
                        });
        return Boolean.TRUE.equals(worked);
    }

    private void importChunk(Map<String, Object> chunk) {
        long tenant = ((Number) chunk.get("tenant_id")).longValue();
        String versionId = (String) chunk.get("version_id");
        var version = version(tenant, versionId);
        Map<String, Object> header = json.object(String.valueOf(version.get("header")));
        int phoneIndex = ((Number) header.get("phone_index")).intValue();
        @SuppressWarnings("unchecked")
        List<String> names = (List<String>) header.get("columns");
        @SuppressWarnings("unchecked")
        Map<String, String> roles = (Map<String, String>) header.get("roles");
        List<List<Object>> records;
        try {
            records =
                    mapper.readValue(
                            phones.decrypt(tenant, (String) chunk.get("cipher")),
                            new TypeReference<List<List<Object>>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("staged chunk unreadable", e);
        }
        // Parse and normalize first, then deduplicate against earlier chunks in one query.
        record Parsed(
                int line,
                String status,
                String reason,
                String hash,
                String e164,
                String mask,
                Map<String, String> common,
                Map<String, Object> attrs) {}
        List<Parsed> parsed = new ArrayList<>();
        Map<String, Integer> firstLine = new HashMap<>();
        for (var record : records) {
            int line = ((Number) record.get(0)).intValue();
            @SuppressWarnings("unchecked")
            List<String> fields = (List<String>) record.get(1);
            String raw = TextFiles.cell(fields, phoneIndex);
            Map<String, String> common = new HashMap<>();
            Map<String, Object> attrs = new LinkedHashMap<>();
            for (int i = 0; i < fields.size() && attrs.size() < 30; i++) {
                if (i == phoneIndex) continue;
                String value = fields.get(i).trim();
                if (value.isEmpty()) continue;
                String role = roles.get(String.valueOf(i));
                if (role != null) {
                    common.putIfAbsent(
                            role,
                            value.substring(
                                    0, Math.min(role.equals("carrier") ? 32 : 64, value.length())));
                    continue;
                }
                // Never keep another clear-text number in the attribute JSON.
                String other = PhoneIdentity.normalize(value);
                String key =
                        i < names.size() && !names.get(i).isBlank()
                                ? names.get(i)
                                : "col" + (i + 1);
                attrs.put(
                        key.substring(0, Math.min(64, key.length())),
                        other != null
                                ? PhoneIdentity.mask(other)
                                : scrub(value.substring(0, Math.min(256, value.length()))));
            }
            String e164 = PhoneIdentity.normalize(raw);
            if (raw.isEmpty()) {
                parsed.add(new Parsed(line, "invalid", "号码为空", null, null, "", common, attrs));
            } else if (e164 == null) {
                parsed.add(
                        new Parsed(
                                line,
                                "invalid",
                                "号码格式无效",
                                null,
                                null,
                                rawMask(raw),
                                common,
                                attrs));
            } else {
                String hash = phones.hash(tenant, e164);
                Integer seen = firstLine.putIfAbsent(hash, line);
                parsed.add(
                        new Parsed(
                                line,
                                seen == null ? "valid" : "duplicate",
                                seen == null ? "" : "与第 " + seen + " 行号码重复",
                                hash,
                                e164,
                                PhoneIdentity.mask(e164),
                                common,
                                attrs));
            }
        }
        Map<String, Integer> earlier = new HashMap<>();
        var unique =
                parsed.stream().filter(p -> "valid".equals(p.status())).map(Parsed::hash).toList();
        for (int from = 0; from < unique.size(); from += 500) {
            var part = unique.subList(from, Math.min(unique.size(), from + 500));
            List<Object> args = new ArrayList<>(List.of(versionId));
            args.addAll(part);
            for (var r :
                    db.rows(
                            "SELECT unique_hash,line_no FROM mk_dataset_row WHERE version_id=? AND unique_hash IN ("
                                    + String.join(",", Collections.nCopies(part.size(), "?"))
                                    + ")",
                            args.toArray()))
                earlier.put((String) r.get("unique_hash"), ((Number) r.get("line_no")).intValue());
        }
        int valid = 0, invalid = 0, duplicate = 0, blocked = 0;
        List<Object[]> batch = new ArrayList<>();
        for (var p : parsed) {
            String status = p.status(), reason = p.reason(), blockedReason = null;
            if ("valid".equals(status) && earlier.containsKey(p.hash())) {
                status = "duplicate";
                reason = "与第 " + earlier.get(p.hash()) + " 行号码重复";
            }
            String cipher = null;
            if ("valid".equals(status)) {
                blockedReason = admission(tenant, p.e164(), p.hash());
                cipher = phones.encrypt(tenant, p.e164());
                valid++;
                if (blockedReason != null) blocked++;
            } else if ("duplicate".equals(status)) duplicate++;
            else invalid++;
            batch.add(
                    new Object[] {
                        tenant,
                        versionId,
                        version.get("dataset_id"),
                        p.line(),
                        status,
                        reason,
                        p.hash(),
                        "valid".equals(status) ? p.hash() : null,
                        cipher,
                        p.mask(),
                        p.common().get("region"),
                        p.common().get("carrier"),
                        p.common().get("source") != null
                                ? p.common().get("source")
                                : blank(version.get("source")),
                        json.json(p.attrs()),
                        blockedReason
                    });
        }
        db.jdbc()
                .batchUpdate(
                        "INSERT INTO mk_dataset_row(tenant_id,version_id,dataset_id,line_no,status,reason,phone_hash,unique_hash,phone_cipher,phone_mask,region,carrier,source,attrs,blocked_reason) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        batch);
        db.write(
                "UPDATE mk_dataset_chunk SET state='done',cipher=NULL WHERE version_id=? AND chunk_no=?",
                versionId,
                chunk.get("chunk_no"));
        db.write(
                "UPDATE mk_dataset_version SET valid_count=valid_count+?,invalid_count=invalid_count+?,duplicate_count=duplicate_count+?,blocked_count=blocked_count+?,"
                        + "chunk_done=chunk_done+1,state=IF(chunk_done>=chunk_total,'ready',state),finished_at=IF(chunk_done>=chunk_total,NOW(6),finished_at)"
                        + " WHERE tenant_id=? AND id=?",
                valid,
                invalid,
                duplicate,
                blocked,
                tenant,
                versionId);
    }

    private static final java.util.regex.Pattern EMBEDDED_MOBILE =
            java.util.regex.Pattern.compile(
                    "(?<![0-9])(?:\\+?86[- ]?)?(1[3-9][0-9])[- ]?[0-9]{4}[- ]?([0-9]{4})(?![0-9])");

    /** Masks mobile numbers embedded in free-text attributes. */
    static String scrub(String value) {
        return EMBEDDED_MOBILE.matcher(value).replaceAll("$1****$2");
    }

    private static String blank(Object value) {
        String s = value == null ? "" : value.toString();
        return s.isEmpty() ? null : s;
    }

    /** First admission denial across both channels, labelled with the channel it applies to. */
    private String admission(long tenant, String e164, String hash) {
        var call = policy.admit(tenant, e164, hash, OutcomeCodes.CALL);
        var sms = policy.admit(tenant, e164, hash, OutcomeCodes.SMS);
        if (!call.allowed() && !sms.allowed())
            return call.reason().equals(sms.reason())
                    ? call.reason()
                    : call.reason() + "；" + sms.reason();
        if (!call.allowed()) return "外呼受限：" + call.reason();
        if (!sms.allowed()) return "短信受限：" + sms.reason();
        return null;
    }

    static String rawMask(String raw) {
        String s = raw.length() > 32 ? raw.substring(0, 32) : raw;
        if (s.length() <= 4) return "*".repeat(s.length());
        return s.substring(0, 2)
                + "*".repeat(Math.max(1, s.length() - 4))
                + s.substring(s.length() - 2);
    }

    public Map<String, Object> rows(
            long tenant, String versionId, String status, int page, int size) {
        version(tenant, versionId);
        int limit = Math.max(1, Math.min(size, 200)), offset = Math.max(0, page - 1) * limit;
        String filter =
                switch (status == null ? "" : status) {
                    case "valid" -> " AND status='valid' AND blocked_reason IS NULL";
                    case "blocked" -> " AND status='valid' AND blocked_reason IS NOT NULL";
                    case "invalid" -> " AND status='invalid'";
                    case "duplicate" -> " AND status='duplicate'";
                    default -> "";
                };
        var items =
                db.rows(
                        "SELECT id,line_no,status,reason,phone_mask,region,carrier,source,attrs,blocked_reason FROM mk_dataset_row WHERE tenant_id=? AND version_id=?"
                                + filter
                                + " ORDER BY line_no LIMIT ? OFFSET ?",
                        tenant,
                        versionId,
                        limit,
                        offset);
        long total =
                db.count(
                        "SELECT COUNT(*) FROM mk_dataset_row WHERE tenant_id=? AND version_id=?"
                                + filter,
                        tenant,
                        versionId);
        return Map.of("items", items, "total", total);
    }
}
