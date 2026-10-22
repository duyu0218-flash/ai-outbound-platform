package com.duozhi.outbound.operations.security;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.operations.service.OperationsAccess;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice
public class OperationsPhoneMask implements ResponseBodyAdvice<Object> {
    private final OperationsAccess access;

    OperationsPhoneMask(OperationsAccess access) {
        this.access = access;
    }

    public boolean supports(
            MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converter) {
        return true;
    }

    public Object beforeBodyWrite(
            Object body,
            MethodParameter type,
            MediaType media,
            Class<? extends HttpMessageConverter<?>> converter,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (request instanceof ServletServerHttpRequest servlet
                && servlet.getServletRequest().getAttribute("identity") instanceof Identity i
                && i.user() != null) {
            Object out = access.flag(i, "can_unmask") ? body : mask(body);
            return access.flag(i, "can_record") ? out : withoutRecording(out);
        }
        return body;
    }

    static Object withoutRecording(Object value) {
        if (value instanceof Map<?, ?> source) {
            var out = new LinkedHashMap<String, Object>();
            source.forEach(
                    (k, v) ->
                            out.put(
                                    k.toString(),
                                    Set.of(
                                                            "recording_url",
                                                            "recording_urls",
                                                            "recording_storage_uris",
                                                            "storage_uri",
                                                            "provider_url")
                                                    .contains(k.toString())
                                            ? null
                                            : withoutRecording(v)));
            return out;
        }
        if (value instanceof List<?> list)
            return list.stream().map(OperationsPhoneMask::withoutRecording).toList();
        return value;
    }

    public static Object mask(Object value) {
        if (value instanceof Map<?, ?> source) {
            var out = new LinkedHashMap<String, Object>();
            source.forEach(
                    (k, v) ->
                            out.put(
                                    k.toString(),
                                    Set.of("phone", "contact_phone").contains(k.toString())
                                                    && v != null
                                            ? v.toString().replaceAll("(?<=.{3}).(?=.{4})", "*")
                                            : mask(v)));
            return out;
        }
        if (value instanceof List<?> list)
            return list.stream().map(OperationsPhoneMask::mask).toList();
        return value;
    }
}
