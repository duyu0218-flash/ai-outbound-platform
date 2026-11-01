package com.duozhi.outbound.marketing.dataset;

import com.duozhi.outbound.common.config.Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Ant DAS personal intention scores, ported from ant-platform intention.py: the "phone" parameter
 * (11-digit mainland number), topN 1..3 (default 3), hour/score parsing, error-code mapping, a
 * process-wide QPS cap (DAS_QPS_LIMIT, default 300) and a 2-second back-off after throttling.
 * Transport failures are "uncertain" (possibly charged) and are never sent again automatically.
 */
@Component
public class AntDasMatchAdapter implements MatchAdapter {
    record Failure(String message, int status) {}

    static final Map<String, Failure> ERRORS =
            Map.of(
                    "10001", new Failure("参数不正确，请核对接口配置。", 502),
                    "10025", new Failure("当前账号尚未开通此服务，请联系管理员。", 503),
                    "10027", new Failure("上游未确认用户授权，请核对授权凭证。", 422),
                    "10029", new Failure("未查询到相关信息。", 200),
                    "10032", new Failure("上游流量限制，请稍后再试。", 429),
                    "10034", new Failure("数据源请求受限，请联系管理员。", 503),
                    "20001", new Failure("上游服务异常，请核对本次调用状态后再操作。", 502),
                    "20002", new Failure("当前账号没有接口访问权限。", 503),
                    "30009", new Failure("上游查询失败，请核对本次调用状态后再操作。", 502));
    static final Set<String> CRITICAL =
            Set.of("10001", "10025", "10027", "10032", "10034", "20002");

    private final ObjectMapper mapper;
    private final QueryLimiter limiter;
    private volatile DasGateway gateway;

    public AntDasMatchAdapter(HttpDasGateway gateway, Config config, ObjectMapper mapper) {
        this.gateway = gateway;
        this.mapper = mapper;
        int qps = config.number("DAS_QPS_LIMIT", 300);
        if (qps < 1 || qps > 300) throw new IllegalStateException("DAS_QPS_LIMIT must be 1..300");
        this.limiter = new QueryLimiter(qps);
    }

    /** Test seam: swaps the transport; returns the previous one so tests can restore it. */
    DasGateway useGateway(DasGateway replacement) {
        DasGateway previous = gateway;
        gateway = replacement;
        return previous;
    }

    @Override
    public String id() {
        return ANT_DAS;
    }

    @Override
    public String label() {
        return "蚂蚁 DAS 意愿分";
    }

    @Override
    public boolean configured() {
        return gateway.configured();
    }

    @Override
    public String unavailableReason() {
        return configured() ? "" : "意愿分服务尚未配置，请管理员确认接口权限、密钥和数据集 ID。";
    }

    @Override
    public boolean online() {
        return true;
    }

    @Override
    public int qps() {
        return limiter.qps();
    }

    @Override
    public Set<String> criticalCodes() {
        return CRITICAL;
    }

    static String local(String e164) {
        if (e164 == null || !e164.startsWith("+86")) return null;
        String local = e164.substring(3);
        return local.matches("1[3-9][0-9]{9}") ? local : null;
    }

