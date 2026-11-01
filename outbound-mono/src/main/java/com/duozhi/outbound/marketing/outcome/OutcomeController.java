package com.duozhi.outbound.marketing.outcome;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.marketing.common.MarketingAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Operator API for call outcomes; every route requires {@link MarketingAccess#operator}. */
@RestController
@RequestMapping("/api/v1/marketing/outcomes")
public class OutcomeController {
    private final MarketingAccess access;
    private final OutcomeService outcomes;
    private final OutcomeQueries queries;
    private final OutcomeRules rules;
    private final OutcomeLlm llm;
    private final Config config;

    public OutcomeController(
            MarketingAccess access,
            OutcomeService outcomes,
            OutcomeQueries queries,
            OutcomeRules rules,
            OutcomeLlm llm,
            Config config) {
        this.access = access;
        this.outcomes = outcomes;
        this.queries = queries;
        this.rules = rules;
        this.llm = llm;
        this.config = config;
    }

    private long tenant(HttpServletRequest r) {
        return access.operator(r).tenant();
    }

    /** Accepts 2026-11-01, 2026-11-01T08:00[:00] or "2026-11-01 08:00:00", all in UTC. */
    static LocalDateTime time(String v, boolean endOfDay) {
        if (v == null || v.isBlank()) return null;
        String s = v.strip().replace(' ', 'T').replaceAll("Z$", "");
        try {
            if (s.length() == 10) {
                LocalDate d = LocalDate.parse(s);
                return endOfDay ? d.plusDays(1).atStartOfDay() : d.atStartOfDay();
            }
            return LocalDateTime.parse(s.length() == 16 ? s + ":00" : s);
        } catch (RuntimeException e) {
            throw new ApiError(422, "时间格式无效：" + v);
        }
    }

    private OutcomeQueries.Filter filter(
            String from, String to, Long campaign, String code, String review, boolean pending) {
        return new OutcomeQueries.Filter(
                time(from, false), time(to, true), campaign, code, review, pending);
    }

    @GetMapping("/meta")
    public Map<String, Object> meta(HttpServletRequest r) {
        long t = tenant(r);
        return JsonSupport.map(
                "connection_codes",
                OutcomeClassifier.CONNECTION,
                "answer_codes",
                OutcomeClassifier.ANSWER,
                "intent_codes",
                OutcomeClassifier.INTENT,
                "campaigns",
                queries.campaigns(t),
                "worker_enabled",
                config.flag("MARKETING_WORKERS_ENABLED", false),
                "llm_available",
                llm.available(),
                "active_rule_version",
                rules.active(t).version());
    }

    @GetMapping
    public Map<String, Object> list(
            HttpServletRequest r,
            @RequestParam(required = false)
            String from,
            @RequestParam(required = false)
            String to,
            @RequestParam(name = "campaign_id", required = false)
            Long campaign,
            @RequestParam(required = false)
            String code,
            @RequestParam(name = "review_state", required = false)
            String review,
            @RequestParam(defaultValue = "1")
            int page,
            @RequestParam(defaultValue = "50")
            int size) {
        return queries.list(tenant(r), filter(from, to, campaign, code, review, false), page, size);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(
            HttpServletRequest r,
            @RequestParam(required = false)
            String from,
            @RequestParam(required = false)
            String to,
            @RequestParam(name = "campaign_id", required = false)
            Long campaign) {
        return queries.stats(tenant(r), filter(from, to, campaign, null, null, false));
    }

    @GetMapping("/review-queue")
    public Map<String, Object> queue(
            HttpServletRequest r,
            @RequestParam(required = false)
            String from,
            @RequestParam(required = false)
            String to,
            @RequestParam(name = "campaign_id", required = false)
            Long campaign,
            @RequestParam(defaultValue = "1")
            int page,
            @RequestParam(defaultValue = "50")
            int size) {
        return queries.list(tenant(r), filter(from, to, campaign, null, null, true), page, size);
    }

    @GetMapping("/accuracy")
    public Map<String, Object> accuracy(
            HttpServletRequest r,
            @RequestParam(required = false)
            String from,
            @RequestParam(required = false)
            String to) {
        return JsonSupport.map(
                "items", queries.accuracy(tenant(r), time(from, false), time(to, true)));
    }

    @GetMapping("/calls/{callId}")
    public Map<String, Object> detail(HttpServletRequest r, @PathVariable
    String callId) {
        return queries.detail(tenant(r), callId);
    }

    @PostMapping("/calls/{callId}/classify")
    public Map<String, Object> classify(HttpServletRequest r, @PathVariable
    String callId) {
        long t = tenant(r);
        var res = outcomes.classify(t, callId, OutcomeService.MANUAL);
        return JsonSupport.map("call_id", callId, "state", res.state(), "changed", res.changed());
    }

    @PostMapping("/calls/{callId}/review")
    public Map<String, Object> review(
            HttpServletRequest r,
            @PathVariable
            String callId,
            @RequestBody
            Map<String, Object> body) {
        var identity = access.operator(r);
        return outcomes.review(identity.tenant(), callId, body, access.actor(identity));
    }

    @PostMapping("/calls/{callId}/assistant-phrase")
    public Map<String, Object> learn(
            HttpServletRequest r,
            @PathVariable
            String callId,
            @RequestBody(required = false)
            Map<String, Object> body) {
        var identity = access.operator(r);
        Object phrase = body == null ? null : body.get("phrase");
        return outcomes.learnAssistantPhrase(
                identity.tenant(),
                callId,
                phrase == null ? null : phrase.toString(),
                access.actor(identity));
    }

    @GetMapping("/rules")
    public Map<String, Object> rules(HttpServletRequest r) {
        return rules.describe(tenant(r));
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/rules")
    public Map<String, Object> draft(
            HttpServletRequest r, @RequestBody(required = false)
            Map<String, Object> body) {
        var identity = access.operator(r);
        Map<String, Object> b = body == null ? Map.of() : body;
        return rules.draft(
                identity.tenant(),
                b.get("config") instanceof Map<?, ?> m ? (Map<String, Object>) m : null,
                b.get("note") == null ? null : b.get("note").toString(),
                access.actor(identity));
    }

    @SuppressWarnings("unchecked")
    @PutMapping("/rules/{version}")
    public Map<String, Object> save(
            HttpServletRequest r,
            @PathVariable
            int version,
            @RequestBody
            Map<String, Object> body) {
        long t = tenant(r);
        if (!(body.get("config") instanceof Map<?, ?> m)) throw new ApiError(422, "缺少规则配置");
        rules.save(
                t,
                version,
                new LinkedHashMap<>((Map<String, Object>) m),
                body.get("note") == null ? null : body.get("note").toString());
        return JsonSupport.map("version", version, "state", "DRAFT");
    }

    /** Publishes a draft; with rerun_from/rerun_to it also reclassifies that window. */
    @PostMapping("/rules/{version}/publish")
    public Map<String, Object> publish(
            HttpServletRequest r,
            @PathVariable
            int version,
            @RequestBody(required = false)
            Map<String, Object> body) {
        long t = tenant(r);
        rules.publish(t, version);
        Map<String, Object> out = JsonSupport.map("version", version, "state", "PUBLISHED");
        if (body != null && body.get("rerun_from") != null && body.get("rerun_to") != null)
            out.put(
                    "rerun",
                    outcomes.rerun(
                            t,
                            time(body.get("rerun_from").toString(), false),
                            time(body.get("rerun_to").toString(), true),
                            campaign(body),
                            limit()));
        return out;
    }

    @PostMapping("/rerun")
    public Map<String, Object> rerun(HttpServletRequest r, @RequestBody
    Map<String, Object> body) {
        long t = tenant(r);
        return outcomes.rerun(
                t,
                time(Objects.toString(body.get("from"), null), false),
                time(Objects.toString(body.get("to"), null), true),
                campaign(body),
                limit());
    }

    private static Long campaign(Map<String, Object> body) {
        Object c = body.get("campaign_id");
        if (c == null || c.toString().isBlank()) return null;
        return c instanceof Number n ? n.longValue() : Long.parseLong(c.toString());
    }

    private int limit() {
        return Math.max(1, config.number("MARKETING_OUTCOME_RERUN_LIMIT", 5000));
    }
}
