package com.duozhi.outbound.common.aspect;

import com.duozhi.outbound.common.exception.ApiError;
import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 20)
public class WebLogAspect {
    private static final Logger logger = LoggerFactory.getLogger(WebLogAspect.class);

    @Around("execution(* com.duozhi.outbound..controller..*(..))")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        var request = LogContext.request();
        Method method = ((MethodSignature) point.getSignature()).getMethod();
        if (request == null
                || !AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)
                || !logger.isInfoEnabled()) return point.proceed();
        long started = System.nanoTime();
        Integer status = 200;
        String outcome = "success";
        Object result = null;
        try {
            result = point.proceed();
            if (result instanceof ResponseEntity<?> response)
                status = response.getStatusCode().value();
            else if (RequestContextHolder.getRequestAttributes()
                            instanceof ServletRequestAttributes a
                    && a.getResponse() != null) status = a.getResponse().getStatus();
            if (status >= 400) outcome = "http_error";
            return result;
        } catch (Throwable failure) {
            // Other exceptions may be translated by ControllerAdvice after this advice returns.
            status = failure instanceof ApiError error ? error.status : null;
            outcome = failure.getClass().getSimpleName();
            throw failure;
        } finally {
            logger.info(
                    "web_request method={} route={} ip={} handler={} args={} result={} status={} outcome={} elapsed_ms={}",
                    LogContext.safe(request.getMethod()),
                    LogContext.route(request),
                    LogContext.safe(request.getRemoteAddr()),
                    point.getSignature().toShortString(),
                    LogContext.arguments(point.getArgs()),
                    LogContext.summary(result),
                    status,
                    outcome,
                    LogContext.elapsed(started));
        }
    }
}
