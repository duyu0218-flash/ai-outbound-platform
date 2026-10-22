package com.duozhi.outbound.system.controller;

import com.duozhi.outbound.auth.domain.AdminPermission;
import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.Values;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class Dashboard {
    private final Store store;
    private static final String INTERESTED =
            "('interested','qualified_lead','positive_lead','appointment','converted')";
    private static final String REACHED =
            "(c.status IN"
                    + " ('ANSWERED','IN_AI','WAITING_HUMAN','HANDOFF_TRANSFERRING','IN_HUMAN','COMPLETED')"
                    + " OR a.result_code IN"
                    + " ('interested','qualified_lead','positive_lead','appointment','converted','rejected','completed'))";

    public Dashboard(Store store) {
        this.store = store;
    }

    private double rate(long a, long b) {
        return b == 0 ? 0 : Math.round(a * 1000.0 / b) / 10.0;
    }

    private long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number ? ((Number) value).longValue() : 0;
    }

    @GetMapping("/api/v1/agent/dashboard")
    public Map<String, String> agent(HttpServletRequest request) {
        Identity.from(request).requireUser();
        return Values.map("scope", "agent", "message", "座席工作台");
    }

    @AdminPermission("admin:dashboard")
    @GetMapping("/api/v1/admin/dashboard")
    public Map<String, Object> admin(
            HttpServletRequest request, @RequestParam(defaultValue = "30")
            int days) {
        if (days < 1 || days > 365) throw new ApiError(400, "invalid days");
        long tenant = Identity.from(request).tenant();
        LocalDateTime since = Store.now().minusDays(days);
        Object sqlSince = store.encode("callsession", "created_at", since);
        String join =
                " FROM callsession c LEFT JOIN callanalysis a ON a.call_session_id=c.id AND"
                        + " a.tenant_id=c.tenant_id WHERE c.tenant_id=? AND c.created_at>=?";
        Map<String, Object> counts =
                store.projectOne(
                        "callsession",
                        "dashboardSummary",
                        Values.map("tenantId", tenant, "since", sqlSince));
        LinkedHashMap<String, Object> period = new LinkedHashMap<String, Object>();
        period.put("days", days);
        period.put("since", since.toString());
        for (String key :
                Values.list(
                        "calls",
                        "reached",
                        "completed",
                        "failed",
                        "analyzed",
                        "interested",
                        "pending_reviews")) period.put(key, number(counts, key));
        period.put(
                "average_qa_score",
                counts.get("average_qa_score") instanceof Number
                        ? Math.round(((Number) counts.get("average_qa_score")).doubleValue() * 10)
                                / 10.0
                        : 0);
        period.put("reach_rate", rate(number(counts, "reached"), number(counts, "calls")));
        period.put("interest_rate", rate(number(counts, "interested"), number(counts, "reached")));
        period.put("completion_rate", rate(number(counts, "completed"), number(counts, "calls")));
        ArrayList<Map<String, Object>> performance = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> rows =
                store.projection(
                        "callsession",
                        "dashboardPerformance",
                        Values.map("tenantId", tenant, "since", sqlSince));
        for (Map<String, Object> row : rows) {
            Map<String, Object> campaign =
                    store.one(
                            "campaign",
                            "sourceDashboard28_0",
                            Store.queryParameters(0, tenant, row.get("campaign_id")));
            if (campaign == null) continue;
            LinkedHashMap<String, Object> out = new LinkedHashMap<>(row);
            out.put("name", campaign.get("name"));
            out.put("reach_rate", rate(number(row, "reached"), number(row, "calls")));
            out.put("interest_rate", rate(number(row, "interested"), number(row, "reached")));
            performance.add(out);
        }
        return Values.map(
                "scope",
                "admin",
                "message",
                "管理员控制台",
                "stats",
                Values.map(
                        "contacts",
                        store.scalar(
                                "contact", "sourceDashboard29_1", Store.queryParameters(0, tenant)),
                        "active_scripts",
                        store.scalar(
                                "scripttemplate",
                                "sourceDashboard29_2",
                                Store.queryParameters(0, tenant, true)),
                        "campaigns",
                        store.scalar(
                                "campaign",
                                "sourceDashboard29_3",
                                Store.queryParameters(0, tenant)),
                        "calls",
                        store.scalar(
                                "callsession",
                                "sourceDashboard29_4",
                                Store.queryParameters(0, tenant))),
                "period",
                period,
                "campaign_performance",
                performance,
                "metric_definitions",
                Values.map(
                        "reached",
                        "当前状态为已接听及后续阶段，或分析/人工复核结果表明已完成有效沟通的通话",
                        "interested",
                        "自动分析或人工复核结果为 interested、qualified_lead、positive_lead、appointment 或"
                                + " converted"));
    }
}
