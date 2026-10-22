package com.duozhi.outbound.common.config;

import com.duozhi.outbound.common.annotation.PolicyWrite;
import com.duozhi.outbound.common.service.PlatformLock;
import com.duozhi.outbound.common.service.Transactions;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
public class PolicyWriterConfiguration {
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor policyWriterAdvisor(PlatformLock locks) {
        var advisor =
                new DefaultPointcutAdvisor(
                        AnnotationMatchingPointcut.forMethodAnnotation(PolicyWrite.class),
                        (MethodInterceptor)
                                invocation -> {
                                    try (var held = locks.lock(Transactions.PLATFORM_KEY)) {
                                        return invocation.proceed();
                                    }
                                });
        // The transaction advisor defaults to LOWEST_PRECEDENCE. Taking this
        // lock outside it prevents policy waiters from exhausting the JDBC pool
        // while an admission holds the platform lock and needs a connection.
        advisor.setOrder(Ordered.LOWEST_PRECEDENCE - 1);
        return advisor;
    }
}
