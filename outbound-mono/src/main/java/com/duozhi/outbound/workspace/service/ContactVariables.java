package com.duozhi.outbound.workspace.service;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

/** Published import contract and immutable recipient values shared by calls and messages. */
@Service
public class ContactVariables {
    private final WorkspaceDocuments docs;

    public ContactVariables(WorkspaceDocuments docs) {
        this.docs = docs;
    }

    public Map<String, Object> schema(long tenant, String script, String template) {
        if (!script.isBlank() && !template.isBlank()) throw new ApiError(422, "请选择一种模板来源");
        var fields = new LinkedHashMap<String, Map<String, Object>>();
        var result = new LinkedHashMap<String, Object>();
        String content = "";
        if (!script.isBlank()) {
            var doc = docs.require(tenant, "script", script);
            if (doc.get("published") == null || "disabled".equals(doc.get("state")))
                throw new ApiError(409, "请选择已发布且未停用的话术");
            var pub = map(doc.get("published"));
            content = Objects.toString(pub.get("compiled_content"), "");
            for (Object raw : (List<?>) map(pub.get("policy")).getOrDefault("slots", List.of())) {
                var slot = map(raw);
                var field = new LinkedHashMap<String, Object>();
                for (String k : List.of("key", "label", "kind", "choices"))
                    if (slot.containsKey(k)) field.put(k, slot.get(k));
                field.put("required", false);
                fields.put(Objects.toString(slot.get("key"), ""), field);
            }
            for (Object raw : (List<?>) pub.getOrDefault("import_fields", List.of())) {
                var f = map(raw);
                fields.put(text(f, "key", 64, true), f);
            }
            result.put("script_id", script);
            result.put("version", pub.get("version"));
        } else if (!template.isBlank()) {
            var doc =
                    docs.one(
                            "SELECT * FROM msg_resource WHERE tenant_id=? AND id=? AND kind='template'",
                            tenant,
                            template);
            if (doc == null) throw new ApiError(404, "短信模板不存在");
            if (!"APPROVED".equals(doc.get("state"))) throw new ApiError(409, "请选择已通过审核的短信模板");
            var payload = docs.json.object(doc.get("payload").toString());
            content =
                    com.duozhi.outbound.messaging.service.MessageContent.variableNames(payload)
                            .stream()
                            .map(v -> "{{" + v + "}}")
                            .collect(java.util.stream.Collectors.joining(" "));
            result.put("template_id", template);
            result.put("version", doc.get("revision"));
        }
        var matcher =
                Pattern.compile("\\{\\{?([A-Za-z][A-Za-z0-9_]{0,63})\\}?\\}").matcher(content);
        while (matcher.find()) {
            String key = matcher.group(1);
            var f =
                    fields.computeIfAbsent(
                            key,
                            k ->
                                    JsonSupport.map(
                                            "key",
                                            k,
                                            "label",
                                            k,
                                            "kind",
                                            "text",
                                            "required",
                                            true));
            f.put("required", true);
        }
        result.put(
                "builtins",
                fields.keySet().stream().filter(k -> Set.of("name", "phone").contains(k)).toList());
        fields.remove("name");
        fields.remove("phone");
        if (fields.size() > 32) throw new ApiError(422, "最多支持32个业务变量");
        result.put("fields", new ArrayList<>(fields.values()));
        result.put("hash", JsonSupport.sha(docs.json.canonical(result, false)));
        return result;
    }

