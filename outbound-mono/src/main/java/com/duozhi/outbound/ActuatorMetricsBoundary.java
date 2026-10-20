package com.duozhi.outbound;
import java.io.IOException;
import java.util.Map;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
/** Actuator's unified exporter keeps the same explicit metrics bearer credential. */
@org.springframework.stereotype.Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE+11)
public class ActuatorMetricsBoundary extends org.springframework.web.filter.OncePerRequestFilter {
    private final OperationalMetrics metrics;private final com.fasterxml.jackson.databind.ObjectMapper json;
    public ActuatorMetricsBoundary(OperationalMetrics metrics,com.fasterxml.jackson.databind.ObjectMapper json){this.metrics=metrics;this.json=json;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){return !"/actuator/prometheus".equals(request.getRequestURI());}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{try{metrics.authorize(request.getHeader("Authorization"));metrics.refresh();chain.doFilter(request,response);}catch(ApiError denied){response.setStatus(denied.status);response.setContentType("application/json;charset=UTF-8");json.writeValue(response.getOutputStream(),Map.of("detail",denied.getMessage()));}}
}
