package com.duozhi.outbound.common.aspect;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.HandlerMapping;

/** Bounded structural summaries: never serialize credentials, customer data or media payloads. */
final class LogContext {
    private LogContext() {}

    static HttpServletRequest request() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
                ? a.getRequest()
                : null;
    }

    static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern == null ? "(unmapped)" : safe(pattern.toString());
    }

    static String safe(String value) {
        if (value == null) return "-";
        return value.substring(0, Math.min(value.length(), 256))
                .replaceAll("[\\p{Cntrl}\\u2028\\u2029]", "_");
    }

    static String arguments(Object[] args) {
        if (args == null) return "[]";
        return Arrays.stream(args).limit(20).map(LogContext::summary).toList().toString();
    }

    static String summary(Object value) {
        if (value == null) return "null";
        if (value instanceof HttpServletRequest) return "HttpServletRequest";
        if (value instanceof HttpServletResponse) return "HttpServletResponse";
        if (value instanceof MultipartFile) return "MultipartFile";
        if (value instanceof ResponseEntity<?> response)
            return "HTTP " + response.getStatusCode().value() + " " + summary(response.getBody());
        if (value instanceof Map<?, ?> map) return "Map(size=" + map.size() + ")";
        if (value instanceof Collection<?> collection)
            return "Collection(size=" + collection.size() + ")";
        if (value.getClass().isArray())
            return "Array(length=" + java.lang.reflect.Array.getLength(value) + ")";
        return safe(value.getClass().getSimpleName()) + "(value omitted)";
    }

    static long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
