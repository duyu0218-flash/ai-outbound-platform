package com.duozhi.outbound.marketing.dataset;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.support.JsonSupport;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Deterministic stand-in partner: the same number always gets the same answer, so pages, tests and
 * demos can run without any partner. Disabled in production unless MARKETING_MATCH_MOCK_ENABLED is
 * set explicitly.
 */
@Component
public class MockMatchAdapter implements MatchAdapter {
    static final List<String> LABELS = List.of("高活跃", "近期浏览", "价格敏感", "老客户", "夜间活跃", "多次比价");
    private final Config config;

    public MockMatchAdapter(Config config) {
        this.config = config;
    }

    @Override
    public String id() {
        return MOCK;
    }

    @Override
    public String label() {
        return "模拟合作方（确定性评分）";
    }

    @Override
    public boolean configured() {
        return config.flag("MARKETING_MATCH_MOCK_ENABLED", !config.production());
    }

    @Override
    public String unavailableReason() {
        return configured() ? "" : "生产环境未启用模拟合作方";
    }

    @Override
    public boolean online() {
        return true;
    }

    @Override
    public Outcome query(Query query) {
        byte[] d = JsonSupport.digest("mock-match:" + query.e164());
        int b0 = d[0] & 0xff;
        if (b0 % 10 >= 7)
            return new Outcome(
                    "no_data",
                    "10029",
                    "未查询到相关信息。",
                    "mock-" + JsonSupport.hex(d).substring(0, 16),
                    200,
                    Normalized.miss(Map.of("mock", true)));
        BigDecimal score =
                BigDecimal.valueOf((((d[1] & 0xff) << 8) | (d[2] & 0xff)) % 10001, 2)
                        .setScale(2, RoundingMode.HALF_UP);
        LinkedHashSet<Integer> hours = new LinkedHashSet<>();
        for (int i = 3; hours.size() < Math.max(1, Math.min(3, query.topN())) && i < d.length; i++)
            hours.add(8 + (d[i] & 0xff) % 14);
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < LABELS.size(); i++)
            if (((d[12] >> i) & 1) == 1) labels.add(LABELS.get(i));
        return new Outcome(
                "success",
                "OK",
                "查询成功。",
                "mock-" + JsonSupport.hex(d).substring(0, 16),
                200,
                new Normalized(
                        true,
                        score,
                        MatchAdapter.grade(score),
                        List.copyOf(hours),
                        labels,
                        Map.of("mock", true)));
    }
}
