package com.duozhi.outbound.workspace.controller;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.auth.domain.*;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspace/cost-assumptions")
@AdminPermission("reports:write")
public class CostAssumptions {
    private final WorkspaceDocuments docs;

    public CostAssumptions(WorkspaceDocuments docs) {
        this.docs = docs;
    }

    private Identity actor(HttpServletRequest r) {
        var i = Identity.from(r);
        i.admin("reports:write");
        return i;
    }

    @GetMapping
    public Object get(HttpServletRequest r) {
        var row = docs.find(actor(r).tenant(), "cost_assumption", "current");
        return row == null ? Map.of("revision", 0, "draft", Map.of()) : row;
    }

    @PutMapping
    public Object save(@RequestBody
    Map<String, Object> body, HttpServletRequest r) {
        var i = actor(r);
        var raw = map(body.get("draft"));
        var value = new LinkedHashMap<String, Object>();
        value.put("name", "手工费用估算口径");
        for (String k : List.of("telephony", "ai", "sms")) {
            Object n = raw.get(k);
            if (n == null || n.toString().isBlank()) {
                value.put(k, null);
                continue;
            }
            try {
                var p = new java.math.BigDecimal(n.toString());
                if (p.signum() < 0
                        || p.compareTo(new java.math.BigDecimal("1000000")) > 0
                        || p.scale() > 6) throw new IllegalArgumentException();
                value.put(k, p);
            } catch (Exception e) {
                throw new ApiError(422, "单价须为0至1000000、最多6位小数；未知请留空");
            }
        }
        value.put("source", text(raw, "source", 1000, true));
        for (String k : List.of("valid_from", "valid_until")) {
            String date = text(raw, k, 10, false);
            if (!date.isBlank())
                try {
                    java.time.LocalDate.parse(date);
                } catch (Exception e) {
                    throw new ApiError(422, "价格有效期须为年-月-日");
                }
            value.put(k, date);
        }
        String start = value.get("valid_from").toString(),
                end = value.get("valid_until").toString();
        if (!start.isBlank() && !end.isBlank() && start.compareTo(end) > 0)
            throw new ApiError(422, "有效期结束不得早于开始");
        value.put("currency", "CNY");
        value.put("units", Map.of("telephony", "元/通话分钟", "ai", "元/AI占用分钟", "sms", "元/条"));
        value.put("disclaimer", "手工估算，非供应商账单；缺失量未计价，0表示明确免费");
        return docs.save(
                i, "cost_assumption", "current", "", value, number(body.get("expected_revision")));
    }
}
