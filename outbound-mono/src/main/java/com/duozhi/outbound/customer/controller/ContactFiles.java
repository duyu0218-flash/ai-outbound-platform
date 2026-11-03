package com.duozhi.outbound.customer.controller;

import com.duozhi.outbound.auth.domain.AdminPermission;
import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.common.support.Ascii;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.common.support.Values;
import com.duozhi.outbound.system.service.Audit;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import org.apache.commons.csv.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/contacts")
@AdminPermission("contacts:write")
public class ContactFiles {
    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.workspace.service.ContactVariables variables;

    private final Store store;
    private final Transactions tx;
    private final Config config;
    private final JsonSupport json;
    private final Audit audit;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public ContactFiles(
            Store store,
            Transactions tx,
            Config config,
            JsonSupport json,
            Audit audit,
            javax.sql.DataSource source) {
        this.store = store;
        this.tx = tx;
        this.config = config;
        this.json = json;
        this.audit = audit;
        this.jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
    }

    private Identity actor(HttpServletRequest r) {
        Identity i = Identity.from(r);
        i.admin("contacts:write");
        return i;
    }

    @GetMapping("/import/template")
    public ResponseEntity<byte[]> template(HttpServletRequest request) throws IOException {
        actor(request);
        try (var input = getClass().getResourceAsStream("/templates/contacts-import.xlsx")) {
            if (input == null) throw new ApiError(503, "导入模板暂不可用");
            return ResponseEntity.ok()
                    .header(
                            "Content-Type",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .header("Content-Disposition", "attachment; filename=contacts-import.xlsx")
                    .body(input.readAllBytes());
        }
    }

    @PostMapping("/import/preview")
    public Map<String, Object> preview(
            @RequestParam
            MultipartFile file,
            @RequestParam(defaultValue = "false")
            boolean upsert,
            HttpServletRequest request)
            throws IOException {
        return importFile(file, upsert, null, request, true);
    }

    @PostMapping("/import")
    public Map<String, Object> importCsv(
            @RequestParam
            MultipartFile file,
            @RequestParam(defaultValue = "true")
            boolean upsert,
            @RequestHeader(value = "Idempotency-Key", required = false)
            String key,
            HttpServletRequest request)
            throws IOException {
        return importFile(file, upsert, key, request, false);
    }

    private Map<String, Object> importFile(
            MultipartFile file,
            boolean upsert,
            String key,
            HttpServletRequest request,
            boolean preview)
            throws IOException {
        return process(file, upsert, key, actor(request), preview, Map.of());
    }

    public String readContent(MultipartFile file) throws IOException {
        if (file.getSize() > Math.max(1, config.number("CONTACT_IMPORT_MAX_BYTES", 20971520)))
            throw new ApiError(413, "csv file is too large");
        if (file.isEmpty()) throw new ApiError(400, "上传文件为空");
        String filename = Objects.toString(file.getOriginalFilename(), "").toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".xlsx") && !filename.endsWith(".csv"))
            throw new ApiError(400, "请上传 .xlsx 或 UTF-8 CSV 文件");
        String text;
        try {
            text =
                    filename.endsWith(".xlsx")
                            ? ContactWorkbook.csv(
                                    file.getBytes(),
                                    Math.max(1, config.number("CONTACT_IMPORT_MAX_ROWS", 200000)))
                            : StandardCharsets.UTF_8
                                    .newDecoder()
                                    .onMalformedInput(CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                                    .decode(ByteBuffer.wrap(file.getBytes()))
                                    .toString();
        } catch (CharacterCodingException e) {
            throw new ApiError(400, "csv must be UTF-8 encoded");
        }
        if (text.startsWith("\ufeff")) text = text.substring(1);
        return text;
    }

    public Map<String, Object> process(
            MultipartFile file,
            boolean upsert,
            String key,
            Identity i,
            boolean preview,
            Map<String, String> mapping)
            throws IOException {
        return process(file, upsert, key, i, preview, mapping, Map.of());
    }

