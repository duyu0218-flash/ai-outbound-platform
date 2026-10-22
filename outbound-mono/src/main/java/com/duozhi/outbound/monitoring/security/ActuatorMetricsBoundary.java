package com.duozhi.outbound.monitoring.security;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.operations.service.OperationalMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Actuator's unified exporter keeps the same explicit metrics bearer credential. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
public class ActuatorMetricsBoundary extends OncePerRequestFilter {
    private final OperationalMetrics metrics;
    private final ObjectMapper json;

    public ActuatorMetricsBoundary(OperationalMetrics metrics, ObjectMapper json) {
        this.metrics = metrics;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/actuator/prometheus".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            metrics.authorize(request.getHeader("Authorization"));
            metrics.refresh();
            chain.doFilter(request, response);
        } catch (ApiError denied) {
            response.setStatus(denied.status);
            response.setContentType("application/json;charset=UTF-8");
            json.writeValue(response.getOutputStream(), Map.of("detail", denied.getMessage()));
        }
    }
}