    @Override
    public Outcome query(Query query) {
        String phone = local(query.e164());
        if (phone == null)
            return new Outcome("failed", "INVALID_PHONE", "仅支持中国大陆 11 位手机号码。", "", 400, null);
        int topN = query.topN() >= 1 && query.topN() <= 3 ? query.topN() : 3;
        limiter.acquire();
        String code = "UNKNOWN", requestId = "";
        Outcome outcome;
        try {
            var raw = gateway.query(phone, topN, query.authorizationRef());
            code = raw.code() == null || raw.code().isEmpty() ? "UNKNOWN" : raw.code();
            if (!code.matches("[A-Z0-9_]{1,32}")) code = "UNKNOWN";
            String rid = raw.requestId() == null ? "" : raw.requestId();
            requestId = rid.matches("[\\w-]{0,128}") ? rid : "";
            if ("OK".equals(code)) {
                var scores = parseScores(raw.data(), topN);
                outcome =
                        scores.isEmpty()
                                ? new Outcome(
                                        "no_data",
                                        code,
                                        "未查询到相关信息。",
                                        requestId,
                                        200,
                                        Normalized.miss(Map.of("scores", List.of())))
                                : new Outcome(
                                        "success",
                                        code,
                                        "查询成功。",
                                        requestId,
                                        200,
                                        normalize(scores));
            } else {
                var e = ERRORS.getOrDefault(code, new Failure("上游返回未知错误，请联系管理员核对。", 502));
                outcome =
                        "10029".equals(code)
                                ? new Outcome(
                                        "no_data",
                                        code,
                                        e.message(),
                                        requestId,
                                        200,
                                        Normalized.miss(Map.of("scores", List.of())))
                                : new Outcome(
                                        "failed", code, e.message(), requestId, e.status(), null);
            }
        } catch (IllegalArgumentException invalid) {
            outcome =
                    new Outcome(
                            "failed",
                            "INVALID_RESPONSE",
                            "评分结果格式异常，请联系管理员核对本次调用。",
                            requestId,
                            502,
                            null);
        } catch (DasGateway.DasException known) {
            var e = ERRORS.get(known.code);
            if (e == null) outcome = uncertain(requestId);
            else {
                code = known.code;
                outcome =
                        "10029".equals(code)
                                ? new Outcome(
                                        "no_data",
                                        code,
                                        e.message(),
                                        requestId,
                                        200,
                                        Normalized.miss(Map.of("scores", List.of())))
                                : new Outcome(
                                        "failed", code, e.message(), requestId, e.status(), null);
            }
        } catch (Exception transport) {
            // Exception bodies may contain keys or the phone: never stored or logged.
            outcome = uncertain(requestId);
        }
        if (Set.of("10032", "10034").contains(outcome.code())) limiter.defer(2);
        return outcome;
    }

    private static Outcome uncertain(String requestId) {
        return new Outcome(
                "uncertain",
                "TRANSPORT_ERROR",
                "未确认上游调用结果，可能已计费。请凭本次流水核对，勿直接重复查询。",
                requestId,
                502,
                null);
    }

    record HourScore(int hour, BigDecimal score) {}

    /** Port of parse_scores: list of {hour 0-23, score >= 0}, unique hours, best first. */
    List<HourScore> parseScores(String raw, int topN) {
        if (raw == null || raw.length() > 32768) throw new IllegalArgumentException("invalid data");
        JsonNode values;
        try {
            values = mapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid data");
        }
        if (!values.isArray() || values.size() > 24)
            throw new IllegalArgumentException("invalid scores");
        List<HourScore> scores = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (JsonNode item : values) {
            if (!item.isObject()) throw new IllegalArgumentException("invalid score");
            JsonNode hour = item.get("hour"), score = item.get("score");
            if (hour == null
                    || hour.isBoolean()
                    || !hour.asText().matches("(?:[0-9]|[01][0-9]|2[0-3])"))
                throw new IllegalArgumentException("invalid hour");
            if (score == null
                    || score.isBoolean()
                    || !(score.isTextual() || score.isNumber())
                    || score.asText().length() > 80)
                throw new IllegalArgumentException("invalid score");
            BigDecimal value;
            try {
                value = new BigDecimal(score.asText());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid score");
            }
            int h = Integer.parseInt(hour.asText());
            if (value.signum() < 0 || value.compareTo(new BigDecimal("1e20")) > 0 || !seen.add(h))
                throw new IllegalArgumentException("invalid score");
            scores.add(new HourScore(h, value));
        }
        scores.sort((a, b) -> b.score().compareTo(a.score()));
        return scores.subList(0, Math.min(topN, scores.size()));
    }

    static Normalized normalize(List<HourScore> scores) {
        BigDecimal top = MatchAdapter.percent(scores.getFirst().score());
        List<Map<String, Object>> raw = new ArrayList<>();
        for (var s : scores) raw.add(Map.of("hour", s.hour(), "score", s.score().toPlainString()));
        return new Normalized(
                true,
                top,
                MatchAdapter.grade(top),
                scores.stream().map(HourScore::hour).toList(),
                List.of(),
                Map.of("scores", raw));
    }
}