    public Map<String, Object> process(
            MultipartFile file,
            boolean upsert,
            String key,
            Identity i,
            boolean preview,
            Map<String, String> mapping,
            Map<String, Object> schema)
            throws IOException {
        if (!preview && config.production() && key == null)
            throw new ApiError(400, "Idempotency-Key is required in production");
        String requestKey = key == null ? UUID.randomUUID().toString() : key.trim();
        if (!requestKey.matches("[\\p{L}\\p{N}._:-]{1,128}"))
            throw new ApiError(400, "invalid Idempotency-Key");
        String text = readContent(file);
        final String content = text;
        // The compatibility endpoint must not hold the platform writer for an unbounded file.
        // Large uploads use the durable worker, which calls this path in 100-row chunks.
        if (!preview)
            try (var parser = CSVFormat.DEFAULT.parse(new StringReader(content))) {
                int count = 0;
                boolean first = true;
                for (var record : parser) {
                    var fields = new ArrayList<String>();
                    for (String cell : record) fields.add(cell.trim());
                    if (fields.stream().allMatch(String::isEmpty)) continue;
                    if (first) {
                        first = false;
                        boolean header =
                                fields.stream()
                                        .map(
                                                v ->
                                                        mapping.getOrDefault(
                                                                v, ContactWorkbook.column(v)))
                                        .anyMatch("phone"::equals);
                        if (header) continue;
                    }
                    if (++count > Math.max(1, config.number("CONTACT_IMPORT_SYNC_MAX_ROWS", 1000)))
                        throw new ApiError(413, "大名单请通过客户名单的异步导入入口上传");
                }
            } catch (IOException | UncheckedIOException e) {
                throw new ApiError(400, "invalid CSV format");
            }

        final String digest;
        try {
            digest =
                    java.util.HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    (Base64.getEncoder()
                                                                            .encodeToString(
                                                                                    file.getBytes())
                                                                    + ":"
                                                                    + upsert
                                                                    + ":"
                                                                    + new TreeMap<>(mapping)
                                                                    + ":"
                                                                    + json.canonical(schema, false))
                                                            .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        java.util.function.Supplier<Map<String, Object>> work =
                () -> {
                    if (!preview) {
                        tx.platform();
                        tx.tenant(i.tenant());
                    }
                    var prior =
                            jdbc.queryForList(
                                    "SELECT file_sha,result_json FROM platform_contact_batch WHERE tenant_id=? AND request_key=?",
                                    i.tenant(),
                                    requestKey);
                    if (!preview && !prior.isEmpty()) {
                        if (!digest.equals(prior.getFirst().get("file_sha")))
                            throw new ApiError(409, "同一导入请求标识不能用于不同文件或处理方式");
                        return json.object(prior.getFirst().get("result_json").toString());
                    }
                    Map<String, Object> job =
                            store.one(
                                    "contactimportjob",
                                    "findByRequestKey",
                                    Values.map("tenantId", i.tenant(), "requestKey", requestKey));
                    if (!preview && job != null && "completed".equals(job.get("state")))
                        return json.object(job.get("result_json").toString());
                    if (!preview && job != null && "processing".equals(job.get("state")))
                        throw new ApiError(409, "contact import is already processing");
                    if (!preview && job == null)
                        job =
                                store.insert(
                                        "contactimportjob",
                                        JsonSupport.map(
                                                "tenant_id",
                                                i.tenant(),
                                                "request_key",
                                                requestKey));
                    int total = 0, created = 0, updated = 0, skipped = 0, failed = 0;
                    List<String> errors = new ArrayList<>();
                    List<Map<String, Object>> previewRows = new ArrayList<>();
                    List<Object> contactIds = new ArrayList<>();
                    var memberValues = new LinkedHashMap<Object, Map<String, Object>>();
                    var newContactIds = new HashSet<Object>();
                    int validRows = 0, dncRows = 0, unknownRows = 0, existingRows = 0;
                    String batchId = UUID.randomUUID().toString();
                    Set<String> seen = new HashSet<>();
                    try (CSVParser parser = CSVFormat.DEFAULT.parse(new StringReader(content))) {
                        Iterator<CSVRecord> records = parser.iterator();
                        List<String> columns =
                                Arrays.asList(
                                        "phone",
                                        "name",
                                        "tags",
                                        "consent_state",
                                        "dnc",
                                        "dnc_reason",
                                        "timezone");
                        boolean first = true;
                        while (records.hasNext()) {
                            CSVRecord row = records.next();
                            List<String> fields = new ArrayList<>();
                            for (String value : row) fields.add(value.trim());
                            if (fields.stream().allMatch(String::isEmpty)) continue;
                            if (first) {
                                first = false;
                                List<String> header = new ArrayList<>();
                                for (String field : fields)
                                    header.add(
                                            mapping.getOrDefault(
                                                    field, ContactWorkbook.column(field)));
                                if (header.contains("phone")) {
                                    if (header.get(0).isEmpty())
                                        throw new ApiError(400, "invalid CSV header");
                                    if (new HashSet<>(header).size() != header.size())
                                        throw new ApiError(400, "模板存在重复列名");
                                    columns = header;
                                    continue;
                                }
                            }
                            total++;
                            if (total
                                    > Math.max(1, config.number("CONTACT_IMPORT_MAX_ROWS", 200000)))
                                throw new ApiError(413, "csv has too many rows");
                            Map<String, String> values = new HashMap<>();
                            for (int n = 0; n < Math.min(columns.size(), fields.size()); n++)
                                values.put(columns.get(n), fields.get(n));
                            String phone,
                                    consent =
                                            values.getOrDefault("consent_state", "")
                                                    .transform(Ascii::lower),
                                    zone = values.getOrDefault("timezone", "");
                            consent = ContactWorkbook.consent(consent);
                            if (consent.isEmpty()) consent = "unknown";
                            if (consent.startsWith("consentstate."))
                                consent = consent.substring(13);
                            if (zone.isEmpty()) zone = "Asia/Shanghai";
                            try {
                                String rawPhone = values.getOrDefault("phone", "");
                                if (!rawPhone.matches("[+0-9()\\s-]+"))
                                    throw new ApiError(400, "phone must contain 6 to 15 digits");
                                phone = Contacts.phone(rawPhone);
                                String dncValue =
                                        values.getOrDefault("dnc", "").transform(Ascii::lower);
                                if (!Set.of(
                                                "", "0", "1", "true", "false", "t", "f", "yes",
                                                "no", "y", "n", "on", "off", "是", "否")
                                        .contains(dncValue)) throw new ApiError(400, "禁止呼叫请填写是或否");
                                store.encode("contact", "consent_state", consent);
                                Contacts.timezone(zone);
                            } catch (ApiError e) {
                                failed++;
                                errors.add(
                                        "row "
                                                + row.getRecordNumber()
                                                + ": "
                                                + (e.getMessage().startsWith("phone must")
                                                        ? "invalid phone"
                                                        : e.getMessage()));
                                continue;
                            }
                            if (!seen.add(phone)) {
                                skipped++;
                                errors.add(
                                        "row "
                                                + row.getRecordNumber()
                                                + ": duplicate phone in uploaded file");
                                continue;
                            }
                            if (nameLength(values) > 160
                                    || values.getOrDefault("tags", "").length() > 2000
                                    || values.getOrDefault("dnc_reason", "").length() > 1000) {
                                failed++;
                                errors.add("row " + row.getRecordNumber() + ": 文本长度超出限制");
                                continue;
                            }
                            Map<String, Object> old =
                                    store.one(
                                            "contact",
                                            "findByPhone",
                                            Values.map("tenantId", i.tenant(), "phone", phone));
                            Map<String, Object> business;
                            try {
                                var effective = new HashMap<>(values);
                                if (old != null
                                        && (!upsert || values.getOrDefault("name", "").isBlank()))
                                    effective.put("name", Objects.toString(old.get("name"), ""));
                                effective.put("phone", phone);
                                business = variables.values(schema, effective);
                            } catch (ApiError e) {
                                failed++;
                                errors.add("row " + row.getRecordNumber() + ": " + e.getMessage());
                                continue;
                            }
                            validRows++;
                            if (old != null) existingRows++;
                            boolean effectiveDnc =
                                    old != null
                                                    && (!upsert
                                                            || values.getOrDefault("dnc", "")
                                                                    .isBlank())
                                            ? Boolean.TRUE.equals(old.get("dnc"))
                                            : Set.of("1", "true", "是", "yes")
                                                    .contains(values.getOrDefault("dnc", ""));
                            String effectiveConsent =
                                    old != null
                                                    && (!upsert
                                                            || values.getOrDefault(
                                                                            "consent_state", "")
                                                                    .isBlank())
                                            ? Objects.toString(old.get("consent_state"), "unknown")
                                            : consent;
                            if (effectiveDnc) dncRows++;
                            if (!"consented".equals(effectiveConsent)) unknownRows++;
                            if (previewRows.size() < 50)
                                previewRows.add(
                                        JsonSupport.map(
                                                "row",
                                                row.getRecordNumber(),
                                                "phone",
                                                phone,
                                                "name",
                                                values.getOrDefault("name", ""),
                                                "variables",
                                                business,
                                                "dnc",
                                                effectiveDnc,
                                                "consent_state",
                                                effectiveConsent,
                                                "action",
                                                old == null ? "新增" : upsert ? "更新" : "跳过已有号码"));
                            if (old != null) {
                                contactIds.add(old.get("id"));
                                memberValues.put(old.get("id"), business);
                            }
                            if (old != null && !upsert) {
                                skipped++;
                                continue;
                            }
                            String name = values.getOrDefault("name", ""),
                                    reason = values.getOrDefault("dnc_reason", "");
                            boolean yes = "consented".equals(consent);
                            Map<String, Object> changes =
                                    JsonSupport.map(
                                            "phone",
                                            phone,
                                            "name",
                                            name.isEmpty()
                                                    ? (old == null ? null : old.get("name"))
                                                    : name,
                                            "tags",
                                            values.getOrDefault("tags", ""),
                                            "consent_state",
                                            consent,
                                            "dnc",
                                            Values.set("1", "true", "t", "yes", "y", "on", "是")
                                                    .contains(
                                                            values.getOrDefault("dnc", "")
                                                                    .transform(Ascii::lower)),
                                            "dnc_reason",
                                            reason.isEmpty() ? null : reason,
                                            "timezone",
                                            zone,
                                            "consented_at",
                                            yes ? Store.now() : null,
                                            "consented_by",
                                            yes ? i.actor() : null);
                            if (old != null) {
                                // A blank or absent cell never clears contact, consent or DNC data.
                                for (String field :
                                        List.of(
                                                "name",
                                                "tags",
                                                "consent_state",
                                                "dnc",
                                                "dnc_reason",
                                                "timezone"))
                                    if (values.getOrDefault(field, "").isBlank())
                                        changes.remove(field);
                                if (values.getOrDefault("consent_state", "").isBlank()) {
                                    changes.remove("consented_at");
                                    changes.remove("consented_by");
                                }
                            }
                            if (old == null) {
                                changes.put("tenant_id", i.tenant());
                                if (!preview) {
                                    Object newId = store.insert("contact", changes).get("id");
                                    contactIds.add(newId);
                                    newContactIds.add(newId);
                                    memberValues.put(newId, business);
                                }
                                created++;
                            } else {
                                if (!preview)
                                    store.update("contact", i.tenant(), old.get("id"), changes);
                                updated++;
                            }
                        }
                    } catch (IOException | UncheckedIOException e) {
                        throw new ApiError(400, "invalid CSV format");
                    }
                    if (total == 0) throw new ApiError(400, "文件中没有号码，请在模板外呼名单页填写数据");
                    Map<String, Object> result =
                            JsonSupport.map(
                                    "total",
                                    total,
                                    "created",
                                    created,
                                    "updated",
                                    updated,
                                    "skipped",
                                    skipped,
                                    "failed",
                                    failed,
                                    "errors",
                                    errors.subList(0, Math.min(errors.size(), 1000)));
                    result.put("error_count", errors.size());
                    if (preview) result.put("contact_ids", List.of());
                    result.put("batch_id", preview ? null : batchId);
                    result.put("selected_count", preview ? validRows : contactIds.size());
                    result.put(
                            "flags",
                            JsonSupport.map(
                                    "dnc",
                                    dncRows,
                                    "not_consented",
                                    unknownRows,
                                    "existing",
                                    existingRows));
                    result.put("variable_schema", schema);
                    result.put("preview_rows", previewRows);
                    if (preview) return result;
                    store.update(
                            "contactimportjob",
                            i.tenant(),
                            job.get("id"),
                            JsonSupport.map(
                                    "state", "completed", "result_json", json.json(result)));
                    jdbc.update(
                            "INSERT INTO platform_contact_batch(tenant_id,id,request_key,name,file_sha,result_json,created_by,created_at) VALUES(?,?,?,?,?,?,?,?)",
                            i.tenant(),
                            batchId,
                            requestKey,
                            Objects.toString(file.getOriginalFilename(), "名单"),
                            digest,
                            json.json(result),
                            i.userId(),
                            Store.now());
                    jdbc.batchUpdate(
                            "INSERT INTO platform_contact_batch_member(tenant_id,batch_id,contact_id,variables_json) VALUES(?,?,?,?)",
                            contactIds,
                            500,
                            (ps, id) -> {
                                ps.setLong(1, i.tenant());
                                ps.setString(2, batchId);
                                ps.setObject(3, id);
                                ps.setString(4, json.json(memberValues.getOrDefault(id, Map.of())));
                            });
                    for (var entry : memberValues.entrySet()) {
                        if (entry.getValue().isEmpty()) continue;
                        // Skipped existing profiles are preserved; batch variables remain usable
                        // independently.
                        if (!upsert && !newContactIds.contains(entry.getKey())) continue;
                        var merged =
                                variables.current(
                                        i.tenant(), ((Number) entry.getKey()).longValue());
                        merged.putAll(entry.getValue());
                        jdbc.update(
                                "INSERT INTO platform_contact_value(tenant_id,contact_id,values_json,updated_at) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE values_json=VALUES(values_json),updated_at=VALUES(updated_at)",
                                i.tenant(),
                                entry.getKey(),
                                json.json(merged),
                                Store.now());
                    }
                    var detailRows = new ArrayList<Map<String, Object>>();
                    for (int n = 0; n < errors.size(); n++)
                        detailRows.add(JsonSupport.map("seq", n + 1, "message", errors.get(n)));
                    jdbc.batchUpdate(
                            "INSERT INTO platform_contact_batch_error(tenant_id,batch_id,row_seq,message) VALUES(?,?,?,?)",
                            detailRows,
                            500,
                            (ps, row) -> {
                                ps.setLong(1, i.tenant());
                                ps.setString(2, batchId);
                                ps.setInt(3, ((Number) row.get("seq")).intValue());
                                ps.setString(4, row.get("message").toString());
                            });
                    audit.record(
                            i,
                            "import",
                            "contact",
                            null,
                            "rows=" + total + ", created=" + created + ", updated=" + updated);
                    return result;
                };
        return preview ? tx.run(work) : tx.serialized(work);
    }

    private int nameLength(Map<String, String> values) {
        return values.getOrDefault("name", "").length();
    }

    public static String safe(Object value) {
        String text = Objects.toString(value, "");
        return (!text.trim().isEmpty() && "=+@-".indexOf(text.trim().charAt(0)) >= 0
                        || text.startsWith("\t")
                        || text.startsWith("\r"))
                ? "'" + text
                : text;
    }

    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(required = false)
            Boolean dnc,
            @RequestParam(required = false)
            String consent,
            @RequestParam(required = false)
            String keyword,
            HttpServletRequest request) {
        Identity i = actor(request);
        var filters = new LinkedHashMap<String, Object>();
        filters.put("tenantId", i.tenant());
        filters.put("keyword", keyword);
        filters.put("dnc", dnc);
        filters.put(
                "consent",
                consent == null || consent.isEmpty()
                        ? null
                        : store.encode("contact", "consent_state", consent));
        audit.record(i, "export", "contact", null, "contacts CSV");
        StreamingResponseBody stream =
                output -> {
                    Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
                    writer.write('\ufeff');
                    CSVPrinter csv = new CSVPrinter(writer, CSVFormat.DEFAULT);
                    String[] columns = {
                        "phone",
                        "name",
                        "tags",
                        "consent_state",
                        "dnc",
                        "dnc_reason",
                        "timezone",
                        "created_at",
                        "updated_at"
                    };
                    csv.printRecord((Object[]) columns);
                    Long last = null;
                    while (true) {
                        var params = new LinkedHashMap<String, Object>(filters);
                        params.put("last", last);
                        List<Map<String, Object>> rows =
                                store.query("contact", "exportContacts", params);
                        if (rows.isEmpty()) break;
                        for (Map<String, Object> row : rows) {
                            List<String> cells = new ArrayList<>();
                            for (String column : columns)
                                cells.add(
                                        safe(
                                                "consent_state".equals(column)
                                                        ? "ConsentState."
                                                                + row.get(column)
                                                                        .toString()
                                                                        .transform(Ascii::upper)
                                                        : row.get(column)));
                            csv.printRecord(cells);
                        }
                        last = ((Number) rows.get(rows.size() - 1).get("id")).longValue();
                        csv.flush();
                    }
                    csv.flush();
                };
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv;charset=UTF-8")
                .header(
                        "Content-Disposition",
                        "attachment; filename=contacts-" + i.tenant() + ".csv")
                .body(stream);
    }
}
