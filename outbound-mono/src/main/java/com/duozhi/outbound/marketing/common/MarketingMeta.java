package com.duozhi.outbound.marketing.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Shared vocabulary for marketing pages; also the access probe for operator-only routes. */
@RestController
@RequestMapping("/api/v1/marketing")
public class MarketingMeta {
    private final MarketingAccess access;

    public MarketingMeta(MarketingAccess access) {
        this.access = access;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping(HttpServletRequest request) {
        access.operator(request);
        return Map.of("codes", OutcomeCodes.ALL.stream().sorted().toList());
    }
}
