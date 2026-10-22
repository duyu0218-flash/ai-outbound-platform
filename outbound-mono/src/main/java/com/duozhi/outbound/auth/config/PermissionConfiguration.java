package com.duozhi.outbound.auth.config;

import com.duozhi.outbound.auth.domain.AdminPermission;
import com.duozhi.outbound.auth.domain.Identity;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

@Configuration(proxyBeanMethods = false)
public class PermissionConfiguration implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(
                new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(
                            HttpServletRequest request,
                            HttpServletResponse response,
                            Object handler) {
                        if (handler instanceof HandlerMethod method) {
                            AdminPermission permission =
                                    AnnotatedElementUtils.findMergedAnnotation(
                                            method.getMethod(), AdminPermission.class);
                            if (permission == null)
                                permission =
                                        AnnotatedElementUtils.findMergedAnnotation(
                                                method.getBeanType(), AdminPermission.class);
                            if (permission != null) {
                                Identity identity = Identity.from(request);
                                if (!permission.supervisor() || identity.user() == null)
                                    identity.admin(permission.value());
                            }
                        }
                        return true;
                    }
                });
    }
}
