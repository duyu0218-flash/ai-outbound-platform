package com.duozhi.outbound.auth.config;

import com.duozhi.outbound.auth.dao.AuthIdentityMapper;
import com.duozhi.outbound.auth.security.JwtFilter;
import com.duozhi.outbound.auth.security.JwtRealm;
import com.duozhi.outbound.auth.service.AuthStartupPolicy;
import com.duozhi.outbound.auth.service.Crypto;
import com.duozhi.outbound.client.security.ClientAuthenticator;
import com.duozhi.outbound.client.security.ClientJwtFilter;
import com.duozhi.outbound.common.config.Config;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import java.util.*;
import org.apache.shiro.mgt.DefaultSubjectDAO;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.apache.shiro.subject.*;
import org.apache.shiro.web.mgt.*;
import org.apache.shiro.web.mgt.DefaultWebSubjectFactory;
import org.apache.shiro.web.servlet.AbstractShiroFilter;
import org.apache.shiro.web.session.mgt.DefaultWebSessionManager;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@EnableTransactionManagement(proxyTargetClass = true)
@Configuration(proxyBeanMethods = false)
public class JwtSecurityConfiguration {
    @Bean(name = "filterShiroFilterRegistrationBean")
    FilterRegistrationBean<AbstractShiroFilter> shiroRegistration(ShiroFilterFactoryBean factory)
            throws Exception {
        var registration = new FilterRegistrationBean<AbstractShiroFilter>();
        registration.setFilter((AbstractShiroFilter) factory.getObject());
        registration.setName("shiroFilter");
        registration.setOrder(1);
        registration.setAsyncSupported(true);
        // ERROR dispatch is container-generated after an already checked request. Reauthenticating
        // /error via the fallback Basic chain hides server failures as spurious 401 responses.
        // Direct REQUEST/FORWARD to /error still passes the normal security chain.
        registration.setDispatcherTypes(
                DispatcherType.REQUEST,
                DispatcherType.ASYNC,
                DispatcherType.FORWARD,
                DispatcherType.INCLUDE);
        return registration;
    }

    @Bean
    JwtRealm jwtRealm(
            AuthIdentityMapper identities,
            Crypto crypto,
            Config config,
            ObjectMapper json,
            AuthStartupPolicy policy) {
        return new JwtRealm(identities, crypto, config, json);
    }

    @Bean
    DefaultWebSecurityManager securityManager(JwtRealm realm) {
        var manager = new DefaultWebSecurityManager(realm);
        var evaluator = new DefaultWebSessionStorageEvaluator();
        evaluator.setSessionStorageEnabled(false);
        var dao = new DefaultSubjectDAO();
        dao.setSessionStorageEvaluator(evaluator);
        manager.setSubjectDAO(dao);
        manager.setRememberMeManager(null);
        var sessions = new DefaultWebSessionManager();
        sessions.setSessionIdCookieEnabled(false);
        sessions.setSessionIdUrlRewritingEnabled(false);
        sessions.setSessionValidationSchedulerEnabled(false);
        manager.setSessionManager(sessions);
        manager.setSubjectFactory(
                new DefaultWebSubjectFactory() {
                    @Override
                    public Subject createSubject(SubjectContext context) {
                        context.setSessionCreationEnabled(false);
                        return super.createSubject(context);
                    }
                });
        return manager;
    }

    @Bean
    ShiroFilterFactoryBean shiroFilterFactoryBean(
            DefaultWebSecurityManager manager,
            AuthIdentityMapper identities,
            ObjectMapper json,
            ClientAuthenticator clients) {
        var factory = new ShiroFilterFactoryBean();
        factory.setSecurityManager(manager);
        factory.setFilters(
                new LinkedHashMap<>(
                        Map.of(
                                "jwt",
                                new JwtFilter(identities, json),
                                "clientJwt",
                                new ClientJwtFilter(clients, json))));
        var chain = new LinkedHashMap<String, String>();
        for (String path :
                List.of(
                        "/healthz",
                        "/api/v1/auth/login",
                        "/api/v1/runtime",
                        "/api/v1/voice-trial/socket",
                        "/api/v1/webhooks/bailian/trial-tool",
                        "/api/v1/webhooks/telephony/status",
                        "/api/v1/webhooks/telephony/speech",
                        "/api/v1/webhooks/telephony/transcript",
                        "/api/v1/webhooks/telephony/media",
                        "/api/v1/webhooks/telephony/recording",
                        "/api/v1/webhooks/sms/status",
                        "/api/v1/webhooks/telephony/dtmf",
                        "/api/v1/webhooks/telephony/batch",
                        "/api/v1/webhooks/telephony/batch-v2"))
            chain.put(path, "noSessionCreation,anon");
        for (String path :
                List.of(
                        "/docs",
                        "/openapi.json",
                        "/health",
                        "/readyz",
                        "/metrics",
                        "/metrics/runtime",
                        "/actuator/prometheus",
                        "/internal/freeswitch/directory",
                        "/admin",
                        "/admin/**",
                        "/agent",
                        "/agent/**",
                        "/client",
                        "/client/**",
                        "/assets/**",
                        "/index.html")) chain.put(path, "noSessionCreation,anon");
        // These original service routes enforce their own service tokens, signatures or signed
        // download permits.
        for (String path :
                List.of(
                        "/v1/call/*",
                        "/v1/admin/drain",
                        "/v1/admin/security",
                        "/v1/admin/security/stop",
                        "/v1/admin/capacity",
                        "/v1/admin/dial-control/*",
                        "/v1/admin/dial-counters/*",
                        "/v1/admin/voice-permits",
                        "/v1/admin/voice-permits/reconcile",
                        "/v1/internal/media-events",
                        "/v1/internal/voice-permits",
                        "/v1/recordings/*",
                        "/v1/recording-cleanup/*",
                        "/v1/pipecat/media/*")) chain.put(path, "noSessionCreation,anon");
        chain.put(
                "/openapi/v1/**",
                "noSessionCreation,anon"); // MessageApi verifies independent HMAC credentials.
        // Client portal tokens are a separate principal; they never reach the staff JWT chain.
        chain.put("/api/v1/client/auth/login", "noSessionCreation,anon");
        chain.put("/api/v1/client/**", "noSessionCreation,clientJwt");
        chain.put("/api/**", "noSessionCreation,jwt");
        chain.put("/**", "noSessionCreation,authcBasic");
        factory.setFilterChainDefinitionMap(chain);
        return factory;
    }
}
