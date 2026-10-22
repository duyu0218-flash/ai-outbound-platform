package com.duozhi.outbound.common.aspect;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.annotation.Log;
import com.duozhi.outbound.system.service.Audit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class LogAspect {
    private static final Logger logger = LoggerFactory.getLogger(LogAspect.class);
    private final Audit audit;

    public LogAspect(Audit audit) {
        this.audit = audit;
    }

    @Around("@annotation(operation)")
    public Object around(ProceedingJoinPoint point, Log operation) throws Throwable {
        var request = LogContext.request();
        Identity actor =
                request != null && request.getAttribute("identity") instanceof Identity i
                        ? i
                        : null;
        long started = System.nanoTime();
        String outcome = "success";
        try {
            return point.proceed();
        } catch (Throwable failure) {
            outcome = failure.getClass().getSimpleName();
            throw failure;
        } finally {
            if (actor != null) {
                try {
                    audit.record(
                            actor,
                            operation.value(),
                            "method",
                            null,
                            "method="
                                    + point.getSignature().toLongString()
                                    + "; route="
                                    + LogContext.route(request)
                                    + "; ip="
                                    + LogContext.safe(request.getRemoteAddr())
                                    + "; args="
                                    + LogContext.arguments(point.getArgs())
                                    + "; outcome="
                                    + outcome
                                    + "; elapsed_ms="
                                    + LogContext.elapsed(started));
                } catch (RuntimeException failure) {
                    // Observability must not change a completed operation or mask its exception.
                    logger.warn(
                            "operation_log_failed action={} error={}",
                            LogContext.safe(operation.value()),
                            failure.getClass().getSimpleName());
                }
            }
        }
    }
}
