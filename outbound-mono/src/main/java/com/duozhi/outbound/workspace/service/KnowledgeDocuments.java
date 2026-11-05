package com.duozhi.outbound.workspace.service;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.runtime.ScheduledWork;
import com.duozhi.outbound.common.support.JsonSupport;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Durable, bounded local document parsing followed by an explicit, atomic draft confirmation. */
@Service
public class KnowledgeDocuments {
    @org.springframework.beans.factory.annotation.Autowired
    private ScheduledWork scheduledWork;

    private static final int MAX_BYTES = 2 * 1024 * 1024, MAX_CHARS = 100000;
    private final WorkspaceDocuments docs;
    private final KnowledgeDrafts entries;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(
                    r -> {
                        var t = new Thread(r, "knowledge-document");
                        t.setDaemon(true);
                        return t;
                    });
    private volatile boolean ready, closing;

    public KnowledgeDocuments(WorkspaceDocuments docs, KnowledgeDrafts entries) {
        this.docs = docs;
        this.entries = entries;
    }

    public Object upload(
            Identity actor,
            String library,
            MultipartFile file,
            String requestKey,
            int splitSize,
            int overlap,
            String category)
            throws IOException {
        if (file.isEmpty() || file.getSize() > MAX_BYTES) throw new ApiError(413, "文档须为1字节至2MB");
        if (!requestKey.matches("[A-Za-z0-9-]{1,64}")) throw new ApiError(422, "文档请求标识不正确");
        if (splitSize < 200
                || splitSize > 2000
                || overlap < 0
                || overlap > Math.min(200, splitSize / 3))
            throw new ApiError(422, "分段长度须为200至2000，重叠不超过段长三分之一且最多200");
        if (category.length() > 160) throw new ApiError(422, "文档分类路径过长");
        String filename = Objects.toString(file.getOriginalFilename(), "文档").replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        if (filename.isBlank()
                || filename.length() > 240
                || !filename.toLowerCase(Locale.ROOT).matches(".*\\.(txt|md|docx|pdf)$"))
            throw new ApiError(422, "支持TXT、Markdown、DOCX和可提取文字的PDF，扫描件请先转换文字");
        byte[] bytes = file.getBytes();
        String sha = KnowledgeGaps.sha(bytes);
        String
                options =
                        docs.json.canonical(
                                JsonSupport.map(
                                        "split_size",
                                        splitSize,
                                        "overlap",
                                        overlap,
                                        "category",
                                        category),
                                false),
                name = filename;
        return docs.tx.serialized(
                () -> {
                    docs.require(actor.tenant(), "library", library);
                    var prior =
                            docs.one(
                                    "SELECT * FROM platform_knowledge_upload WHERE tenant_id=? AND id=?",
                                    actor.tenant(),
                                    requestKey);
                    if (prior != null) {
                        if (!library.equals(prior.get("library_id"))
                                || !sha.equals(prior.get("file_sha"))
                                || !options.equals(prior.get("settings_json"))
                                || !name.equals(prior.get("filename")))
                            throw new ApiError(409, "同一请求不能替换文件、目录或分段设置");
                        return get(actor.tenant(), library, requestKey);
                    }
                    int count =
                            docs.jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM platform_knowledge_upload WHERE tenant_id=? AND state IN ('queued','parsing','ready','failed')",
                                    Integer.class,
                                    actor.tenant());
                    if (count >= 10) throw new ApiError(429, "最多保留10个未确认文档，请先确认或移除");
                    docs.jdbc.update(
                            "INSERT INTO platform_knowledge_upload(tenant_id,id,library_id,filename,file_sha,file_bytes,settings_json,created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                            actor.tenant(),
                            requestKey,
                            library,
                            name,
                            sha,
                            bytes,
                            options,
                            actor.userId(),
                            Store.now(),
                            Store.now());
                    docs.recordTransition(actor, "knowledge_import", requestKey, "queued");
                    return get(actor.tenant(), library, requestKey);
                });
    }

    public List<Map<String, Object>> list(long tenant, String library) {
        docs.require(tenant, "library", library);
        return docs.jdbc.queryForList(
                "SELECT id,filename,state,revision,progress,error,created_at,updated_at,created_by FROM platform_knowledge_upload WHERE tenant_id=? AND library_id=? ORDER BY created_at DESC LIMIT 100",
                tenant,
                library);
    }

    private Map<String, Object> require(long tenant, String library, String id) {
        docs.require(tenant, "library", library);
        var row =
                docs.one(
                        "SELECT * FROM platform_knowledge_upload WHERE tenant_id=? AND library_id=? AND id=?",
                        tenant,
                        library,
                        id);
        if (row == null) throw new ApiError(404, "文档作业不存在");
        return row;
    }

    public Map<String, Object> get(long tenant, String library, String id) {
        var result = new LinkedHashMap<>(require(tenant, library, id));
        result.remove("file_bytes");
        result.put("settings", docs.json.object(result.remove("settings_json").toString()));
        Object raw = result.remove("segments_json");
        var parsed = raw == null ? Map.<String, Object>of() : docs.json.object(raw.toString());
        result.put("segments", parsed.getOrDefault("segments", List.of()));
        result.put("original_segments", parsed.getOrDefault("original_segments", List.of()));
        return result;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        docs.jdbc.update(
                "UPDATE platform_knowledge_upload SET state='queued',progress=0 WHERE state='parsing'");
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

    @Scheduled(fixedDelay = 1200)
    public void scheduledNext() {
        scheduledWork.submit(ScheduledWork.Job.KNOWLEDGE_IMPORT, this::next);
    }

    public void next() {
        if (!ready || closing || !busy.compareAndSet(false, true)) return;
        try {
            var row =
                    docs.tx.run(
                            () -> {
                                docs.jdbc.update(
                                        "UPDATE platform_knowledge_upload SET state='queued',progress=0 WHERE state='parsing' AND updated_at<UTC_TIMESTAMP(3)-INTERVAL 1 MINUTE");
                                var found =
                                        docs.one(
                                                "SELECT * FROM platform_knowledge_upload WHERE state='queued' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED");
                                if (found != null)
                                    docs.jdbc.update(
                                            "UPDATE platform_knowledge_upload SET state='parsing',progress=10,updated_at=? WHERE tenant_id=? AND id=?",
                                            Store.now(),
                                            found.get("tenant_id"),
                                            found.get("id"));
                                return found;
                            });
            if (row == null) {
                busy.set(false);
                return;
            }
            executor.execute(
                    () -> {
                        try {
                            parse(row);
                        } finally {
                            busy.set(false);
                        }
                    });
        } catch (RuntimeException e) {
            busy.set(false);
            throw e;
        }
    }

    public void parse(Map<String, Object> row) {
        Object tenant = row.get("tenant_id"), id = row.get("id");
        try {
            String text = extract(row.get("filename").toString(), (byte[]) row.get("file_bytes"));
            if (closing || Thread.currentThread().isInterrupted()) return;
            docs.jdbc.update(
                    "UPDATE platform_knowledge_upload SET progress=70,updated_at=? WHERE tenant_id=? AND id=? AND state='parsing'",
                    Store.now(),
                    tenant,
                    id);
            var options = docs.json.object(row.get("settings_json").toString());
            var segments =
                    split(text, number(options.get("split_size")), number(options.get("overlap")));
            var result = new ArrayList<Map<String, Object>>();
            String file = row.get("filename").toString();
            for (int n = 0; n < segments.size(); n++)
                result.add(
                        JsonSupport.map(
                                "name",
                                file.substring(0, Math.min(135, file.length())) + " · " + (n + 1),
                                "content",
                                segments.get(n),
                                "category",
                                options.get("category"),
                                "source",
                                "文档：" + file + " · 分段" + (n + 1),
                                "kind",
                                "document",
                                "document_id",
                                id,
                                "segment_index",
                                n + 1,
                                "enabled",
                                true));
            docs.jdbc.update(
                    "UPDATE platform_knowledge_upload SET state='ready',progress=100,revision=revision+1,segments_json=?,file_bytes=NULL,error=NULL,updated_at=? WHERE tenant_id=? AND id=? AND state='parsing'",
                    docs.json.json(
                            JsonSupport.map("segments", result, "original_segments", result)),
                    Store.now(),
                    tenant,
                    id);
        } catch (Exception e) {
            if (closing || Thread.currentThread().isInterrupted()) return;
            String message =
                    e instanceof ApiError ? e.getMessage() : "无法解析该文件，请检查文件是否损坏、加密或包含过大的压缩内容";
            docs.jdbc.update(
                    "UPDATE platform_knowledge_upload SET state='failed',revision=revision+1,error=?,updated_at=? WHERE tenant_id=? AND id=? AND state='parsing'",
                    message,
                    Store.now(),
                    tenant,
                    id);
        }
    }

    public static String extract(String filename, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES)
            throw new ApiError(413, "文档大小不正确");
        String lower = filename.toLowerCase(Locale.ROOT), text;
        if (lower.endsWith(".txt") || lower.endsWith(".md")) {
            try {
                text =
                        StandardCharsets.UTF_8
                                .newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(bytes))
                                .toString();
            } catch (CharacterCodingException e) {
                throw new ApiError(422, "文本文件须为UTF-8编码");
            }
        } else if (lower.endsWith(".docx")) {
            int total = 0, files = 0;
            byte[] buffer = new byte[8192];
            try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                while (zip.getNextEntry() != null) {
                    if (++files > 300) throw new ApiError(413, "DOCX内部文件过多");
                    int n;
                    while ((n = zip.read(buffer)) != -1) {
                        total += n;
                        if (total > 10 * 1024 * 1024)
                            throw new ApiError(413, "DOCX解压内容超过10MB，请拆分文件");
                    }
                }
            }
            try (var document = new XWPFDocument(new ByteArrayInputStream(bytes));
                    var reader = new XWPFWordExtractor(document)) {
                text = reader.getText();
            }
        } else if (lower.endsWith(".pdf")) {
            try (var document =
                    Loader.loadPDF(
                            bytes, "", null, null, IOUtils.createTempFileOnlyStreamCache())) {
                if (document.getNumberOfPages() > 200) throw new ApiError(413, "PDF最多200页，请拆分文件");
                if (!document.getCurrentAccessPermission().canExtractContent())
                    throw new ApiError(422, "PDF不允许提取内容");
                var writer = new LimitedWriter();
                new PDFTextStripper().writeText(document, writer);
                text = writer.toString();
            }
        } else throw new ApiError(422, "不支持的文档类型");
        text = text.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n').strip();
        if (text.isBlank()) throw new ApiError(422, "没有可提取文字；扫描PDF和图片须先转换为文本，当前不执行OCR");
        if (text.length() > MAX_CHARS) throw new ApiError(413, "提取文字超过100000字符，请拆分文件");
        return text;
    }

    private static final class LimitedWriter extends Writer {
        private final StringBuilder text = new StringBuilder();

        @Override
        public void write(char[] c, int offset, int length) throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            if (text.length() + length > MAX_CHARS) throw new ApiError(413, "提取文字超过100000字符，请拆分文件");
            text.append(c, offset, length);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        @Override
        public String toString() {
            return text.toString();
        }
    }

    public static List<String> split(String text, int size, int overlap) {
        if (size < 200 || size > 2000 || overlap < 0 || overlap > Math.min(200, size / 3))
            throw new ApiError(422, "分段设置不正确");
        var result = new ArrayList<String>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + size);
            if (end < text.length()) {
                int boundary = text.lastIndexOf('\n', end);
                if (boundary > start + size / 2) end = boundary + 1;
                if (end > start && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            }
            String part = text.substring(start, end).strip();
            if (!part.isBlank()) result.add(part);
            if (result.size() > 500) throw new ApiError(422, "分段超过500条，请增加分段长度或拆分文档");
            if (end == text.length()) break;
            start = Math.max(start + 1, end - overlap);
            if (start < text.length() && Character.isLowSurrogate(text.charAt(start))) start++;
        }
        return result;
    }

    public Object confirm(Identity actor, String library, String id, Map<String, Object> body) {
        if (!(body.get("segments") instanceof List<?> raw) || raw.isEmpty() || raw.size() > 500)
            throw new ApiError(422, "请选择1至500条分段");
        var values = new ArrayList<Map<String, Object>>();
        var indexes = new HashSet<Integer>();
        for (Object input : raw) {
            var value = map(input);
            int index = number(value.get("segment_index"));
            if (index < 1 || index > 500 || !indexes.add(index))
                throw new ApiError(422, "分段序号不正确或重复");
            value.put("kind", "document");
            value.put("document_id", id);
            values.add(entries.normalize(value));
        }
        String encoded = docs.json.canonical(values, false);
        if (encoded.length() > 300000) throw new ApiError(413, "分段内容过多，请拆分文件");
        String hash = KnowledgeGaps.sha(encoded);
        return docs.tx.serialized(
                () -> {
                    var job = require(actor.tenant(), library, id);
                    if ("committed".equals(job.get("state"))) {
                        if (!hash.equals(job.get("confirmation_sha")))
                            throw new ApiError(409, "文档已按其他分段确认");
                        return get(actor.tenant(), library, id);
                    }
                    if (!"ready".equals(job.get("state"))
                            || number(job.get("revision")) != number(body.get("expected_revision")))
                        throw new ApiError(409, "文档作业已变化，请刷新");
                    var original = docs.json.object(job.get("segments_json").toString());
                    var allowed = new HashSet<Integer>();
                    for (Object part : (List<?>) original.get("original_segments"))
                        allowed.add(number(map(part).get("segment_index")));
                    if (!allowed.containsAll(indexes)) throw new ApiError(422, "分段不属于该文档，请重新加载预览");
                    var lib = docs.require(actor.tenant(), "library", library);
                    if (number(lib.get("revision"))
                            != number(body.get("expected_library_revision")))
                        throw new ApiError(409, "知识库已变化，请刷新后重新核对分段");
                    int count =
                            docs.jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM platform_document WHERE tenant_id=? AND kind='knowledge' AND parent_id=?",
                                    Integer.class,
                                    actor.tenant(),
                                    library);
                    if (count + values.size() > 500) throw new ApiError(422, "单库最多500条知识，请移入其他知识库");
                    var saved = new ArrayList<Map<String, Object>>();
                    var parameters = new ArrayList<Object>();
                    var tuples = new ArrayList<String>();
                    for (var value : values) {
                        String entry = id + "-" + value.get("segment_index");
                        tuples.add("(?,'knowledge',?,?,?,?,?,?)");
                        Collections.addAll(
                                parameters,
                                actor.tenant(),
                                entry,
                                value.get("name"),
                                library,
                                docs.json.json(value),
                                actor.userId(),
                                Store.now());
                        saved.add(JsonSupport.map("object_id", entry, "draft", value));
                    }
                    try {
                        docs.jdbc.update(
                                "INSERT INTO platform_document(tenant_id,kind,object_id,name,parent_id,draft_json,updated_by,updated_at) VALUES "
                                        + String.join(",", tuples),
                                parameters.toArray());
                    } catch (org.springframework.dao.DuplicateKeyException e) {
                        throw new ApiError(409, "文档分段标识已存在，请刷新核对作业结果");
                    }
                    docs.save(
                            actor,
                            "library",
                            library,
                            "",
                            map(lib.get("draft")),
                            number(lib.get("revision")));
                    var parsed = docs.json.object(job.get("segments_json").toString());
                    docs.jdbc.update(
                            "UPDATE platform_knowledge_upload SET state='committed',revision=revision+1,confirmation_sha=?,segments_json=?,updated_at=? WHERE tenant_id=? AND id=?",
                            hash,
                            docs.json.json(
                                    JsonSupport.map(
                                            "segments",
                                            saved,
                                            "original_segments",
                                            parsed.get("original_segments"))),
                            Store.now(),
                            actor.tenant(),
                            id);
                    docs.recordTransition(actor, "knowledge_import", id, "confirmed_as_drafts");
                    return get(actor.tenant(), library, id);
                });
    }

    public Object change(
            Identity actor, String library, String id, Map<String, Object> body, boolean retry) {
        return docs.tx.serialized(
                () -> {
                    var row = require(actor.tenant(), library, id);
                    String state = row.get("state").toString();
                    if (number(row.get("revision")) != number(body.get("expected_revision")))
                        throw new ApiError(409, "文档作业已变化，请刷新");
                    if (retry && (!"failed".equals(state) || row.get("file_bytes") == null)
                            || !retry && !Set.of("queued", "ready", "failed").contains(state))
                        throw new ApiError(409, "当前状态不能执行此操作");
                    int changed;
                    if (retry)
                        changed =
                                docs.jdbc.update(
                                        "UPDATE platform_knowledge_upload SET state='queued',progress=0,error=NULL,revision=revision+1,updated_at=? WHERE tenant_id=? AND id=? AND state='failed' AND revision=?",
                                        Store.now(),
                                        actor.tenant(),
                                        id,
                                        row.get("revision"));
                    else
                        changed =
                                docs.jdbc.update(
                                        "UPDATE platform_knowledge_upload SET state='cancelled',file_bytes=NULL,segments_json=NULL,revision=revision+1,updated_at=? WHERE tenant_id=? AND id=? AND state IN ('queued','ready','failed') AND revision=?",
                                        Store.now(),
                                        actor.tenant(),
                                        id,
                                        row.get("revision"));
                    if (changed != 1) throw new ApiError(409, "文档作业已变化，请刷新");
                    docs.recordTransition(
                            actor, "knowledge_import", id, retry ? "retry_queued" : "cancelled");
                    return get(actor.tenant(), library, id);
                });
    }
}
