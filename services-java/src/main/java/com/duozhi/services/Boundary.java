package com.duozhi.services;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class Boundary extends OncePerRequestFilter {
    private static final Set<String> SINGLE = Set.of("authorization", "host", "content-length", "x-voice-timestamp", "x-voice-nonce", "x-voice-signature");
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff"); response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Frame-Options", "DENY");
        for (String name : SINGLE) {
            List<String> values = Collections.list(request.getHeaders(name));
            if (values.size() > 1 || values.stream().anyMatch(v -> v.length() > 4096 || !v.chars().allMatch(c -> c >= 32 && c <= 126))) {
                reject(response, 400, "invalid request headers"); return;
            }
        }
        if (request.getContentLengthLong() > 1048576) { reject(response, 413, "request too large"); return; }
        // Read the bounded body before dispatch so chunked requests cannot bypass the limit.
        if (Set.of("POST", "PUT", "PATCH").contains(request.getMethod())) {
            byte[] body = request.getInputStream().readNBytes(1048577);
            if (body.length > 1048576) { reject(response, 413, "request too large"); return; }
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    var input = new ByteArrayInputStream(body);
                    return new ServletInputStream() {
                        public boolean isFinished() { return input.available() == 0; }
                        public boolean isReady() { return true; }
                        public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                        public int read() { return input.read(); }
                    };
                }
                @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8)); }
            }, response);
        } else chain.doFilter(request, response);
    }
    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.getOutputStream().write(Support.json(Map.of("detail", message)));
    }
}
