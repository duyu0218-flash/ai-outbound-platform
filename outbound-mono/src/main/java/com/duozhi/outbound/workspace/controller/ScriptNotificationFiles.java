package com.duozhi.outbound.workspace.controller;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.customer.controller.ContactFiles;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/workspace/scripts/{script}/notifications")
public class ScriptNotificationFiles {
    private final WorkspaceDocuments docs;
    private final ContactFiles files;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.auth.service.AccountSecurity security;

    public ScriptNotificationFiles(WorkspaceDocuments docs, ContactFiles files) {
        this.docs = docs;
        this.files = files;
    }

    private Identity actor(String script, HttpServletRequest r) {
        var i = Identity.from(r);
        i.requireUser();
        i.admin("messaging:write");
        docs.require(i.tenant(), "script", script);
        return i;
    }

    private static final List<String> HEADERS =
            List.of("标题", "短信通道ID", "短信签名ID", "模板ID", "联系人ID", "所属账号ID", "自动发送", "参数");

    @PostMapping("/import")
    public Object parse(
            @PathVariable
            String script, @RequestParam
            MultipartFile file, HttpServletRequest r)
            throws java.io.IOException {
        actor(script, r);
        if (file.isEmpty() || file.getSize() > 2 * 1024 * 1024)
            throw new ApiError(422, "短信文件须小于2MB");
        var result = new ArrayList<Map<String, Object>>();
        try (var csv =
                org.apache.commons.csv.CSVFormat.DEFAULT
                        .builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .get()
                        .parse(new java.io.StringReader(files.readContent(file)))) {
            if (!csv.getHeaderNames().containsAll(HEADERS)) throw new ApiError(422, "请使用通知短信模板");
            for (var row : csv) {
                if (result.size() >= 100) throw new ApiError(422, "最多导入100条通知短信");
                try {
                    result.add(
                            Map.of(
                                    "title",
                                    row.get("标题"),
                                    "product_id",
                                    row.get("短信通道ID"),
                                    "sender_id",
                                    row.get("短信签名ID"),
                                    "template_id",
                                    row.get("模板ID"),
                                    "contact_id",
                                    Integer.parseInt(row.get("联系人ID")),
                                    "owner_id",
                                    row.get("所属账号ID").isBlank()
                                            ? 0
                                            : Integer.parseInt(row.get("所属账号ID")),
                                    "auto_send",
                                    Set.of("true", "1", "是").contains(row.get("自动发送")),
                                    "variables",
                                    docs.json.object(row.get("参数"))));
                } catch (RuntimeException invalid) {
                    throw new ApiError(422, "通知短信第" + (row.getRecordNumber() + 1) + "行格式错误");
                }
            }
        }
        return Map.of("rows", result);
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@PathVariable
    String script, HttpServletRequest r)
            throws java.io.IOException {
        actor(script, r);
        return workbook(
                List.of(
                        Map.of(
                                "title",
                                "通知示例",
                                "template_id",
                                "已审核模板ID",
                                "contact_id",
                                1,
                                "owner_id",
                                0,
                                "auto_send",
                                false,
                                "variables",
                                Map.of())));
    }

    @PostMapping("/export")
    public ResponseEntity<byte[]> export(
            @PathVariable
            String script, @RequestBody
            Map<String, Object> b, HttpServletRequest r)
            throws java.io.IOException {
        var i = actor(script, r);
        security.export(i, Objects.toString(r.getHeader("X-Export-Verification"), ""));
        if (!(b.get("rows") instanceof List<?> rows) || rows.size() > 100)
            throw new ApiError(422, "通知数据无效");
        return workbook(rows);
    }

    private ResponseEntity<byte[]> workbook(List<?> values) throws java.io.IOException {
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
                var bytes = new java.io.ByteArrayOutputStream()) {
            var sheet = book.createSheet("通知短信");
            var header = sheet.createRow(0);
            for (int n = 0; n < HEADERS.size(); n++)
                header.createCell(n).setCellValue(HEADERS.get(n));
            int index = 1;
            for (var value : values) {
                var v = WorkspaceDocuments.map(value);
                var row = sheet.createRow(index++);
                String[] columns = {
                    Objects.toString(v.get("title"), ""),
                    Objects.toString(v.get("product_id"), ""),
                    Objects.toString(v.get("sender_id"), ""),
                    Objects.toString(v.get("template_id"), ""),
                    Objects.toString(v.get("contact_id"), ""),
                    Objects.toString(v.get("owner_id"), ""),
                    Objects.toString(v.get("auto_send"), "false"),
                    docs.json.json(v.getOrDefault("variables", Map.of()))
                };
                for (int n = 0; n < columns.length; n++) row.createCell(n).setCellValue(columns[n]);
            }
            book.write(bytes);
            return ResponseEntity.ok()
                    .header(
                            "Content-Type",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .header("Content-Disposition", "attachment; filename=script-notifications.xlsx")
                    .body(bytes.toByteArray());
        }
    }
}