    public static List<Map<String, Object>> normalize(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() > 32)
            throw new ApiError(422, "业务变量须为最多32项的列表");
        var result = new ArrayList<Map<String, Object>>();
        var keys = new HashSet<String>();
        for (Object v : list) {
            var f = map(v);
            String key = text(f, "key", 64, true), kind = Objects.toString(f.get("kind"), "text");
            if (!key.matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                    || !keys.add(key)
                    || Set.of("name", "phone").contains(key)) throw new ApiError(422, "变量标识不正确或重复");
            if (!Set.of("text", "integer", "digits", "date", "choice").contains(kind))
                throw new ApiError(422, "变量类型不支持");
            Object choices = f.getOrDefault("choices", List.of());
            if (!(choices instanceof List<?> c)
                    || c.size() > 50
                    || c.stream().anyMatch(x -> x == null || x.toString().length() > 100))
                throw new ApiError(422, "可选值须为最多50项的列表");
            if (kind.equals("choice") && ((List<?>) choices).isEmpty())
                throw new ApiError(422, "枚举变量需要可选值");
            result.add(
                    JsonSupport.map(
                            "key",
                            key,
                            "label",
                            text(f, "label", 80, true),
                            "kind",
                            kind,
                            "required",
                            Boolean.TRUE.equals(f.get("required")),
                            "choices",
                            choices));
        }
        return result;
    }

    public Map<String, Object> values(Map<String, Object> schema, Map<String, String> row) {
        var result = new LinkedHashMap<String, Object>();
        for (Object key : (List<?>) schema.getOrDefault("builtins", List.of()))
            if (row.getOrDefault(key.toString(), "").isBlank())
                throw new ApiError(422, "模板缺少基础变量：" + key);
        for (Object raw : (List<?>) schema.getOrDefault("fields", List.of())) {
            var f = map(raw);
            String key = f.get("key").toString(),
                    value = row.getOrDefault("变量:" + key, row.getOrDefault(key, ""));
            if (value.isBlank()) {
                if (Boolean.TRUE.equals(f.get("required")))
                    throw new ApiError(422, "缺少变量：" + f.get("label"));
                else continue;
            }
            if (value.length() > 512) throw new ApiError(422, "变量超过512字符：" + key);
            String kind = Objects.toString(f.get("kind"), "text");
            boolean valid = true;
            switch (kind) {
                case "integer" -> valid = value.matches("-?[0-9]{1,15}");
                case "digits" -> valid = value.matches("[0-9]{1,64}");
                case "date" -> {
                    try {
                        LocalDate.parse(value);
                    } catch (Exception e) {
                        valid = false;
                    }
                }
                case "choice" ->
                        valid = ((List<?>) f.getOrDefault("choices", List.of())).contains(value);
            }
            if (!valid) throw new ApiError(422, "变量类型不正确：" + f.get("label") + "（" + kind + "）");
            result.put(key, value);
        }
        return result;
    }

    public Map<String, Object> current(long tenant, long contact) {
        var row =
                docs.one(
                        "SELECT values_json FROM platform_contact_value WHERE tenant_id=? AND contact_id=?",
                        tenant,
                        contact);
        return row == null
                ? new LinkedHashMap<>()
                : docs.json.object(row.get("values_json").toString());
    }

    /** Resolve a contact set with bounded SQL batches, retaining the caller's order. */
    public LinkedHashMap<Long, Map<String, Object>> current(
            long tenant, Collection<Long> contacts) {
        var result = new LinkedHashMap<Long, Map<String, Object>>();
        contacts.forEach(id -> result.putIfAbsent(id, new LinkedHashMap<>()));
        for (var chunk : WorkspaceAudience.chunks(new ArrayList<>(result.keySet()), 500)) {
            var args = new ArrayList<Object>();
            args.add(tenant);
            args.addAll(chunk);
            for (var row :
                    docs.jdbc.queryForList(
                            "SELECT contact_id,values_json FROM platform_contact_value WHERE tenant_id=? AND contact_id IN ("
                                    + String.join(",", Collections.nCopies(chunk.size(), "?"))
                                    + ")",
                            args.toArray()))
                result.put(
                        ((Number) row.get("contact_id")).longValue(),
                        docs.json.object(row.get("values_json").toString()));
        }
        return result;
    }

    public Map<String, Object> frozen(long tenant, String snapshot, long contact) {
        var row =
                docs.one(
                        "SELECT variables_json FROM platform_audience_member WHERE tenant_id=? AND snapshot_id=? AND contact_id=?",
                        tenant,
                        snapshot,
                        contact);
        return row == null ? Map.of() : docs.json.object(row.get("variables_json").toString());
    }

    public static String render(String source, Map<String, Object> values) {
        var m = Pattern.compile("\\{\\{?([A-Za-z][A-Za-z0-9_]{0,63})\\}?\\}").matcher(source);
        var out = new StringBuffer();
        while (m.find())
            m.appendReplacement(
                    out,
                    Matcher.quoteReplacement(Objects.toString(values.get(m.group(1)), "（待确认）")));
        m.appendTail(out);
        return out.toString();
    }

    public byte[] workbook(Map<String, Object> schema) throws java.io.IOException {
        try (var book = new XSSFWorkbook();
                var out = new java.io.ByteArrayOutputStream()) {
            var sheet = book.createSheet("外呼名单");
            var row = sheet.createRow(0);
            var columns =
                    new ArrayList<>(List.of("手机号", "姓名", "标签", "联系授权状态", "禁止呼叫", "禁呼原因", "时区"));
            for (Object raw : (List<?>) schema.getOrDefault("fields", List.of()))
                columns.add("变量:" + map(raw).get("key"));
            var style = book.createCellStyle();
            style.setDataFormat(book.createDataFormat().getFormat("@"));
            for (int n = 0; n < columns.size(); n++) {
                row.createCell(n).setCellValue(columns.get(n));
                sheet.setColumnWidth(n, 6000);
                sheet.setDefaultColumnStyle(n, style);
            }
            sheet.createFreezePane(0, 1);
            var help = book.createSheet("填写说明");
            int n = 0;
            for (String s :
                    List.of(
                            "手机号按文本填写；不要填写公式。仅填写已获得联系授权的号码。",
                            "联系授权状态：已同意、未同意、已撤回、未知；禁止呼叫：是/否。",
                            "默认保留已有客户资料，本批业务变量独立保存；勾选更新才修改客户资料。",
                            "变量日期格式 YYYY-MM-DD；空白的非必填变量不覆盖已有资料。",
                            "模板契约：" + docs.json.json(schema)))
                help.createRow(n++).createCell(0).setCellValue(s);
            for (Object raw : (List<?>) schema.getOrDefault("fields", List.of())) {
                var f = map(raw);
                help.createRow(n++)
                        .createCell(0)
                        .setCellValue(
                                "变量:"
                                        + f.get("key")
                                        + " | "
                                        + f.get("label")
                                        + " | "
                                        + f.get("kind")
                                        + " | "
                                        + (Boolean.TRUE.equals(f.get("required")) ? "必填" : "选填")
                                        + " | "
                                        + f.getOrDefault("choices", List.of()));
            }
            help.setColumnWidth(0, 28000);
            book.write(out);
            return out.toByteArray();
        }
    }
}
