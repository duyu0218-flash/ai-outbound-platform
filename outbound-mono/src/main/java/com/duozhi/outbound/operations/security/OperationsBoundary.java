package com.duozhi.outbound.operations.security;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.operations.service.OperationsAccess;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.*;
import org.springframework.http.server.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class OperationsBoundary implements WebMvcConfigurer {
    private final OperationsAccess access;

    public OperationsBoundary(OperationsAccess access) {
        this.access = access;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(
                        new HandlerInterceptor() {
                            @Override
                            public boolean preHandle(
                                    HttpServletRequest r,
                                    HttpServletResponse response,
                                    Object handler) {
                                if (!(r.getAttribute("identity") instanceof Identity i)
                                        || i.user() == null) return true;
                                String path = r.getRequestURI();
                                if (path.matches("/api/v1/calls/[0-9a-fA-F-]{36}(?:/.*)?")) {
                                    if (!(r.getMethod().equals("POST")
                                            && path.matches(".*/handoffs/[0-9]+/accept")))
                                        access.call(i, path.split("/")[4]);
                                    if (path.endsWith("/recordings")
                                            && !access.flag(i, "can_record"))
                                        throw new ApiError(403, "没有录音访问权限");
                                }
                                if (access.managed(i)
                                        && path.startsWith("/api/v1/")
                                        && !path.startsWith("/api/v1/ops/")
                                        && !path.startsWith("/api/v1/auth/")
                                        && !path.startsWith("/api/v1/calls")
                                        && !path.startsWith("/api/v1/handoffs")
                                        && !path.startsWith("/api/v1/webrtc/")
                                        && !(r.getMethod().equals("GET")
                                                && path.startsWith("/api/v1/knowledge")))
                                    throw new ApiError(403, "请使用具有团队数据范围的运营工作台");
                                return true;
                            }
                        })
                .addPathPatterns("/api/v1/**");
    }
}
