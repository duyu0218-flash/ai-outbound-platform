package com.duozhi.outbound.workspace.controller;

import com.duozhi.outbound.auth.domain.*;
import com.duozhi.outbound.customer.controller.ContactFiles;
import com.duozhi.outbound.workspace.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.apache.commons.csv.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspace/audiences")
@AdminPermission("contacts:write")
public class AudienceWorkflow {
    private final WorkspaceAudience audience;
    private final com.duozhi.outbound.marketing.dataset.Segments segments;

    public AudienceWorkflow(
            WorkspaceAudience audience, com.duozhi.outbound.marketing.dataset.Segments segments) {
        this.audience = audience;
        this.segments = segments;
    }

    private long tenant(HttpServletRequest r) {
        var i = Identity.from(r);
        i.admin("contacts:write");
        return i.tenant();
    }

    @GetMapping("/segments")
    public Object segments(HttpServletRequest r) {
        return segments.snapshots(tenant(r), null);
    }

    @PostMapping("/preview")
    public Object preview(
            @RequestBody
            Map<String, Object> body,
            @RequestParam(defaultValue = "1")
            int page,
            @RequestParam(defaultValue = "50")
            int size,
            HttpServletRequest r) {
        return audience.preview(tenant(r), body, page, size);
    }

    @PostMapping("/export")
    public ResponseEntity<String> export(
            @RequestBody
            Map<String, Object> body, HttpServletRequest r)
            throws java.io.IOException {
        var result = audience.resolve(tenant(r), body);
        var output = new java.io.StringWriter();
        output.write('\ufeff');
        try (var csv = new CSVPrinter(output, CSVFormat.DEFAULT)) {
            csv.printRecord("客户ID", "手机号", "姓名", "业务变量");
            for (var e : result.members().entrySet()) {
                var v = e.getValue();
                csv.printRecord(
                        e.getKey(),
                        ContactFiles.safe(v.get("phone")),
                        ContactFiles.safe(v.get("name")),
                        ContactFiles.safe(v.toString()));
            }
        }
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv;charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=audience.csv")
                .body(output.toString());
    }
}
