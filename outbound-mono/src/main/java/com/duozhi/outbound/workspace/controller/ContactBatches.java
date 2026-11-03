package com.duozhi.outbound.workspace.controller;

import com.duozhi.outbound.auth.domain.*;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.customer.controller.ContactFiles;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/workspace/contact-batches")
@AdminPermission("contacts:write")
public class ContactBatches {
    private final WorkspaceDocuments docs;
    private final ContactFiles files;

    public ContactBatches(WorkspaceDocuments docs, ContactFiles files) {
        this.docs = docs;
        this.files = files;
    }

    private Identity actor(HttpServletRequest r) {
        var i = Identity.from(r);
        i.admin("contacts:write");
        return i;
    }

    @GetMapping
    public Object list(HttpServletRequest r) {
        var i = actor(r);
        return docs
                .jdbc
                .queryForList(
                        "SELECT id,name,created_at,result_json FROM platform_contact_batch WHERE tenant_id=? AND request_key NOT LIKE 'workspace-chunk:%' ORDER BY created_at DESC LIMIT 200",
                        i.tenant())
                .stream()
                .map(
                        row -> {
                            row.put(
                                    "result",
                                    docs.json.object(row.remove("result_json").toString()));
                            return row;
                        })
                .toList();
    }

    @GetMapping("/uploads")
    public Object uploads(HttpServletRequest r) {
        return docs
                .jdbc
                .queryForList(
                        "SELECT id,filename,state,error,result_json,(file_bytes IS NOT NULL) AS retryable,created_at,updated_at FROM platform_contact_upload WHERE tenant_id=? ORDER BY created_at DESC LIMIT 50",
                        actor(r).tenant())
                .stream()
                .map(
                        row -> {
                            Object value = row.remove("result_json");
                            row.put(
                                    "result",
                                    value == null ? Map.of() : docs.json.object(value.toString()));
                            return row;
                        })
                .toList();
    }

    @GetMapping("/uploads/{id}")
    public Object upload(@PathVariable
    String id, HttpServletRequest r) {
        var row =
                docs.one(
                        "SELECT id,filename,state,error,result_json,(file_bytes IS NOT NULL) AS retryable,created_at,updated_at FROM platform_contact_upload WHERE tenant_id=? AND id=?",
                        actor(r).tenant(),
                        id);
        if (row == null) throw new ApiError(404, "导入任务不存在");
        if (row.get("result_json") != null)
            row.put("result", docs.json.object(row.remove("result_json").toString()));
        return row;
    }

    @GetMapping("/{id}/errors")
    public ResponseEntity<String> errors(@PathVariable
    String id, HttpServletRequest r) {
        var row =
                docs.one(
                        "SELECT result_json FROM platform_contact_batch WHERE tenant_id=? AND id=?",
                        actor(r).tenant(),
                        id);
        if (row == null) throw new ApiError(404, "名单批次不存在");
        var result = docs.json.object(row.get("result_json").toString());
        StringBuilder csv = new StringBuilder("\ufeff行号与原因\r\n");
        for (Object e :
                docs.jdbc.queryForList(
                        "SELECT message FROM platform_contact_batch_error WHERE tenant_id=? AND batch_id=? ORDER BY row_seq",
                        String.class,
                        actor(r).tenant(),
                        id))
            csv.append('"').append(ContactFiles.safe(e).replace("\"", "\"\"")).append("\"\r\n");
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv;charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=import-errors.csv")
                .body(csv.toString());
    }

    @PostMapping("/preview")
    public Object preview(
            @RequestParam
            MultipartFile file,
            @RequestParam(defaultValue = "false")
            boolean upsert,
            @RequestParam(defaultValue = "{}")
            String mapping,
            HttpServletRequest r)
            throws IOException {
        return files.process(file, upsert, null, actor(r), true, mapping(mapping));
    }

    private Map<String, String> mapping(String text) {
        var raw = docs.json.object(text);
        if (raw.size() > 32) throw new ApiError(422, "最多映射32列");
        var result = new TreeMap<String, String>();
        for (var e : raw.entrySet()) {
            String field = Objects.toString(e.getValue(), "");
            if (!Set.of("phone", "name", "tags", "consent_state", "dnc", "dnc_reason", "timezone")
                    .contains(field)) throw new ApiError(422, "不支持的映射列");
            result.put(e.getKey(), field);
        }
        return result;
    }

