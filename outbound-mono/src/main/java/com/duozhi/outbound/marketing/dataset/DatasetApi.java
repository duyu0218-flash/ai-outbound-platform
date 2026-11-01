package com.duozhi.outbound.marketing.dataset;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.marketing.common.MarketingAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Operator API for data packages, matching, segments, snapshots and snapshot send tasks. */
@RestController
@RequestMapping("/api/v1/marketing")
public class DatasetApi {
    private final MarketingAccess access;
    private final DatasetImports imports;
    private final Matching matching;
    private final Segments segments;
    private final SendTasks sends;

    public DatasetApi(
            MarketingAccess access,
            DatasetImports imports,
            Matching matching,
            Segments segments,
            SendTasks sends) {
        this.access = access;
        this.imports = imports;
        this.matching = matching;
        this.segments = segments;
        this.sends = sends;
    }

    private Identity who(HttpServletRequest request) {
        return access.operator(request);
    }

    private static byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ApiError(422, "请选择文件");
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ApiError(422, "文件读取失败");
        }
    }

    private static String text(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : v.toString();
    }

    // ---- data packages

    @GetMapping("/datasets")
    public List<Map<String, Object>> datasets(HttpServletRequest request) {
        return imports.datasets(who(request).tenant());
    }

    @PostMapping("/datasets")
    public Map<String, Object> createDataset(
            @RequestBody
            Map<String, Object> body, HttpServletRequest request) {
        var id = who(request);
        return imports.createDataset(
                id.tenant(), text(body, "name"), text(body, "description"), access.actor(id));
    }

    @GetMapping("/datasets/{id}/versions")
    public List<Map<String, Object>> versions(@PathVariable
    String id, HttpServletRequest request) {
        return imports.versions(who(request).tenant(), id);
    }

    @PostMapping("/datasets/{id}/versions")
    public Map<String, Object> upload(
            @PathVariable
            String id,
            @RequestParam("file")
            MultipartFile file,
            @RequestParam(value = "authorizationRef", required = false)
            String authorizationRef,
            @RequestParam(value = "source", required = false)
            String source,
            HttpServletRequest request) {
        var who = who(request);
        var version =
                imports.upload(
                        who.tenant(),
                        id,
                        file.getOriginalFilename(),
                        bytes(file),
                        authorizationRef,
                        source,
                        access.actor(who));
        // Small files finish inline; larger ones continue in the worker or via "继续导入".
        imports.process((String) version.get("id"), 10);
        return imports.version(who.tenant(), (String) version.get("id"));
    }

    @GetMapping("/dataset-versions")
    public List<Map<String, Object>> readyVersions(HttpServletRequest request) {
        return imports.readyVersions(who(request).tenant());
    }

    @GetMapping("/dataset-versions/{id}")
    public Map<String, Object> version(@PathVariable
    String id, HttpServletRequest request) {
        return imports.version(who(request).tenant(), id);
    }

    @PostMapping("/dataset-versions/{id}/process")
    public Map<String, Object> process(@PathVariable
    String id, HttpServletRequest request) {
        long tenant = who(request).tenant();
        imports.version(tenant, id);
        imports.process(id, 20);
        return imports.version(tenant, id);
    }

    @PutMapping("/dataset-versions/{id}/authorization")
    public Map<String, Object> authorize(
            @PathVariable
            String id,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest request) {
        return imports.authorize(who(request).tenant(), id, text(body, "authorizationRef"));
    }

    @GetMapping("/dataset-versions/{id}/rows")
    public Map<String, Object> rows(
            @PathVariable
            String id,
            @RequestParam(required = false)
            String status,
            @RequestParam(defaultValue = "1")
            int page,
            @RequestParam(defaultValue = "50")
            int size,
            HttpServletRequest request) {
        return imports.rows(who(request).tenant(), id, status, page, size);
    }

    // ---- partners and matching

    @GetMapping("/match/adapters")
    public List<Map<String, Object>> adapters(HttpServletRequest request) {
        who(request);
        return matching.adapterList();
    }

    @GetMapping("/partners")
    public List<Map<String, Object>> partners(HttpServletRequest request) {
        return matching.partners(who(request).tenant());
    }

    @PostMapping("/partners")
    public Map<String, Object> createPartner(
            @RequestBody
            Map<String, Object> body, HttpServletRequest request) {
        return matching.savePartner(who(request).tenant(), null, body);
    }

    @PutMapping("/partners/{id}")
    public Map<String, Object> updatePartner(
            @PathVariable
            String id,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest request) {
        return matching.savePartner(who(request).tenant(), id, body);
    }

    @GetMapping("/match-jobs")
    public List<Map<String, Object>> jobs(
            @RequestParam(required = false)
            String versionId, HttpServletRequest request) {
        return matching.jobs(who(request).tenant(), versionId);
    }

    @PostMapping("/match-jobs")
    public Map<String, Object> submit(
            @RequestBody
            Map<String, Object> body, HttpServletRequest request) {
        var who = who(request);
        Integer topN = body.get("topN") == null ? null : JsonSupport.integer(body.get("topN"), 3);
        return matching.submit(
                who.tenant(),
                text(body, "partnerId"),
                text(body, "versionId"),
                topN,
                access.actor(who));
    }

    @GetMapping("/match-jobs/{id}")
    public Map<String, Object> job(@PathVariable
    String id, HttpServletRequest request) {
        return matching.job(who(request).tenant(), id);
    }

    @PostMapping("/match-jobs/{id}/run")
    public Map<String, Object> runJob(@PathVariable
    String id, HttpServletRequest request) {
        return matching.run(who(request).tenant(), id, 10);
    }

    @PostMapping("/match-jobs/{id}/resume")
    public Map<String, Object> resume(@PathVariable
    String id, HttpServletRequest request) {
        return matching.resume(who(request).tenant(), id);
    }

    @GetMapping("/match-jobs/{id}/analysis")
    public Map<String, Object> analysis(@PathVariable
    String id, HttpServletRequest request) {
        return matching.analysis(who(request).tenant(), id);
    }

    @GetMapping("/match-jobs/{id}/hash-file")
    public ResponseEntity<byte[]> exportHashes(
            @PathVariable
            String id, HttpServletRequest request) {
        String body = matching.exportHashes(who(request).tenant(), id);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"match-" + id + "-sha256.txt\"")
                .body(body.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/match-jobs/{id}/hit-file")
    public Map<String, Object> importHits(
            @PathVariable
            String id,
            @RequestParam("file")
            MultipartFile file,
            HttpServletRequest request) {
        return matching.importHits(who(request).tenant(), id, bytes(file));
    }

    @PostMapping("/match-imports/ant-platform")
    public Map<String, Object> importAnt(
            @RequestParam("file")
            MultipartFile file,
            @RequestParam("partnerId")
            String partnerId,
            @RequestParam(value = "versionId", required = false)
            String versionId,
            HttpServletRequest request) {
        var who = who(request);
        return matching.importAntPlatform(
                who.tenant(), partnerId, versionId, bytes(file), access.actor(who));
    }

    // ---- segments and snapshots

    @GetMapping("/segments")
    public List<Map<String, Object>> segments(HttpServletRequest request) {
        return segments.list(who(request).tenant());
    }

    @PostMapping("/segments")
    public Map<String, Object> createSegment(
            @RequestBody
            Map<String, Object> body, HttpServletRequest request) {
        var who = who(request);
        return segments.save(who.tenant(), null, body, access.actor(who));
    }

    @PutMapping("/segments/{id}")
    public Map<String, Object> updateSegment(
            @PathVariable
            String id,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest request) {
        var who = who(request);
        return segments.save(who.tenant(), id, body, access.actor(who));
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/segments/preview")
    public Map<String, Object> preview(
            @RequestBody
            Map<String, Object> body, HttpServletRequest request) {
        Object rules = body.get("rules");
        return segments.preview(
                who(request).tenant(),
                rules instanceof Map<?, ?> m ? (Map<String, Object>) m : null);
    }

    @PostMapping("/segments/{id}/snapshots")
    public Map<String, Object> freeze(
            @PathVariable
            String id,
            @RequestBody(required = false)
            Map<String, Object> body,
            HttpServletRequest request) {
        var who = who(request);
        return segments.freeze(
                who.tenant(),
                id,
                body == null ? null : body.get("controlPercent"),
                access.actor(who));
    }

    @GetMapping("/snapshots")
    public List<Map<String, Object>> snapshots(
            @RequestParam(required = false)
            String segmentId, HttpServletRequest request) {
        return segments.snapshots(who(request).tenant(), segmentId);
    }

    @GetMapping("/snapshots/{id}")
    public Map<String, Object> snapshot(@PathVariable
    String id, HttpServletRequest request) {
        return segments.snapshot(who(request).tenant(), id);
    }

    @GetMapping("/snapshots/{id}/members")
    public List<Map<String, Object>> members(
            @PathVariable
            String id,
            @RequestParam(defaultValue = "0")
            long after,
            @RequestParam(defaultValue = "100")
            int limit,
            HttpServletRequest request) {
        return segments.memberView(who(request).tenant(), id, after, limit);
    }

    // ---- send tasks from snapshots

    @GetMapping("/send-options")
    public Map<String, Object> sendOptions(HttpServletRequest request) {
        return sends.options(who(request).tenant());
    }

    @GetMapping("/send-tasks")
    public List<Map<String, Object>> sendTasks(
            @RequestParam(required = false)
            String snapshotId, HttpServletRequest request) {
        return sends.tasks(who(request).tenant(), snapshotId);
    }

    @PostMapping("/send-tasks")
    public Map<String, Object> createSendTask(
            @RequestBody
            Map<String, Object> body, HttpServletRequest request) {
        var who = who(request);
        var task = sends.create(who.tenant(), body, request, access.actor(who));
        return sends.run(who.tenant(), (String) task.get("id"), 2);
    }

    @GetMapping("/send-tasks/{id}")
    public Map<String, Object> sendTask(@PathVariable
    String id, HttpServletRequest request) {
        return sends.task(who(request).tenant(), id);
    }

    @GetMapping("/send-tasks/{id}/items")
    public List<Map<String, Object>> sendItems(
            @PathVariable
            String id,
            @RequestParam(required = false)
            String state,
            @RequestParam(defaultValue = "1")
            int page,
            HttpServletRequest request) {
        return sends.items(who(request).tenant(), id, state, page);
    }

    @PostMapping("/send-tasks/{id}/run")
    public Map<String, Object> runSendTask(@PathVariable
    String id, HttpServletRequest request) {
        return sends.run(who(request).tenant(), id, 5);
    }

    @PostMapping("/send-tasks/{id}/cancel")
    public Map<String, Object> cancelSendTask(@PathVariable
    String id, HttpServletRequest request) {
        return sends.cancel(who(request).tenant(), id);
    }
}
