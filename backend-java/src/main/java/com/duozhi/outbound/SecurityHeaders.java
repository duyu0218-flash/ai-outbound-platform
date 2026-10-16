package com.duozhi.outbound;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

/** Reject ambiguous credentials before any filter or controller consumes them. */
final class SecurityHeaders {
    private SecurityHeaders() {}
    private static final Map<String,Integer> LIMITS=Map.ofEntries(
        Map.entry("Authorization",8192),Map.entry("X-API-Key",4096),Map.entry("X-Tenant-ID",20),
        Map.entry("X-Security-Approval",4096),Map.entry("X-Webhook-Token",4096),
        Map.entry("X-Webhook-Timestamp",32),Map.entry("X-Webhook-Signature",128),
        Map.entry("Origin",2048),Map.entry("Host",512));
    static void validate(HttpServletRequest request) {
        LIMITS.forEach((name,max)->{
            Enumeration<String> values=request.getHeaders(name);
            if(values==null||!values.hasMoreElements())return;
            String value=values.nextElement();
            if(values.hasMoreElements()||value==null||value.length()>max
                    ||value.chars().anyMatch(c->c<32||c>126))
                throw new ApiError(400,"invalid or duplicate security header");
        });
    }
    static void outbound(Map<String,String> headers) {
        Set<String> names=new HashSet<>();
        for(Map.Entry<String,String> header:headers.entrySet()) {
            String name=header.getKey(),value=header.getValue();
            if(name==null||!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")
                    ||!names.add(Ascii.lower(name))||value==null||value.length()>8192
                    ||value.chars().anyMatch(c->c<32||c>126)
                    ||Set.of("host","content-length","transfer-encoding","connection").contains(Ascii.lower(name)))
                throw new ApiError(422,"invalid outbound header");
        }
    }
}
