package com.duozhi.outbound.workspace.controller;

import com.duozhi.outbound.auth.domain.*;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/workspace/scripts/{id}/audio")
@AdminPermission(value = "product:write", operator = true)
public class ScriptAudio {
    private final WorkspaceDocuments docs;
    private final WorkspaceController scripts;

    public ScriptAudio(WorkspaceDocuments docs, WorkspaceController scripts) {
        this.docs = docs;
        this.scripts = scripts;
    }

    private static String key(String s) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, String> prompts(long id, HttpServletRequest r) {
        var doc = scripts.script(String.valueOf(id), r);
        var draft = WorkspaceDocuments.map(doc.get("draft"));
        var set = new LinkedHashMap<String, String>();
        for (String field : List.of("opening", "ending")) {
            String text = Objects.toString(draft.get(field), "").trim();
            if (!text.isEmpty()) set.put(key(text), text);
        }
        for (var raw :
                docs.jdbc.queryForList(
                        "SELECT graph_json FROM scriptflowversion WHERE tenant_id=? AND script_template_id=? ORDER BY version DESC LIMIT 1",
                        Identity.from(r).tenant(),
                        id)) {
            var graph = docs.json.object(raw.get("graph_json").toString());
            for (Object obj : (List<?>) graph.getOrDefault("nodes", List.of())) {
                var node = WorkspaceDocuments.map(obj);
                String text = Objects.toString(node.get("prompt"), "").trim();
                if (!text.isEmpty()) set.put(key(text), text);
            }
        }
        return set;
    }

    @GetMapping
    public Object list(@PathVariable
    long id, HttpServletRequest r) {
        var prompts = prompts(id, r);
        var recorded = new HashMap<String, Number>();
        for (var row :
                docs.jdbc.queryForList(
                        "SELECT segment_key,revision FROM script_recording_asset WHERE tenant_id=? AND script_id=?",
                        Identity.from(r).tenant(),
                        id))
            recorded.put(row.get("segment_key").toString(), (Number) row.get("revision"));
        return prompts.entrySet().stream()
                .map(
                        e ->
                                Map.of(
                                        "key",
                                        e.getKey(),
                                        "text",
                                        e.getValue(),
                                        "revision",
                                        recorded.getOrDefault(e.getKey(), 0)))
                .toList();
    }

    @PostMapping("/{segment}")
    @Transactional
    public Object upload(
            @PathVariable
            long id,
            @PathVariable
            String segment,
            @RequestParam
            int revision,
            @RequestParam
            MultipartFile audio,
            HttpServletRequest r)
            throws java.io.IOException {
        var i = Identity.from(r);
        if (audio.isEmpty() || audio.getSize() > 4 * 1024 * 1024)
            throw new ApiError(422, "请选择 4 MB 以内的语音文件");
        byte[] b = audio.getBytes();
        String type;
        if (b.length > 12
                && b[0] == 0x1a
                && b[1] == 0x45
                && b[2] == (byte) 0xdf
                && b[3] == (byte) 0xa3) type = "audio/webm";
        else if (b.length > 44
                && new String(b, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(b, 8, 4, StandardCharsets.US_ASCII).equals("WAVE"))
            type = "audio/wav";
        else if (b.length > 12 && new String(b, 4, 4, StandardCharsets.US_ASCII).equals("ftyp"))
            type = "audio/mp4";
        else throw new ApiError(422, "支持 WAV、WebM 或 M4A 音频");
        docs.tx.tenant(i.tenant());
        String text = prompts(id, r).get(segment);
        if (text == null) throw new ApiError(409, "话术文本已变更，请重新检测");
        var row =
                docs.one(
                        "SELECT revision FROM script_recording_asset WHERE tenant_id=? AND script_id=? AND segment_key=? FOR UPDATE",
                        i.tenant(),
                        id,
                        segment);
        if ((row == null ? 0 : ((Number) row.get("revision")).intValue()) != revision)
            throw new ApiError(409, "录音已更新，请重新检测");
        docs.jdbc.update(
                "INSERT INTO script_recording_asset(tenant_id,script_id,segment_key,prompt_text,media_type,content,revision,updated_by,updated_at) VALUES(?,?,?,?,?,?,1,?,UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE content=?,media_type=?,revision=revision+1,updated_at=UTC_TIMESTAMP(3),updated_by=?",
                i.tenant(),
                id,
                segment,
                text.substring(0, Math.min(4000, text.length())),
                type,
                b,
                i.userId(),
                b,
                type,
                i.userId());
        return Map.of("revision", revision + 1);
    }

    @GetMapping("/{segment}")
    public ResponseEntity<byte[]> read(
            @PathVariable
            long id, @PathVariable
            String segment, HttpServletRequest r) {
        if (!prompts(id, r).containsKey(segment)) throw new ApiError(404, "话术片段不存在");
        var row =
                docs.one(
                        "SELECT media_type,content FROM script_recording_asset WHERE tenant_id=? AND script_id=? AND segment_key=?",
                        Identity.from(r).tenant(),
                        id,
                        segment);
        if (row == null) throw new ApiError(404, "尚无录音");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(row.get("media_type").toString()))
                .body((byte[]) row.get("content"));
    }
}
