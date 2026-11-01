package com.duozhi.outbound.marketing.journey;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.marketing.common.MarketingAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Operator API of module P4: journeys, frequency policy, profiles and the funnel dashboard. */
@RestController
@RequestMapping("/api/v1/marketing")
public class JourneyApi {
    private final MarketingAccess access;
    private final JourneyService journeys;
    private final PolicyConfig policies;
    private final ProfileService profiles;
    private final FunnelStats stats;
    private final MarketingClock clock;

    public JourneyApi(
            MarketingAccess access,
            JourneyService journeys,
            PolicyConfig policies,
            ProfileService profiles,
            FunnelStats stats,
            MarketingClock clock) {
        this.access = access;
        this.journeys = journeys;
        this.policies = policies;
        this.profiles = profiles;
        this.stats = stats;
        this.clock = clock;
    }

    private static Map<String, Object> body(Map<String, Object> body) {
        return body == null ? Map.of() : body;
    }

    @GetMapping("/journeys")
    public Map<String, Object> list(HttpServletRequest request) {
        Identity id = access.operator(request);
        return Map.of("items", journeys.list(id.tenant()), "capabilities", journeys.capabilities());
    }

    @PostMapping("/journeys")
    public Map<String, Object> create(
            HttpServletRequest request, @RequestBody
            Map<String, Object> body) {
        Identity id = access.operator(request);
        return journeys.create(id.tenant(), access.actor(id), body(body));
    }

    @GetMapping("/journeys/{journey}")
    public Map<String, Object> get(HttpServletRequest request, @PathVariable
    String journey) {
        return journeys.get(access.operator(request).tenant(), journey);
    }

    @PutMapping("/journeys/{journey}")
    public Map<String, Object> update(
            HttpServletRequest request,
            @PathVariable
            String journey,
            @RequestBody
            Map<String, Object> body) {
        return journeys.update(access.operator(request).tenant(), journey, body(body));
    }

    @GetMapping("/journeys/{journey}/versions/{version}")
    public Map<String, Object> version(
            HttpServletRequest request, @PathVariable
            String journey, @PathVariable
            int version) {
        return journeys.version(access.operator(request).tenant(), journey, version);
    }

    @PostMapping("/journeys/{journey}/publish")
    public Map<String, Object> publish(HttpServletRequest request, @PathVariable
    String journey) {
        Identity id = access.operator(request);
        return journeys.publish(id.tenant(), access.actor(id), journey);
    }

    @PostMapping("/journeys/{journey}/estimate")
    public Map<String, Object> estimate(
            HttpServletRequest request,
            @PathVariable
            String journey,
            @RequestBody(required = false)
            Map<String, Object> body) {
        return journeys.estimate(access.operator(request).tenant(), journey, snapshot(body(body)));
    }

    @PostMapping("/journeys/{journey}/start")
    public Map<String, Object> start(
            HttpServletRequest request,
            @PathVariable
            String journey,
            @RequestBody(required = false)
            Map<String, Object> body) {
        Identity id = access.operator(request);
        return journeys.start(id.tenant(), access.actor(id), journey, snapshot(body(body)));
    }

    @PostMapping("/journeys/{journey}/approve")
    public Map<String, Object> approve(HttpServletRequest request, @PathVariable
    String journey) {
        Identity id = access.operator(request);
        return journeys.approve(id.tenant(), access.actor(id), journey);
    }

    @PostMapping("/journeys/{journey}/reject")
    public Map<String, Object> reject(HttpServletRequest request, @PathVariable
    String journey) {
        Identity id = access.operator(request);
        return journeys.reject(id.tenant(), access.actor(id), journey);
    }

    @PostMapping("/journeys/{journey}/pause")
    public Map<String, Object> pause(HttpServletRequest request, @PathVariable
    String journey) {
        return journeys.pause(access.operator(request).tenant(), journey);
    }

    @PostMapping("/journeys/{journey}/resume")
    public Map<String, Object> resume(HttpServletRequest request, @PathVariable
    String journey) {
        return journeys.resume(access.operator(request).tenant(), journey);
    }

    @PostMapping("/journeys/{journey}/stop")
    public Map<String, Object> stop(HttpServletRequest request, @PathVariable
    String journey) {
        return journeys.stop(access.operator(request).tenant(), journey);
    }

    @GetMapping("/journeys/{journey}/runtime")
    public Map<String, Object> runtime(HttpServletRequest request, @PathVariable
    String journey) {
        return journeys.runtime(access.operator(request).tenant(), journey);
    }

    @GetMapping("/policy")
    public Map<String, Object> policy(HttpServletRequest request) {
        return policies.get(access.operator(request).tenant()).view();
    }

    @PutMapping("/policy")
    public Map<String, Object> savePolicy(
            HttpServletRequest request, @RequestBody
            Map<String, Object> body) {
        Identity id = access.operator(request);
        return policies.save(id.tenant(), body(body), access.actor(id)).view();
    }

    /** POST so the phone number stays in the body, never in a URL or access log. */
    @PostMapping("/profiles/lookup")
    public Map<String, Object> lookup(
            HttpServletRequest request, @RequestBody
            Map<String, Object> body) {
        Object phone = body(body).get("phone");
        if (phone == null || phone.toString().isBlank()) throw new ApiError(422, "请输入手机号");
        return profiles.lookup(access.operator(request).tenant(), phone.toString());
    }

    @GetMapping("/profiles/overview")
    public Map<String, Object> overview(HttpServletRequest request) {
        return profiles.overview(access.operator(request).tenant());
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(
            HttpServletRequest request,
            @RequestParam(required = false)
            String journeyId,
            @RequestParam(required = false)
            String snapshotId,
            @RequestParam(required = false)
            String from,
            @RequestParam(required = false)
            String to,
            @RequestParam(required = false)
            String channel) {
        Identity id = access.operator(request);
        LocalDate end = date(to, clock.appNow().toLocalDate());
        LocalDate start = date(from, end.minusDays(6));
        return stats.dashboard(id.tenant(), journeyId, snapshotId, start, end, channel);
    }

    @PostMapping("/dashboard/refresh")
    public Map<String, Object> refresh(
            HttpServletRequest request, @RequestBody(required = false)
            Map<String, Object> body) {
        Identity id = access.operator(request);
        Map<String, Object> b = body(body);
        LocalDate end = date(Objects.toString(b.get("to"), null), clock.appNow().toLocalDate());
        LocalDate start = date(Objects.toString(b.get("from"), null), end.minusDays(6));
        return Map.of("days", stats.refresh(id.tenant(), start, end));
    }

    private static String snapshot(Map<String, Object> body) {
        Object s = body.get("snapshotId");
        return s == null ? null : s.toString().trim();
    }

    private static LocalDate date(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return LocalDate.parse(value.substring(0, Math.min(10, value.length())));
        } catch (Exception e) {
            throw new ApiError(422, "日期格式应为 yyyy-MM-dd");
        }
    }
}
