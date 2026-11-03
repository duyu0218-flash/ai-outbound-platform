package com.duozhi.outbound.workspace.controller;

import com.duozhi.outbound.auth.domain.*;
import com.duozhi.outbound.workspace.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/workspace")
@AdminPermission("product:write")
public class KnowledgeWorkflow {
    private final KnowledgeDirectories directories;
    private final KnowledgeDocuments documents;
    private final KnowledgeGaps gaps;

    public KnowledgeWorkflow(
            KnowledgeDirectories directories, KnowledgeDocuments documents, KnowledgeGaps gaps) {
        this.directories = directories;
        this.documents = documents;
        this.gaps = gaps;
    }

    private Identity actor(HttpServletRequest request) {
        var i = Identity.from(request);
        i.admin("product:write");
        return i;
    }

    @GetMapping("/libraries/{library}/categories")
    public Object categories(@PathVariable
    String library, HttpServletRequest r) {
        return directories.list(actor(r).tenant(), library);
    }

    @PostMapping("/libraries/{library}/categories")
    public Object createCategory(
            @PathVariable
            String library,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest r) {
        return directories.create(actor(r), library, body);
    }

    @PutMapping("/libraries/{library}/categories")
    public Object renameCategory(
            @PathVariable
            String library,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest r) {
        return directories.change(actor(r), library, body, false);
    }

    @PostMapping("/libraries/{library}/categories/remove")
    public Object removeCategory(
            @PathVariable
            String library,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest r) {
        return directories.change(actor(r), library, body, true);
    }

    @GetMapping("/libraries/{library}/documents")
    public Object documents(@PathVariable
    String library, HttpServletRequest r) {
        return documents.list(actor(r).tenant(), library);
    }

    @PostMapping(value = "/libraries/{library}/documents", consumes = "multipart/form-data")
    public Object upload(
            @PathVariable
            String library,
            @RequestPart
            MultipartFile file,
            @RequestParam
            String request_key,
            @RequestParam(defaultValue = "800")
            int split_size,
            @RequestParam(defaultValue = "80")
            int overlap,
            @RequestParam(defaultValue = "")
            String category,
            HttpServletRequest r)
            throws IOException {
        return documents.upload(
                actor(r), library, file, request_key, split_size, overlap, category);
    }

    @GetMapping("/libraries/{library}/documents/{id}")
    public Object document(
            @PathVariable
            String library, @PathVariable
            String id, HttpServletRequest r) {
        return documents.get(actor(r).tenant(), library, id);
    }

    @PostMapping("/libraries/{library}/documents/{id}/confirm")
    public Object confirm(
            @PathVariable
            String library,
            @PathVariable
            String id,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest r) {
        return documents.confirm(actor(r), library, id, body);
    }

    @PostMapping("/libraries/{library}/documents/{id}/retry")
    public Object retry(
            @PathVariable
            String library,
            @PathVariable
            String id,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest r) {
        return documents.change(actor(r), library, id, body, true);
    }

    @PostMapping("/libraries/{library}/documents/{id}/cancel")
    public Object cancel(
            @PathVariable
            String library,
            @PathVariable
            String id,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest r) {
        return documents.change(actor(r), library, id, body, false);
    }

    @GetMapping("/knowledge-gaps")
    public Object gaps(
            @RequestParam(defaultValue = "pending")
            String state,
            @RequestParam(defaultValue = "call")
            String source,
            @RequestParam(defaultValue = "")
            String keyword,
            @RequestParam(defaultValue = "1")
            int page,
            @RequestParam(defaultValue = "50")
            int size,
            HttpServletRequest r) {
        return gaps.list(actor(r).tenant(), state, source, keyword, page, size);
    }

    @GetMapping("/knowledge-gaps/{id}")
    public Object gap(@PathVariable
    String id, HttpServletRequest r) {
        return gaps.require(actor(r).tenant(), id);
    }

    @PostMapping("/knowledge-gaps/{id}/adopt")
    public Object adopt(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        return gaps.adopt(actor(r), id, body);
    }

    @PostMapping("/knowledge-gaps/{id}/dismiss")
    public Object dismiss(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        return gaps.transition(actor(r), id, body, false);
    }

    @PostMapping("/knowledge-gaps/{id}/reopen")
    public Object reopen(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        return gaps.transition(actor(r), id, body, true);
    }
}
