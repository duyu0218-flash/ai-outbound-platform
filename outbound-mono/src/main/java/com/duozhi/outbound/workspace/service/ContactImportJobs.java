package com.duozhi.outbound.workspace.service;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.customer.controller.*;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.apache.commons.csv.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** One durable upload worker; contact writes yield the platform lock between 100-row chunks. */
@Service
public class ContactImportJobs {
    private static final int CHUNK = 100;
    private final WorkspaceDocuments docs;
    private final ContactFiles files;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(
                    r -> {
                        var t = new Thread(r, "contact-import");
                        t.setDaemon(true);
                        return t;
                    });
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile boolean ready, closing;

    public ContactImportJobs(WorkspaceDocuments docs, ContactFiles files) {
        this.docs = docs;
        this.files = files;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        docs.jdbc.update(
                "UPDATE platform_contact_upload SET state='queued' WHERE state='processing'");
        ready = true;
    }

    @PreDestroy
    public void close() {
        closing = true;
        executor.shutdownNow();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Scheduled(fixedDelay = 1500)
    public void processNext() {
        if (!ready || closing || !busy.compareAndSet(false, true)) return;
        try {
            var row =
                    docs.tx.run(
                            () -> {
                                docs.jdbc.update(
                                        "UPDATE platform_contact_upload SET state='queued' WHERE state='processing' AND updated_at<UTC_TIMESTAMP(3)-INTERVAL 1 MINUTE");
                                var next =
                                        docs.one(
                                                "SELECT * FROM platform_contact_upload WHERE state='queued' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED");
                                if (next != null)
                                    docs.jdbc.update(
                                            "UPDATE platform_contact_upload SET state='processing',updated_at=? WHERE tenant_id=? AND id=? AND state='queued'",
                                            Store.now(),
                                            next.get("tenant_id"),
                                            next.get("id"));
                                return next;
                            });
            if (row == null) {
                busy.set(false);
                return;
            }
            executor.execute(
                    () -> {
                        try {
                            execute(row);
                        } finally {
                            busy.set(false);
                        }
                    });
        } catch (RuntimeException e) {
            busy.set(false);
            throw e;
        }
    }

    public void execute(Map<String, Object> row) {
        long tenant = ((Number) row.get("tenant_id")).longValue();
        String id = row.get("id").toString();
        try {
            var result = importChunks(row);
            docs.jdbc.update(
                    "UPDATE platform_contact_upload SET state='completed',result_json=?,file_bytes=NULL,error=NULL,updated_at=? WHERE tenant_id=? AND id=?",
                    docs.json.json(result),
                    Store.now(),
                    tenant,
                    id);
        } catch (Exception e) {
            if (closing || Thread.currentThread().isInterrupted())
                return; // retain payload and processing state for restart
            String message = e instanceof ApiError ? e.getMessage() : "导入中断，已提交批次保留；可点击重试继续处理";
            docs.jdbc.update(
                    "UPDATE platform_contact_upload SET state='failed',error=?,updated_at=? WHERE tenant_id=? AND id=?",
                    message,
                    Store.now(),
                    tenant,
                    id);
        }
    }

    record SourceRow(long number, List<String> fields) {}

    private Map<String, Object> importChunks(Map<String, Object> job) throws Exception {
        long tenant = ((Number) job.get("tenant_id")).longValue();
        String id = job.get("id").toString(), key = "workspace:" + id;
        var completed =
                docs.one(
                        "SELECT result_json FROM platform_contact_batch WHERE tenant_id=? AND request_key=?",
                        tenant,
                        key);
        if (completed != null) return docs.json.object(completed.get("result_json").toString());
        String content =
                files.readContent(
                        new Upload(job.get("filename").toString(), (byte[]) job.get("file_bytes")));
        var mapping = docs.json.object(job.get("mapping_json").toString());
        var rows = new ArrayList<SourceRow>();
        var seen = new HashSet<String>();
        var errors = new ArrayList<String>();
        List<String> columns =
                List.of("phone", "name", "tags", "consent_state", "dnc", "dnc_reason", "timezone");
        boolean first = true;
        int total = 0, duplicates = 0;
        try (var parser = CSVFormat.DEFAULT.parse(new StringReader(content))) {
            for (var record : parser) {
                var fields = new ArrayList<String>();
                for (String value : record) fields.add(value.trim());
                if (fields.stream().allMatch(String::isEmpty)) continue;
                if (fields.size() > 32) throw new ApiError(422, "最多支持32列，请检查文件");
                if (first) {
                    first = false;
                    var header =
                            fields.stream()
                                    .map(
                                            v ->
                                                    Objects.toString(
                                                            mapping.getOrDefault(
                                                                    v,
                                                                    ContactWorkbook.column(
                                                                            v.toLowerCase(
                                                                                    Locale.ROOT)))))
                                    .toList();
                    if (header.contains("phone")) {
                        if (new HashSet<>(header).size() != header.size())
                            throw new ApiError(422, "文件存在重复列名");
                        columns = header;
                        continue;
                    }
                }
                total++;
                if (total > 200000) throw new ApiError(413, "名单超过200000行");
                int index = columns.indexOf("phone");
                String raw = index < fields.size() ? fields.get(index) : "";
                String phone = "";
                if (raw.matches("[+0-9()\\s-]+"))
                    try {
                        phone = Contacts.phone(raw);
                    } catch (ApiError ignored) {
                    }
                if (!phone.isEmpty() && !seen.add(phone)) {
                    duplicates++;
                    errors.add(
                            "row "
                                    + record.getRecordNumber()
                                    + ": duplicate phone in uploaded file");
                    continue;
                }
                rows.add(new SourceRow(record.getRecordNumber(), fields));
            }
        }
        if (total == 0) throw new ApiError(422, "文件中没有号码");
        var actor =
                new Identity(tenant, docs.json.object(job.get("actor_json").toString()), Set::of);
        int created = 0, updated = 0, skipped = duplicates, failed = 0, processed = duplicates;
        var previews = new ArrayList<Map<String, Object>>();
        var children = new ArrayList<String>();
        for (int start = 0, part = 0; start < rows.size(); start += CHUNK, part++) {
            if (closing || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            var chunk = rows.subList(start, Math.min(start + CHUNK, rows.size()));
            var text = new StringWriter();
            try (var csv = new CSVPrinter(text, CSVFormat.DEFAULT)) {
                csv.printRecord(columns);
                for (var source : chunk) csv.printRecord(source.fields());
            }
            var result =
                    files.process(
                            new Upload(
                                    "chunk.csv", text.toString().getBytes(StandardCharsets.UTF_8)),
                            Boolean.TRUE.equals(job.get("upsert_rows")),
                            "workspace-chunk:" + id + ":" + part,
                            actor,
                            false,
                            Map.of());
            String child = result.get("batch_id").toString();
            children.add(child);
            created += WorkspaceDocuments.number(result.get("created"));
            updated += WorkspaceDocuments.number(result.get("updated"));
            skipped += WorkspaceDocuments.number(result.get("skipped"));
            failed += WorkspaceDocuments.number(result.get("failed"));
            processed += chunk.size();
            for (String error :
                    docs.jdbc.queryForList(
                            "SELECT message FROM platform_contact_batch_error WHERE tenant_id=? AND batch_id=? ORDER BY row_seq",
                            String.class,
                            tenant,
                            child)) errors.add(globalRow(error, chunk));
            for (Object rawPreview : (List<?>) result.get("preview_rows")) {
                if (previews.size() >= 50) break;
                var preview = WorkspaceDocuments.map(rawPreview);
                int n = WorkspaceDocuments.number(preview.get("row")) - 2;
                if (n >= 0 && n < chunk.size()) preview.put("row", chunk.get(n).number());
                previews.add(preview);
            }
            docs.jdbc.update(
                    "UPDATE platform_contact_upload SET result_json=?,updated_at=? WHERE tenant_id=? AND id=?",
                    docs.json.json(
                            JsonSupport.map(
                                    "processed_rows",
                                    processed,
                                    "total",
                                    total,
                                    "created",
                                    created,
                                    "updated",
                                    updated,
                                    "skipped",
                                    skipped,
                                    "failed",
                                    failed)),
                    Store.now(),
                    tenant,
                    id);
            Thread.sleep(10); // allow queued call writers to acquire the non-fair platform mutex
        }
        String batch = UUID.randomUUID().toString();
        var result =
                JsonSupport.<String, Object>map(
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
                        errors.subList(0, Math.min(1000, errors.size())),
                        "error_count",
                        errors.size(),
                        "batch_id",
                        batch,
                        "preview_rows",
                        previews);
        return docs.tx.run(
                () -> {
                    docs.jdbc.update(
                            "INSERT INTO platform_contact_batch(tenant_id,id,request_key,name,file_sha,result_json,created_by,created_at) VALUES(?,?,?,?,?,?,?,?)",
                            tenant,
                            batch,
                            key,
                            job.get("filename"),
                            job.get("file_sha"),
                            "{}",
                            actor.userId(),
                            Store.now());
                    for (String child : children)
                        docs.jdbc.update(
                                "INSERT IGNORE INTO platform_contact_batch_member(tenant_id,batch_id,contact_id) SELECT tenant_id,?,contact_id FROM platform_contact_batch_member WHERE tenant_id=? AND batch_id=?",
                                batch,
                                tenant,
                                child);
                    result.put(
                            "selected_count",
                            docs.jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM platform_contact_batch_member WHERE tenant_id=? AND batch_id=?",
                                    Long.class,
                                    tenant,
                                    batch));
                    var errorRows = new ArrayList<Map<String, Object>>();
                    for (int n = 0; n < errors.size(); n++)
                        errorRows.add(JsonSupport.map("seq", n + 1, "message", errors.get(n)));
                    docs.jdbc.batchUpdate(
                            "INSERT INTO platform_contact_batch_error(tenant_id,batch_id,row_seq,message) VALUES(?,?,?,?)",
                            errorRows,
                            500,
                            (ps, e) -> {
                                ps.setLong(1, tenant);
                                ps.setString(2, batch);
                                ps.setInt(3, ((Number) e.get("seq")).intValue());
                                ps.setString(4, e.get("message").toString());
                            });
                    docs.jdbc.update(
                            "UPDATE platform_contact_batch SET result_json=? WHERE tenant_id=? AND id=?",
                            docs.json.json(result),
                            tenant,
                            batch);
                    return result;
                });
    }

    private String globalRow(String error, List<SourceRow> rows) {
        var matcher = Pattern.compile("^row (\\d+):").matcher(error);
        if (!matcher.find()) return error;
        int index = Integer.parseInt(matcher.group(1)) - 2;
        return index < 0 || index >= rows.size()
                ? error
                : "row " + rows.get(index).number() + error.substring(matcher.end() - 1);
    }

    public record Upload(String filename, byte[] bytes) implements MultipartFile {
        public String getName() {
            return "file";
        }

        public String getOriginalFilename() {
            return filename;
        }

        public String getContentType() {
            return "application/octet-stream";
        }

        public boolean isEmpty() {
            return bytes.length == 0;
        }

        public long getSize() {
            return bytes.length;
        }

        public byte[] getBytes() {
            return bytes;
        }

        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        public void transferTo(File file) throws IOException {
            java.nio.file.Files.write(file.toPath(), bytes);
        }
    }
}