    @PostMapping
    public Object submit(
            @RequestParam
            MultipartFile file,
            @RequestParam(defaultValue = "false")
            boolean upsert,
            @RequestParam(defaultValue = "{}")
            String mapping,
            @RequestHeader("Idempotency-Key")
            String key,
            HttpServletRequest r)
            throws IOException {
        var i = actor(r);
        if (!key.matches("[A-Za-z0-9._:-]{1,128}")) throw new ApiError(422, "请求标识不正确");
        if (file.isEmpty() || file.getSize() > 20 * 1024 * 1024)
            throw new ApiError(413, "上传文件须为1字节至20MB");
        String name = Objects.toString(file.getOriginalFilename(), "");
        if (name.length() > 200
                || !(name.toLowerCase(Locale.ROOT).endsWith(".csv")
                        || name.toLowerCase(Locale.ROOT).endsWith(".xlsx")))
            throw new ApiError(422, "请上传xlsx或UTF-8 CSV");
        byte[] bytes = file.getBytes();
        var fields = mapping(mapping);
        String encoded = docs.json.json(fields), digest;
        try {
            digest =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    (Base64.getEncoder().encodeToString(bytes)
                                                                    + ":"
                                                                    + upsert
                                                                    + ":"
                                                                    + fields)
                                                            .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        String sha = digest;
        return docs.tx.serialized(
                () -> {
                    var old =
                            docs.one(
                                    "SELECT id,file_sha FROM platform_contact_upload WHERE tenant_id=? AND request_key=?",
                                    i.tenant(),
                                    key);
                    if (old != null) {
                        if (!sha.equals(old.get("file_sha")))
                            throw new ApiError(409, "请求标识已用于其他文件或导入选项");
                        return upload(old.get("id").toString(), r);
                    }
                    int pending =
                            docs.jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM platform_contact_upload WHERE tenant_id=? AND file_bytes IS NOT NULL",
                                    Integer.class,
                                    i.tenant());
                    if (pending >= 3) throw new ApiError(429, "已有3个保留文件的导入任务，请等待完成或移除失败任务");
                    String id = UUID.randomUUID().toString();
                    docs.jdbc.update(
                            "INSERT INTO platform_contact_upload(tenant_id,id,request_key,filename,file_sha,file_bytes,upsert_rows,mapping_json,actor_json,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                            i.tenant(),
                            id,
                            key,
                            name,
                            sha,
                            bytes,
                            upsert,
                            encoded,
                            docs.json.json(
                                    JsonSupport.map(
                                            "id",
                                            i.userId(),
                                            "username",
                                            i.actor(),
                                            "role",
                                            "admin")),
                            Store.now(),
                            Store.now());
                    return upload(id, r);
                });
    }

    @PostMapping("/uploads/{id}/retry")
    public Object retry(@PathVariable
    String id, HttpServletRequest r) {
        var i = actor(r);
        return docs.tx.run(
                () -> {
                    int changed =
                            docs.jdbc.update(
                                    "UPDATE platform_contact_upload SET state='queued',error=NULL,updated_at=? WHERE tenant_id=? AND id=? AND state='failed' AND file_bytes IS NOT NULL",
                                    Store.now(),
                                    i.tenant(),
                                    id);
                    if (changed == 0) throw new ApiError(409, "仅保留原文件的失败任务可重试");
                    return upload(id, r);
                });
    }

    @DeleteMapping("/uploads/{id}")
    public Object discard(@PathVariable
    String id, HttpServletRequest r) {
        var i = actor(r);
        int changed =
                docs.jdbc.update(
                        "UPDATE platform_contact_upload SET state='cancelled',file_bytes=NULL,error='任务已取消，已导入的联系人保留',updated_at=? WHERE tenant_id=? AND id=? AND state IN ('queued','failed')",
                        Store.now(),
                        i.tenant(),
                        id);
        if (changed == 0) throw new ApiError(409, "处理中的导入请等待完成");
        return upload(id, r);
    }
}
