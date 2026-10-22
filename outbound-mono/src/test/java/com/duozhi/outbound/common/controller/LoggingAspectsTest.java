package com.duozhi.outbound.common.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.annotation.Log;
import com.duozhi.outbound.common.aspect.LogAspect;
import com.duozhi.outbound.common.aspect.WebLogAspect;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.system.service.Audit;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

class LoggingAspectsTest {
    final Audit audit = mock(Audit.class);
    final Identity actor = new Identity(2, Map.of("id", 7, "username", "operator"), Set::of);
    final Logger logger = (Logger) LoggerFactory.getLogger(WebLogAspect.class);
    final ListAppender<ILoggingEvent> events = new ListAppender<>();
    MockHttpServletRequest request;
    Probe controller;

    @BeforeEach
    void setup() {
        request = new MockHttpServletRequest("POST", "/probe/private-phone-13800138000");
        request.setQueryString("token=secret-query");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "spoofed-client");
        request.setAttribute("identity", actor);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/probe/{id}");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var factory = new AspectJProxyFactory(new Probe());
        factory.setProxyTargetClass(true);
        factory.addAspect(new WebLogAspect());
        factory.addAspect(new LogAspect(audit));
        controller = factory.getProxy();
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
        logger.detachAppender(events);
        events.stop();
    }

    @Test
    void logsSuccessfulCallsWithoutSerializingSecretsOrQueryStrings() {
        Map<String, Object> body = Map.of("password", "secret-password", "phone", "13800138000");
        assertThat(controller.save(body)).containsEntry("access_token", "secret-token");
        assertThat(events.list).hasSize(1);
        assertThat(events.list.getFirst().getFormattedMessage())
                .contains(
                        "route=/probe/{id}",
                        "ip=127.0.0.1",
                        "args=[Map(size=2)]",
                        "status=200",
                        "elapsed_ms=")
                .doesNotContain(
                        "secret-password",
                        "secret-token",
                        "13800138000",
                        "secret-query",
                        "spoofed-client");
        verify(audit)
                .record(
                        eq(actor),
                        eq("probe"),
                        eq("method"),
                        isNull(),
                        argThat(
                                detail ->
                                        detail.contains("outcome=success")
                                                && !detail.contains("secret-password")));
    }

    @Test
    void preservesBusinessExceptionAndLogsFailureDuration() {
        ApiError failure = new ApiError(409, "secret-exception-detail");
        assertThatThrownBy(() -> controller.fail(failure)).isSameAs(failure);
        assertThat(events.list.getFirst().getFormattedMessage())
                .contains("outcome=ApiError", "status=409", "elapsed_ms=")
                .doesNotContain("secret-exception-detail");
        verify(audit)
                .record(
                        eq(actor),
                        eq("probe"),
                        eq("method"),
                        isNull(),
                        contains("outcome=ApiError"));
    }

    @Test
    void auditFailureCannotChangeResultOrMaskOriginalException() {
        doThrow(new IllegalStateException("database unavailable"))
                .when(audit)
                .record(any(), anyString(), anyString(), any(), anyString());
        assertThat(controller.save(Map.of())).containsEntry("access_token", "secret-token");
        ApiError failure = new ApiError(409, "business failure");
        assertThatThrownBy(() -> controller.fail(failure)).isSameAs(failure);
    }

    @Test
    void anonymousRequestsDoNotInventAnAuditIdentity() {
        request.removeAttribute("identity");
        controller.save(Map.of());
        verifyNoInteractions(audit);
        assertThat(events.list).hasSize(1);
    }

    @Test
    void backgroundInvocationsAndControllerHelpersDoNotEmitWebLogs() {
        controller.helper();
        assertThat(events.list).isEmpty();
        RequestContextHolder.resetRequestAttributes();
        controller.save(Map.of());
        verifyNoInteractions(audit);
        assertThat(events.list).isEmpty();
    }

    @Test
    void zeroArgumentsAndResponseEntityStatusWork() {
        assertThat(controller.unavailable().getStatusCode().value()).isEqualTo(503);
        assertThat(events.list.getFirst().getFormattedMessage())
                .contains("args=[]", "result=HTTP 503", "status=503", "outcome=http_error");
    }

    @Test
    void neverInvokesPayloadToString() {
        controller.payload(
                new Object() {
                    @Override
                    public String toString() {
                        throw new AssertionError("must not serialize payload");
                    }
                });
        assertThat(events.list).hasSize(1);
    }

    public static class Probe {
        @PostMapping("/probe/{id}")
        @Log("probe")
        public Map<String, Object> save(Map<String, Object> body) {
            return Map.of("access_token", "secret-token");
        }

        @PostMapping("/probe/fail")
        @Log("probe")
        public void fail(ApiError failure) {
            throw failure;
        }

        @GetMapping("/probe/unavailable")
        ResponseEntity<?> unavailable() {
            return ResponseEntity.status(503).body(Map.of());
        }

        @PostMapping("/probe/payload")
        public Object payload(Object body) {
            return body;
        }

        public void helper() {}
    }
}
